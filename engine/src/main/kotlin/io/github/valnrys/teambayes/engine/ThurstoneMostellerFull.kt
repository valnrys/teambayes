/*
 * Ported from openskill 6.2.0, Copyright (c) 2024 Vivek Joshy, MIT licence; see NOTICE.
 * Source: openskill/models/weng_lin/thurstone_mosteller_full.py and
 * openskill/models/weng_lin/common.py.
 */
package io.github.valnrys.teambayes.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The Weng-Lin Thurstone-Mosteller "full pairing" rating model, for games between
 * two teams.
 *
 * Each player's skill is a bell curve ([Rating]). A team's skill is the sum of its
 * players' curves. After a game, [rate] nudges every player's mu towards what the
 * result suggests and shrinks their sigma, because we've learned something.
 * [predictWin] and [predictDraw] answer "how likely is each result?" before a game.
 *
 * This is a line-by-line port of openskill 6.2.0's `ThurstoneMostellerFull`, reduced
 * to two teams and to the options TeamBayes uses (no weights, no scores, no
 * `limit_sigma`, no `balance`, and no per-call tau override). It matches
 * openskill's reference values within the tests' 1e-9 tolerance (observed agreement is
 * around 2e-13), quirks included; the comments in [rate] point
 * each one out. Most callers want [WengLin] rather than this constructor.
 *
 * @property mu starting skill for a new player, finite; see [EngineConfig.MU0].
 * @property sigma starting uncertainty for a new player, finite and >= 0; see [EngineConfig.SIGMA0].
 * @property beta match-day randomness, > 0; see [EngineConfig.betaFor].
 * @property tau uncertainty added before each game, >= 0; see [EngineConfig.TAU].
 * @property kappa floor on how far one game can shrink sigma, > 0; see [EngineConfig.KAPPA].
 * @property epsilon draw margin, >= 0; see [EngineConfig.EPSILON].
 */
class ThurstoneMostellerFull(
    val mu: Double = EngineConfig.MU0,
    val sigma: Double = EngineConfig.SIGMA0,
    val beta: Double = EngineConfig.BETA,
    val tau: Double = EngineConfig.TAU,
    val kappa: Double = EngineConfig.KAPPA,
    val epsilon: Double = EngineConfig.EPSILON,
) {

    init {
        // Written as positive checks so that NaN fails them too.
        require(mu.isFinite()) { "mu must be finite, was $mu" }
        require(sigma >= 0.0 && sigma.isFinite()) { "sigma must be finite and not negative, was $sigma" }
        require(beta > 0.0 && beta.isFinite()) { "beta must be positive and finite, was $beta" }
        require(kappa > 0.0 && kappa.isFinite()) { "kappa must be positive and finite, was $kappa" }
        require(epsilon >= 0.0 && epsilon.isFinite()) { "epsilon must be finite and not negative, was $epsilon" }
        require(tau >= 0.0 && tau.isFinite()) { "tau must be finite and not negative, was $tau" }
    }

    /** A rating for a player the model has never seen: [mu] ± [sigma]. */
    fun newRating(): Rating = Rating(mu, sigma)

    /**
     * Updates every player's rating after a game between [teamA] and [teamB].
     *
     * Returns the new ratings, each team in the same player order as given. The
     * inputs are not changed.
     *
     * Each list entry is treated as a separate player. openskill would apply tau and
     * the update twice to a player listed twice; this engine has no player ids and
     * can't tell. Callers must ensure no player appears twice, in one team or across
     * both.
     *
     * The model's [tau] is always used. openskill's per-call `tau` argument is not
     * exposed.
     *
     * @throws IllegalArgumentException if a team is empty, or if a team's total
     *   variance after tau is zero (every player has sigma 0 and tau is 0). openskill
     *   raises `ZeroDivisionError` there; we refuse rather than return NaN.
     */
    fun rate(teamA: List<Rating>, teamB: List<Rating>, outcome: Outcome): RatedTeams {
        requireTeams(teamA, teamB)

        // 1. Add tau to every sigma *before* the update: sigma' = sqrt(sigma² + tau²).
        //    This is the "skill may have drifted since last time" step.
        val tauSquared = tau * tau
        val playersA = teamA.map { Rating(it.mu, sqrt(it.sigma * it.sigma + tauSquared)) }
        val playersB = teamB.map { Rating(it.mu, sqrt(it.sigma * it.sigma + tauSquared)) }

        val a = teamSum(playersA)
        val b = teamSum(playersB)
        require(a.sigmaSquared > 0.0 && b.sigmaSquared > 0.0) {
            "Each team needs some uncertainty to rate: a team where every sigma is 0 " +
                "(with tau 0) has zero variance"
        }

        // 2. Update each team against the other. With only two teams, the order
        //    openskill sorts them into (by rank) changes nothing, so it is skipped.
        val newA = updateTeam(playersA, a, b, outcome.rankA, outcome.rankB)
        val newB = updateTeam(playersB, b, a, outcome.rankB, outcome.rankA)

        // 3. openskill 6.2.0 then tries to give tied teams the same average mu change.
        //    That step is a no-op: it reads the "before" mu from the very objects it
        //    has just updated in place, so every change it computes is zero. We copy
        //    the behaviour (no averaging), not the intent; the fixtures depend on it.
        return RatedTeams(newA, newB)
    }

    /**
     * The chance that each team wins.
     *
     * Draws don't get a share here: the result for team A is the chance that its
     * performance on the day beats team B's, and team B gets `1 - pA`. Tau is not
     * applied.
     *
     * @throws IllegalArgumentException if a team is empty.
     */
    fun predictWin(teamA: List<Rating>, teamB: List<Rating>): WinProbabilities {
        requireTeams(teamA, teamB)
        val a = teamSum(teamA)
        val b = teamSum(teamB)
        val result = NormalDist.cdf(
            (a.mu - b.mu) / sqrt(2 * (beta * beta) + a.sigmaSquared + b.sigmaSquared),
        )
        return WinProbabilities(result, 1 - result)
    }

    /**
     * The chance of a draw between [teamA] and [teamB]: higher means a more even game.
     * The team balancer maximises this.
     *
     * As in openskill, the draw margin assumes a draw rate of `1 / nPlayers`, where
     * `nPlayers` counts both teams. It is not configurable, and it ignores [epsilon].
     *
     * @throws IllegalArgumentException if a team is empty.
     */
    fun predictDraw(teamA: List<Rating>, teamB: List<Rating>): Double {
        requireTeams(teamA, teamB)
        val totalPlayers = teamA.size + teamB.size
        // openskill quirk, copied: the draw rate is assumed to be 1 / n, not learned.
        val drawProbability = 1.0 / totalPlayers
        // n enters twice: openskill multiplies beta by sqrt(n), assuming an unscaled beta,
        // but WengLin's beta is already scaled by sqrt(n / 2), so n scales it a second time.
        val drawMargin = sqrt(totalPlayers.toDouble()) * beta *
            NormalDist.invCdf((1 + drawProbability) / 2)

        val a = teamSum(teamA)
        val b = teamSum(teamB)
        val spread = sqrt(2 * (beta * beta) + a.sigmaSquared + b.sigmaSquared)
        return NormalDist.cdf((drawMargin - a.mu + b.mu) / spread) -
            NormalDist.cdf((b.mu - a.mu - drawMargin) / spread)
    }

    /**
     * New ratings for the players of one team, given the team totals for it ([self])
     * and its opponent ([other]), and both teams' ranks (lower is better).
     */
    private fun updateTeam(
        players: List<Rating>,
        self: TeamSum,
        other: TeamSum,
        selfRank: Int,
        otherRank: Int,
    ): List<Rating> {
        val betaSquared = beta * beta
        val c = sqrt(self.sigmaSquared + other.sigmaSquared + (2 * betaSquared))
        val deltaMu = (self.mu - other.mu) / c
        val sigmaSquaredToC = self.sigmaSquared / c
        // openskill's default gamma: sqrt(team variance) / c.
        val gamma = sqrt(self.sigmaSquared) / c
        // epsilon is the draw margin, and openskill passes it to wins and losses too.
        val t = epsilon / c

        // omega: how far the team's mu should move. delta: how much its variance shrinks.
        val omega: Double
        val delta: Double
        when {
            otherRank > selfRank -> { // this team won
                omega = sigmaSquaredToC * v(deltaMu, t)
                delta = gamma * sigmaSquaredToC / c * w(deltaMu, t)
            }
            otherRank < selfRank -> { // this team lost
                omega = -sigmaSquaredToC * v(-deltaMu, t)
                delta = gamma * sigmaSquaredToC / c * w(-deltaMu, t)
            }
            else -> { // draw
                omega = sigmaSquaredToC * vt(deltaMu, t)
                delta = gamma * sigmaSquaredToC / c * wt(deltaMu, t)
            }
        }

        // Each player takes a share of the team's change in proportion to their
        // variance: the less certain we are about a player, the more they move.
        return players.map { player ->
            val share = player.sigma * player.sigma / self.sigmaSquared
            val newMu = player.mu + share * omega
            // kappa stops sigma collapsing to zero (or going imaginary) after one game.
            val newSigma = player.sigma * sqrt(max(1 - share * delta, kappa))
            Rating(newMu, newSigma)
        }
    }

    private fun requireTeams(teamA: List<Rating>, teamB: List<Rating>) {
        require(teamA.isNotEmpty() && teamB.isNotEmpty()) { "Each team needs at least one player" }
    }

    /** A team's combined skill: the sums of its players' mu and sigma². */
    private class TeamSum(val mu: Double, val sigmaSquared: Double)

    /**
     * Sums a team the way openskill does: players in descending [Rating.ordinal]
     * order (a stable sort, so ties keep their order). Floating-point addition isn't
     * associative, so summing in the same order is what gives the same bits.
     */
    private fun teamSum(team: List<Rating>): TeamSum {
        var mu = 0.0
        var sigmaSquared = 0.0
        for (player in team.sortedByDescending { it.ordinal }) {
            mu += player.mu
            sigmaSquared += player.sigma * player.sigma
        }
        return TeamSum(mu, sigmaSquared)
    }

    internal companion object {
        /** Machine epsilon, 2^-52 (Python's `sys.float_info.epsilon`). */
        private val MACHINE_EPSILON = Math.ulp(1.0)

        /**
         * The mean shift for a win, `V(x, t)` in Weng and Lin (2011): `pdf(x - t) / cdf(x - t)`.
         * When `cdf(x - t)` is below machine epsilon the ratio is unstable, so it falls
         * back to `-(x - t)`, its asymptote.
         */
        internal fun v(x: Double, t: Double): Double {
            val xt = x - t
            val denominator = NormalDist.cdf(xt)
            return if (denominator < MACHINE_EPSILON) -xt else NormalDist.pdf(xt) / denominator
        }

        /**
         * The variance shrink for a win, `W(x, t)` in Weng and Lin (2011): `v * (v + x - t)`.
         * Falls back to 1 (for negative x) or 0 under the same condition as [v].
         */
        internal fun w(x: Double, t: Double): Double {
            val xt = x - t
            val denominator = NormalDist.cdf(xt)
            if (denominator < MACHINE_EPSILON) {
                return if (x < 0) 1.0 else 0.0
            }
            val vxt = v(x, t)
            return vxt * (vxt + xt)
        }

        /**
         * The mean shift for a draw, `Ṽ(x, t)` in Weng and Lin (2011). When the
         * probability mass of the draw band, `b`, is below 1e-5 it falls back to a
         * straight line in x.
         */
        internal fun vt(x: Double, t: Double): Double {
            val xx = abs(x)
            val b = NormalDist.cdf(t - xx) - NormalDist.cdf(-t - xx)
            if (b < 1e-5) {
                return if (x < 0) -x - t else -x + t
            }
            val a = NormalDist.pdf(-t - xx) - NormalDist.pdf(t - xx)
            return (if (x < 0) -a else a) / b
        }

        /**
         * The variance shrink for a draw, `W̃(x, t)` in Weng and Lin (2011). Falls back
         * to 1 when the draw band's mass is below machine epsilon.
         */
        internal fun wt(x: Double, t: Double): Double {
            val xx = abs(x)
            val b = NormalDist.cdf(t - xx) - NormalDist.cdf(-t - xx)
            if (b < MACHINE_EPSILON) {
                return 1.0
            }
            val vtxt = vt(x, t)
            return ((t - xx) * NormalDist.pdf(t - xx) + (t + xx) * NormalDist.pdf(-t - xx)) / b +
                vtxt * vtxt
        }
    }
}
