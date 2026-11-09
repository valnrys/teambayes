package io.github.valnrys.teambayes.engine

/**
 * One played game.
 *
 * @property id unique id of the game.
 * @property playedAt when the game was played, in epoch milliseconds.
 * @property createdAt when the game was entered into the app, in epoch milliseconds;
 *   it breaks ties between games played at the same moment.
 * @property teamA player ids on team A.
 * @property teamB player ids on team B.
 * @property outcome how the game ended.
 */
data class Game(
    val id: String,
    val playedAt: Long,
    val createdAt: Long,
    val teamA: List<String>,
    val teamB: List<String>,
    val outcome: Outcome,
)

/** One player's rating straight after the game [gameId], which was played at [playedAt]. */
data class RatingPoint(val gameId: String, val playedAt: Long, val rating: Rating)

/**
 * The result of a [replay].
 *
 * @property ratings every starting player's current rating, by player id.
 * @property history for every starting player, a [RatingPoint] after each game they
 *   played, oldest first. Players who never played have an empty list. This feeds charts.
 */
data class ReplayResult(
    val ratings: Map<String, Rating>,
    val history: Map<String, List<RatingPoint>>,
)

/**
 * Works out current ratings by playing every game again from the starting ratings.
 *
 * Ratings are never patched: if a game is edited, deleted or added late, just replay.
 * Games are applied in order of (playedAt, createdAt, id), whatever order they arrive in,
 * so the same games always give the same ratings.
 *
 * The rating model has no player ids, so it can't notice a player listed twice in one
 * game and would rate them as two people. That is why the checks below reject repeats here,
 * before anything is rated.
 *
 * @param starting each player's rating before any game, by player id.
 * @param games the games to apply, in any order.
 * @throws IllegalArgumentException if a game names a player not in [starting], has an
 *   empty team, lists a player twice (on both teams or twice in one), or if two games
 *   share an id. Nothing is rated until every game has passed these checks. Also thrown
 *   (by the model) if a team's players all have sigma 0.
 */
fun replay(starting: Map<String, Rating>, games: List<Game>): ReplayResult {
    validate(starting, games)

    val ratings = starting.toMutableMap()
    val history = starting.keys.associateWith { mutableListOf<RatingPoint>() }

    val ordered = games.sortedWith(compareBy<Game>({ it.playedAt }, { it.createdAt }, { it.id }))
    for (game in ordered) {
        val (newA, newB) = WengLin.rate(
            teamA = game.teamA.map { ratings.getValue(it) },
            teamB = game.teamB.map { ratings.getValue(it) },
            outcome = game.outcome,
        )
        val updates = (game.teamA + game.teamB).zip(newA + newB)
        for ((player, rating) in updates) {
            ratings[player] = rating
            history.getValue(player) += RatingPoint(game.id, game.playedAt, rating)
        }
    }
    return ReplayResult(ratings, history)
}

private fun validate(starting: Map<String, Rating>, games: List<Game>) {
    val seenIds = mutableSetOf<String>()
    for (game in games) {
        require(seenIds.add(game.id)) { "Duplicate game id '${game.id}'" }
        require(game.teamA.isNotEmpty() && game.teamB.isNotEmpty()) {
            "Game '${game.id}' has an empty team"
        }
        val players = game.teamA + game.teamB
        val repeated = players.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) {
            "Game '${game.id}' lists player(s) more than once: ${repeated.joinToString()}"
        }
        val unknown = players.filter { it !in starting }
        require(unknown.isEmpty()) {
            "Game '${game.id}' has player(s) with no starting rating: ${unknown.joinToString()}"
        }
    }
}
