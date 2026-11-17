package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.engine.Rating
import kotlin.math.roundToInt

/**
 * The skill number shown to people: `round((mu - 2 * sigma) * 40)`.
 *
 * It is a cautious score: subtracting two sigmas means a player we know little about
 * doesn't rank high on one lucky game. The 40 turns the engine's small numbers into
 * something that reads like a skill level (a new player is about 330). It matches the
 * original web version.
 */
fun skillOf(rating: Rating): Int = ((rating.mu - 2 * rating.sigma) * 40).roundToInt()
