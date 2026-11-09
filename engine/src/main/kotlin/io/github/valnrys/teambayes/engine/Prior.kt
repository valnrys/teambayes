package io.github.valnrys.teambayes.engine

/**
 * Starting skill for players, worked out from a rough best-to-worst ranking instead
 * of making everyone start level.
 *
 * Only mu comes from the ranking. Sigma always starts at [EngineConfig.SIGMA0], which
 * is wide, so the ranking is a gentle nudge: a few real results outweigh it.
 */
object Prior {

    /**
     * How far from [EngineConfig.MU0] the best and worst ranked players start, in units
     * of [EngineConfig.BETA].
     *
     * Why two betas: `2 * BETA` is about 8.3 skill points, which is exactly one starting
     * sigma ([EngineConfig.SIGMA0]). So the best and worst players start one sigma from
     * the centre. That is enough for the ranking to steer the first few team splits,
     * but the ranking is only a rough guess, and sigma is still wide, so a few real
     * results outweigh it.
     */
    private const val SPREAD_IN_BETAS = 2.0

    /**
     * Starting mu values for [count] players ranked best to worst.
     *
     * The values are evenly spaced from `MU0 + 2 * BETA` (the best, first in the list)
     * down to `MU0 - 2 * BETA` (the worst, last). A single player gets [EngineConfig.MU0].
     * This uses the base [EngineConfig.BETA], not [EngineConfig.betaFor], because there
     * is no game yet.
     *
     * @param count how many players are being ranked.
     * @return one mu per player, in ranking order (best first); empty if [count] is 0.
     * @throws IllegalArgumentException if [count] is negative.
     */
    fun fromRankOrder(count: Int): List<Double> {
        require(count >= 0) { "count must not be negative, was $count" }
        if (count == 1) return listOf(EngineConfig.MU0)

        val best = EngineConfig.MU0 + SPREAD_IN_BETAS * EngineConfig.BETA
        val step = 2 * SPREAD_IN_BETAS * EngineConfig.BETA / (count - 1)
        return List(count) { rank -> best - rank * step }
    }

    /**
     * A starting mu for a player added later, who the organiser places between two
     * existing players.
     *
     * - Both neighbours given: the midpoint of their current mu.
     * - Only [above] given (new player is the worst): [EngineConfig.BETA] below it.
     * - Only [below] given (new player is the best): [EngineConfig.BETA] above it.
     * - Neither given (first player): [EngineConfig.MU0].
     *
     * Going past either end always takes a full [EngineConfig.BETA] step, so the result
     * can end up beyond the `MU0 +/- 2 * BETA` range that [fromRankOrder] uses.
     *
     * Callers should take the neighbours from a list sorted by current mu. After a few
     * games two players' mus can cross, so neighbours taken from an old manual order
     * could be the wrong way round, and this would throw.
     *
     * @param above the current mu of the next better player, or `null` if there is none.
     * @param below the current mu of the next worse player, or `null` if there is none.
     * @throws IllegalArgumentException if a value isn't finite, or [above] is lower than
     *   [below] (equal is fine).
     */
    fun insertBetween(above: Double?, below: Double?): Double {
        require(above == null || above.isFinite()) { "above must be finite, was $above" }
        require(below == null || below.isFinite()) { "below must be finite, was $below" }
        require(above == null || below == null || above >= below) {
            "above ($above) must not be lower than below ($below): the better player comes first"
        }
        return when {
            above != null && below != null -> (above + below) / 2
            above != null -> above - EngineConfig.BETA
            below != null -> below + EngineConfig.BETA
            else -> EngineConfig.MU0
        }
    }
}
