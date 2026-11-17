package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Game
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.replay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A stored player with their current rating. */
data class RatedPlayer(val stored: StoredPlayer, val rating: Rating)

/**
 * Current ratings, worked out on demand.
 *
 * Ratings are **never stored**. They are recomputed by replaying every finalised game, in
 * order, from each player's starting rating (see the engine's `replay`). So editing,
 * deleting or back-dating a game can't leave a stale rating behind.
 *
 * The replay runs on [dispatcher] (a background thread by default), never the main thread.
 */
class RatingsRepository(
    private val playerRepository: PlayerRepository,
    private val gameRepository: GameRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Every player with their current rating, alphabetical by name. This is the one flow
     * screens should collect: it watches players and games once and replays once per
     * change, so a screen needn't combine the player list with ratings itself. Emits again
     * whenever a player is added or a game is finalised.
     */
    val roster: Flow<List<RatedPlayer>> =
        combine(playerRepository.players, gameRepository.finalGames) { players, games ->
            rosterOf(players, games)
        }.flowOn(dispatcher)

    /** The same ratings as [roster], as a map by player id. */
    val ratings: Flow<Map<String, Rating>> =
        roster.map { players -> players.associate { it.stored.id to it.rating } }

    /**
     * What game [gameId] did to each of its players: their rating just **before** it and
     * just **after** it, in replay order. Later games (and back-dated games played after
     * this one) have no effect on either number, so this is right even when the game was
     * entered late. The done screen shows the difference.
     *
     * The map holds only the players who took part.
     *
     * @throws IllegalArgumentException if there is no finalised game with that id.
     */
    suspend fun ratingChange(gameId: String): Map<String, Pair<Rating, Rating>> {
        val players = playerRepository.players.first()
        val games = gameRepository.finalGames.first()
        return withContext(dispatcher) { ratingChange(players, games, gameId) }
    }

    /**
     * Ratings as they were just before game [gameId]: every game earlier in replay order
     * has been played, [gameId] and later ones have not. Includes every player, at their
     * starting rating if they had no earlier game.
     *
     * @throws IllegalArgumentException if there is no finalised game with that id.
     */
    suspend fun ratingsBefore(gameId: String): Map<String, Rating> {
        val players = playerRepository.players.first()
        val games = gameRepository.finalGames.first()
        return withContext(dispatcher) { ratingsBefore(players, games, gameId) }
    }
}

// The pure parts, kept apart from Flows and threads so plain JVM tests can check them.

/** Each player's rating before any game: their stored mu, and the engine's starting sigma. */
internal fun startingRatings(players: List<StoredPlayer>): Map<String, Rating> =
    players.associate { it.id to Rating(it.startingMu, EngineConfig.SIGMA0) }

/** The engine's view of a finalised game. */
internal fun FinalGame.toEngineGame() = Game(
    id = id,
    playedAt = playedAt,
    createdAt = createdAt,
    teamA = teamA.map { it.playerId },
    teamB = teamB.map { it.playerId },
    outcome = outcome,
)

/** The order `replay` applies games in: played time, then entry time, then id. */
private val replayOrder = compareBy<FinalGame>({ it.playedAt }, { it.createdAt }, { it.id })

/** Replays all [games] from the players' starting ratings. */
internal fun currentRatings(players: List<StoredPlayer>, games: List<FinalGame>): Map<String, Rating> =
    replay(startingRatings(players), games.map { it.toEngineGame() }).ratings

/** Every player with their rating after replaying all [games], in [players]' order. */
internal fun rosterOf(players: List<StoredPlayer>, games: List<FinalGame>): List<RatedPlayer> {
    val ratings = currentRatings(players, games)
    return players.map { RatedPlayer(it, ratings.getValue(it.id)) }
}

/**
 * Replays only the games that come before [gameId] in replay order.
 *
 * @throws IllegalArgumentException if [gameId] isn't among [games].
 */
internal fun ratingsBefore(
    players: List<StoredPlayer>,
    games: List<FinalGame>,
    gameId: String,
): Map<String, Rating> {
    val target = requireNotNull(games.firstOrNull { it.id == gameId }) { "No finalised game '$gameId'" }
    val earlier = games.filter { replayOrder.compare(it, target) < 0 }
    return currentRatings(players, earlier)
}

/**
 * For each player in game [gameId]: (rating before it, rating after it), read from the
 * engine's replay history so only games up to and including [gameId] matter.
 *
 * @throws IllegalArgumentException if [gameId] isn't among [games].
 */
internal fun ratingChange(
    players: List<StoredPlayer>,
    games: List<FinalGame>,
    gameId: String,
): Map<String, Pair<Rating, Rating>> {
    val target = requireNotNull(games.firstOrNull { it.id == gameId }) { "No finalised game '$gameId'" }
    val starting = startingRatings(players)
    val history = replay(starting, games.map { it.toEngineGame() }).history
    return (target.teamA + target.teamB).associate { (playerId) ->
        val points = history.getValue(playerId)
        val index = points.indexOfFirst { it.gameId == gameId }
        val before = if (index > 0) points[index - 1].rating else starting.getValue(playerId)
        playerId to (before to points[index].rating)
    }
}
