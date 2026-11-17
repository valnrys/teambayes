package io.github.valnrys.teambayes.matchday

import io.github.valnrys.teambayes.data.skillOf
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.engine.Rating

// ---------------------------------------------------------------------------------------
// The result of a game, worked out from the goals. Ported from the original web version.
//
// The organiser never types a score or picks a winner in the normal case. They tally
// each player's goals, and the score is each team's sum; the winner follows from the
// score. Typing a score and keeping it in step with the goals by hand is exactly the
// kind of thing that goes wrong on a dark pitch.
//
// The one thing a goal tally can't show is an own goal, or a goal nobody's sure who
// scored. For that, "Adjust score" lets the organiser type the real score; the winner
// then follows the typed score instead. The winner is never chosen on its own.
//
// Everything here is a pure function, so it's tested on the laptop.
// ---------------------------------------------------------------------------------------

/**
 * A game's score and who won it.
 *
 * @property scoreA the Bibs' goals.
 * @property scoreB the Non-bibs' goals.
 */
data class MatchResult(val scoreA: Int, val scoreB: Int, val winner: Outcome)

/**
 * One team's goals: the sum of [goals] for the players in [team]. Goals credited to anyone
 * else (a stray id) don't count, and nor do negative numbers.
 */
fun sumGoals(goals: Map<String, Int>, team: List<String>): Int =
    team.toSet().sumOf { id -> goals[id]?.coerceAtLeast(0) ?: 0 }

/** Who won, from a plain comparison of the two scores. */
fun winnerFromScores(scoreA: Int, scoreB: Int): Outcome = when {
    scoreA > scoreB -> Outcome.A_WINS
    scoreB > scoreA -> Outcome.B_WINS
    else -> Outcome.DRAW
}

/**
 * A typed score as a number. Blank or not a number reads as 0, so the score saved is
 * always a real number, never "nothing typed yet".
 */
fun scoreFromInput(text: String): Int = text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

/**
 * The result in effect.
 *
 * Normally ([adjusted] false) it's the goal tally, and [typedA] and [typedB] are ignored.
 * With [adjusted] true, the typed score replaces the tally (own goals, unknown scorer) and
 * the winner follows it.
 */
fun deriveResult(
    goals: Map<String, Int>,
    teamA: List<String>,
    teamB: List<String>,
    adjusted: Boolean = false,
    typedA: String = "",
    typedB: String = "",
): MatchResult {
    val a = if (adjusted) scoreFromInput(typedA) else sumGoals(goals, teamA)
    val b = if (adjusted) scoreFromInput(typedB) else sumGoals(goals, teamB)
    return MatchResult(a, b, winnerFromScores(a, b))
}

/**
 * Is there a real result to save yet?
 *
 * A 0–0 with nobody having tapped a single goal isn't a result; it's an untouched form,
 * and finalising it by accident would record a draw that never happened. So it's blocked
 * until at least one goal is tallied, unless the score is adjusted: a typed 0–0 is a
 * real draw someone entered on purpose.
 */
fun canFinalise(goals: Map<String, Int>, teamA: List<String>, teamB: List<String>, adjusted: Boolean): Boolean =
    adjusted || sumGoals(goals, teamA) + sumGoals(goals, teamB) > 0

/**
 * How one game moved one player's Skill, the number people see (see [skillOf]).
 *
 * @property change Skill after the game minus Skill before it: positive is up.
 */
data class Movement(val playerId: String, val change: Int)

/**
 * The done screen's "Rating movement" list, biggest rise first, from each player's rating
 * before and after the game. Equal changes go by name, so the order is steady.
 *
 * @param changes each player's (before, after), as the ratings repository reports them.
 * @param names each player's name, by id, for breaking ties.
 */
fun movementsFrom(changes: Map<String, Pair<Rating, Rating>>, names: Map<String, String>): List<Movement> =
    changes.map { (id, beforeAfter) -> Movement(id, skillOf(beforeAfter.second) - skillOf(beforeAfter.first)) }
        .sortedWith(compareByDescending<Movement> { it.change }.thenBy { names[it.playerId]?.lowercase() ?: it.playerId })
