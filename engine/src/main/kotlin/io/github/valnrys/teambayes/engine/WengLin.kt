package io.github.valnrys.teambayes.engine

/**
 * The entry point for rating and predicting TeamBayes games.
 *
 * The model's match-day randomness depends on how many players are on the pitch, so
 * every function here works that number out from the two teams. Callers can't pass
 * a player count that doesn't match the teams.
 *
 * ```kotlin
 * val (newA, newB) = WengLin.rate(teamA, teamB, Outcome.DRAW)
 * val evenness = WengLin.predictDraw(teamA, teamB)
 * ```
 */
object WengLin {

    /**
     * Updates every player's rating after a game. See [ThurstoneMostellerFull.rate],
     * including its rule that a player must not appear twice.
     */
    fun rate(teamA: List<Rating>, teamB: List<Rating>, outcome: Outcome): RatedTeams =
        modelFor(teamA, teamB).rate(teamA, teamB, outcome)

    /** The chance that each team wins. See [ThurstoneMostellerFull.predictWin]. */
    fun predictWin(teamA: List<Rating>, teamB: List<Rating>): WinProbabilities =
        modelFor(teamA, teamB).predictWin(teamA, teamB)

    /** The chance of a draw: higher means a more even game. See [ThurstoneMostellerFull.predictDraw]. */
    fun predictDraw(teamA: List<Rating>, teamB: List<Rating>): Double =
        modelFor(teamA, teamB).predictDraw(teamA, teamB)

    /**
     * The rating model for a game with [nPlayers] players in total, using
     * [EngineConfig.betaFor] for beta and the [EngineConfig] defaults for everything
     * else. Useful when scoring many splits of the same roster, as the balancer does.
     *
     * **[nPlayers] must equal `teamA.size + teamB.size`** for every game you pass to
     * the returned model. Nothing checks this; a mismatch silently uses the wrong
     * beta. Prefer [rate], [predictWin] and [predictDraw] above when you can.
     *
     * @throws IllegalArgumentException if [nPlayers] is less than 2.
     */
    fun forGame(nPlayers: Int): ThurstoneMostellerFull =
        ThurstoneMostellerFull(beta = EngineConfig.betaFor(nPlayers))

    /** Checks both teams have players before counting them, so 1v0 gets the clearer message. */
    private fun modelFor(teamA: List<Rating>, teamB: List<Rating>): ThurstoneMostellerFull {
        require(teamA.isNotEmpty() && teamB.isNotEmpty()) { "Each team needs at least one player" }
        return forGame(teamA.size + teamB.size)
    }
}
