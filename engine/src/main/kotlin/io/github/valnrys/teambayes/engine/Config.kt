package io.github.valnrys.teambayes.engine

import kotlin.math.sqrt

/**
 * The fixed numbers behind every rating. They match openskill's defaults for the
 * Thurstone-Mosteller model, except [betaFor], which scales with the size of the game.
 *
 * Ratings are on openskill's scale: a brand-new player is 25 ± 8.3, and the numbers
 * only mean something relative to each other. See `docs/model.md` for the full story.
 */
object EngineConfig {

    /** Starting skill (mu) for a new player: an average player. See `docs/model.md`. */
    const val MU0: Double = 25.0

    /** Starting uncertainty (sigma) for a new player: we know nothing yet. See `docs/model.md`. */
    const val SIGMA0: Double = 25.0 / 3

    /** Base match-day randomness (beta): how much one game's form varies from true skill. See `docs/model.md`. */
    const val BETA: Double = 25.0 / 6

    /** Uncertainty added before every game (tau), so ratings can follow a player who improves or declines. See `docs/model.md`. */
    const val TAU: Double = 25.0 / 300

    /** Floor on how much one game can shrink a player's uncertainty (kappa), a numerical safety net. See `docs/model.md`. */
    const val KAPPA: Double = 1e-4

    /** Draw margin (epsilon): how close two teams' performances must be to count as level. See `docs/model.md`. */
    const val EPSILON: Double = 0.1

    /**
     * Match-day randomness for a game with [nPlayers] players in total: [BETA] scaled
     * by `sqrt(nPlayers / 2)`, so a 10-player game is noisier than a one-on-one.
     * See `docs/model.md`.
     *
     * @throws IllegalArgumentException if [nPlayers] is less than 2.
     */
    fun betaFor(nPlayers: Int): Double {
        require(nPlayers >= 2) { "A game needs at least two players, was $nPlayers" }
        return BETA * sqrt(nPlayers / 2.0)
    }
}
