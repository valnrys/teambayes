package io.github.valnrys.teambayes.data

import androidx.room3.withWriteTransaction
import io.github.valnrys.teambayes.data.db.AppearanceEntity
import io.github.valnrys.teambayes.data.db.GameDao
import io.github.valnrys.teambayes.data.db.PlayerDao
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase
import io.github.valnrys.teambayes.engine.Outcome
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How the teams of a game were chosen. */
enum class Selection {
    /** Picked by the balancer and not touched. */
    GENERATED,

    /** Picked by the balancer, then changed by the organiser. */
    EDITED,

    /** Picked by hand, without the balancer. */
    MANUAL,
}

/** The two sides of a game. */
enum class Team { A, B }

/** A final or running score. */
data class Score(val a: Int, val b: Int)

/** One player's goals in one game. */
data class PlayerGoals(val playerId: String, val goals: Int)

/**
 * The game being played now, or just finished and not yet confirmed.
 *
 * @property playedAt when the game was played, epoch milliseconds.
 * @property createdAt when the game was entered into the app, epoch milliseconds.
 * @property teamA the players on team A with their goals so far (no meaningful order).
 * @property teamB the players on team B with their goals so far.
 * @property scoreOverride the score the organiser typed in "adjust score" mode, or `null`
 *   if the score is just the sum of the goals.
 */
data class PendingGame(
    val id: String,
    val playedAt: Long,
    val createdAt: Long,
    val selection: Selection,
    val teamA: List<PlayerGoals>,
    val teamB: List<PlayerGoals>,
    val scoreOverride: Score?,
)

/**
 * A finished game, which counts towards ratings.
 *
 * @property outcome who won, using the engine's own type.
 * @property scoreA team A's final score (may differ from the sum of [teamA] goals).
 * @property scoreB team B's final score.
 */
data class FinalGame(
    val id: String,
    val playedAt: Long,
    val createdAt: Long,
    val selection: Selection,
    val outcome: Outcome,
    val scoreA: Int,
    val scoreB: Int,
    val teamA: List<PlayerGoals>,
    val teamB: List<PlayerGoals>,
)

/** Thrown by [GameRepository.savePending] when a pending game already exists. */
class PendingGameExistsException : IllegalStateException("A pending game already exists")

/** Thrown when an operation needs a pending game and there is none. */
class NoPendingGameException : IllegalStateException("There is no pending game")

/**
 * Where games live. A game is first *pending* (teams chosen, goals being counted) and
 * becomes *final* once the organiser confirms the result. Only final games count towards
 * ratings. There is at most one pending game at a time.
 *
 * Every write is a single database transaction: it fully happens or not at all.
 */
interface GameRepository {
    /** The pending game, or `null` if there is none. Updates whenever it changes. */
    val pendingGame: Flow<PendingGame?>

    /** Every finalised game, oldest first (by played time, then entry time, then id). */
    val finalGames: Flow<List<FinalGame>>

    /**
     * Saves the chosen teams as the pending game, every player on 0 goals, and returns its id.
     *
     * @param playedAt when the game is played, epoch milliseconds.
     * @throws PendingGameExistsException if there already is a pending game.
     * @throws IllegalArgumentException if a team is empty, a player is listed twice, or a
     *   player is unknown.
     */
    suspend fun savePending(
        playedAt: Long,
        teamA: List<String>,
        teamB: List<String>,
        selection: Selection,
    ): String

    /**
     * Sets how many goals [playerId] scored in the pending game.
     *
     * @throws NoPendingGameException if there is no pending game.
     * @throws IllegalArgumentException if [goals] is negative or the player is not in the game.
     */
    suspend fun setGoals(playerId: String, goals: Int)

    /**
     * Stores the score typed in "adjust score" mode, or clears it with `null`. Goals are
     * stored per player, so a score that differs from their sum needs its own place.
     *
     * @throws NoPendingGameException if there is no pending game.
     * @throws IllegalArgumentException if a score is negative.
     */
    suspend fun setScoreOverride(score: Score?)

    /**
     * Confirms the pending game: it becomes final and counts towards ratings. Returns its id.
     * The winner is whatever the caller says; it is not forced to agree with the score.
     *
     * @throws NoPendingGameException if there is no pending game.
     * @throws IllegalArgumentException if a score is negative.
     */
    suspend fun finalise(winner: Outcome, scoreA: Int, scoreB: Int): String

    /** Throws the pending game away, with its goals. Does nothing if there is none. */
    suspend fun discardPending()
}

/**
 * [GameRepository] backed by the Room database.
 *
 * @param now the clock, in epoch milliseconds; replaced in tests.
 * @param newId makes a fresh game id; replaced in tests.
 */
class RoomGameRepository(
    private val database: TeamBayesDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : GameRepository {

    private val games: GameDao = database.gameDao()
    private val players: PlayerDao = database.playerDao()

    override val pendingGame: Flow<PendingGame?> =
        games.observePending().map { it?.toPendingGame() }

    override val finalGames: Flow<List<FinalGame>> =
        games.observeFinal()
            .map { rows -> rows.map { it.toFinalGame() } }
            // Goal taps change `appearances`, which re-runs this query. The final games are
            // the same, so drop the repeat and spare the ratings a pointless replay.
            .distinctUntilChanged()

    override suspend fun savePending(
        playedAt: Long,
        teamA: List<String>,
        teamB: List<String>,
        selection: Selection,
    ): String {
        require(teamA.isNotEmpty() && teamB.isNotEmpty()) { "Both teams need at least one player" }
        val everyone = teamA + teamB
        require(everyone.toSet().size == everyone.size) { "A player can only be in a game once" }

        return database.withWriteTransaction {
            if (games.getPending() != null) throw PendingGameExistsException()
            val known = players.existingIds(everyone).toSet()
            val unknown = everyone.filter { it !in known }
            require(unknown.isEmpty()) { "Unknown player(s): ${unknown.joinToString()}" }

            val id = newId()
            games.insertGame(newGameRow(id, playedAt, selection, createdAt = now()))
            games.insertAppearances(
                teamA.map { AppearanceEntity(id, it, Team.A.toCode(), goals = 0) } +
                    teamB.map { AppearanceEntity(id, it, Team.B.toCode(), goals = 0) },
            )
            id
        }
    }

    override suspend fun setGoals(playerId: String, goals: Int) {
        require(goals >= 0) { "Goals can't be negative, was $goals" }
        database.withWriteTransaction {
            val pending = games.getPending() ?: throw NoPendingGameException()
            val updated = games.setGoals(pending.id, playerId, goals)
            require(updated == 1) { "Player $playerId is not in the pending game" }
        }
    }

    override suspend fun setScoreOverride(score: Score?) {
        require(score == null || (score.a >= 0 && score.b >= 0)) { "A score can't be negative" }
        database.withWriteTransaction {
            val pending = games.getPending() ?: throw NoPendingGameException()
            games.setScore(pending.id, score?.a, score?.b)
        }
    }

    override suspend fun finalise(winner: Outcome, scoreA: Int, scoreB: Int): String {
        require(scoreA >= 0 && scoreB >= 0) { "A score can't be negative" }
        return database.withWriteTransaction {
            val pending = games.getPending() ?: throw NoPendingGameException()
            games.finalise(pending.id, winner.toCode(), scoreA, scoreB)
            pending.id
        }
    }

    override suspend fun discardPending() {
        database.withWriteTransaction {
            // The appearances go with it: the foreign key cascades the delete.
            games.getPending()?.let { games.deletePendingGame(it.id) }
        }
    }
}
