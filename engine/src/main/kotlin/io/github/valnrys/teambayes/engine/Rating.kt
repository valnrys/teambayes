package io.github.valnrys.teambayes.engine

/**
 * What the engine believes about one player's skill: probably around [mu], give or
 * take [sigma]. A new player starts at [EngineConfig.MU0] ± [EngineConfig.SIGMA0];
 * every game moves mu and usually shrinks sigma.
 *
 * @property mu the best guess at the player's skill. Must be finite.
 * @property sigma how unsure that guess is (one standard deviation). Must be finite and not negative.
 */
data class Rating(val mu: Double, val sigma: Double) {

    init {
        require(mu.isFinite()) { "mu must be finite, was $mu" }
        require(sigma.isFinite() && sigma >= 0.0) { "sigma must be finite and not negative, was $sigma" }
    }

    /**
     * A cautious single-number skill, `mu - 3 * sigma`: the player is very likely
     * (about 99.9%) at least this good. Useful for leaderboards, because a new player
     * with a lucky first game doesn't jump to the top. openskill calls this the ordinal.
     */
    val ordinal: Double
        get() = mu - 3.0 * sigma
}

/**
 * How a two-team game ended.
 *
 * openskill describes results as ranks, where a lower rank is a better finish and
 * equal ranks are a draw. [rankA] and [rankB] give that mapping.
 */
enum class Outcome(val rankA: Int, val rankB: Int) {
    /** Team A won: ranks `[0, 1]`. */
    A_WINS(0, 1),

    /** Team B won: ranks `[1, 0]`. */
    B_WINS(1, 0),

    /** A draw: ranks `[0, 0]`. */
    DRAW(0, 0),
}

/**
 * Both teams' ratings after a game, each in the same player order as given.
 * Destructures as `val (newA, newB) = ...`.
 */
data class RatedTeams(val teamA: List<Rating>, val teamB: List<Rating>)

/**
 * The chance that each team wins. The two add up to 1: draws don't get a separate
 * share. Destructures as `val (pA, pB) = ...`.
 */
data class WinProbabilities(val teamA: Double, val teamB: Double)
