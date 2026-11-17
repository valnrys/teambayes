package io.github.valnrys.teambayes.fakes

import io.github.valnrys.teambayes.data.DuplicateNameException
import io.github.valnrys.teambayes.data.FinalGame
import io.github.valnrys.teambayes.data.GameRepository
import io.github.valnrys.teambayes.data.PendingGame
import io.github.valnrys.teambayes.data.PendingGameExistsException
import io.github.valnrys.teambayes.data.PlayerGoals
import io.github.valnrys.teambayes.data.PlayerRepository
import io.github.valnrys.teambayes.data.Score
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.data.StoredPlayer
import io.github.valnrys.teambayes.data.normaliseName
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.engine.Prior
import io.github.valnrys.teambayes.matchday.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Repositories that keep everything in memory, so ViewModel tests run on the laptop
// without a database. They follow the real ones' rules where match day relies on them
// (unique names, one pending game) and remember what they were asked to do.

/** A stored, active player with the same id, name and starting mu as [player]. */
fun storedFrom(player: Player, active: Boolean = true) =
    StoredPlayer(id = player.id, name = player.name, active = active, startingMu = player.rating.mu, createdAt = 0)

/**
 * Players in memory.
 *
 * @param initial the players stored at the start.
 * @param reportAddsAtOnce if false, [players] doesn't show an added player until
 *   [reportAdds] is called, like a database whose flow emits a little after the write.
 */
class InMemoryPlayerRepository(
    initial: List<StoredPlayer> = emptyList(),
    private val reportAddsAtOnce: Boolean = true,
) : PlayerRepository {
    /** What is stored, which [players] may not have reported yet. */
    private var stored = initial.sortedBy { it.name.lowercase() }
    private val reported = MutableStateFlow(stored)

    override val players: StateFlow<List<StoredPlayer>> = reported

    /** Makes [players] catch up with what is stored. */
    fun reportAdds() {
        reported.value = stored
    }

    /** Each [addPlayer] call's name and starting mu, in order. */
    val added = mutableListOf<Pair<String, Double>>()

    /** If set, [addPlayer] waits for it before saving, so a test can act while a save is running. */
    var gate: CompletableDeferred<Unit>? = null

    private var nextId = 1

    override suspend fun addPlayersInOrder(namesBestToWorst: List<String>) {
        val mus = Prior.fromRankOrder(namesBestToWorst.size)
        namesBestToWorst.zip(mus).forEach { (name, mu) -> addPlayer(name, mu) }
    }

    override suspend fun addPlayer(name: String, startingMu: Double): String {
        gate?.await()
        val clean = normaliseName(name)
        if (stored.any { it.name.equals(clean, ignoreCase = true) }) throw DuplicateNameException(clean)
        val player = StoredPlayer("new-${nextId++}", clean, active = true, startingMu = startingMu, createdAt = 0)
        added += clean to startingMu
        stored = (stored + player).sortedBy { it.name.lowercase() }
        if (reportAddsAtOnce) reportAdds()
        return player.id
    }
}

/**
 * Games in memory. Only what match day uses so far is filled in.
 *
 * @param finalGames finished games, for ratings.
 */
class InMemoryGameRepository(finalGames: List<FinalGame> = emptyList()) : GameRepository {

    /** One [savePending] call's arguments. */
    data class SavedPending(val playedAt: Long, val teamA: List<String>, val teamB: List<String>, val selection: Selection)

    /** Each successful [savePending] call, in order. */
    val savedPending = mutableListOf<SavedPending>()

    /** If set, [savePending] waits for it, so a test can act while a save is running. */
    var gate: CompletableDeferred<Unit>? = null

    private val pending = MutableStateFlow<PendingGame?>(null)
    override val pendingGame: StateFlow<PendingGame?> = pending
    override val finalGames: StateFlow<List<FinalGame>> = MutableStateFlow(finalGames)

    override suspend fun savePending(
        playedAt: Long,
        teamA: List<String>,
        teamB: List<String>,
        selection: Selection,
    ): String {
        gate?.await()
        if (pending.value != null) throw PendingGameExistsException()
        savedPending += SavedPending(playedAt, teamA, teamB, selection)
        val id = "game-${savedPending.size}"
        pending.value = PendingGame(
            id = id,
            playedAt = playedAt,
            createdAt = 0,
            selection = selection,
            teamA = teamA.map { PlayerGoals(it, 0) },
            teamB = teamB.map { PlayerGoals(it, 0) },
            scoreOverride = null,
        )
        return id
    }

    override suspend fun setGoals(playerId: String, goals: Int) = notYet()
    override suspend fun setScoreOverride(score: Score?) = notYet()
    override suspend fun finalise(winner: Outcome, scoreA: Int, scoreB: Int): String = notYet()
    override suspend fun discardPending() {
        pending.value = null
    }

    private fun notYet(): Nothing = throw UnsupportedOperationException("Match day doesn't record results yet")
}
