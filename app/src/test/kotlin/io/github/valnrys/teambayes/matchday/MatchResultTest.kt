package io.github.valnrys.teambayes.matchday

import io.github.valnrys.teambayes.engine.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score and winner worked out from the goals: the same cases the original web
 * version tests, plus Kotlin's own edges.
 */
class MatchResultTest {

    private val teamA = listOf("p1", "p2")
    private val teamB = listOf("p3", "p4")

    // ---- Plain derivation ---------------------------------------------------------------

    @Test
    fun sumsEachTeamsGoalsAndPicksTheWinner() {
        val goals = mapOf("p1" to 2, "p2" to 1, "p3" to 1)
        assertEquals(MatchResult(3, 1, Outcome.A_WINS), deriveResult(goals, teamA, teamB))
    }

    @Test
    fun noGoalsAtAllIsAScorelessDraw() {
        assertEquals(MatchResult(0, 0, Outcome.DRAW), deriveResult(emptyMap(), teamA, teamB))
    }

    @Test
    fun equalNonZeroTalliesIsADraw() {
        assertEquals(MatchResult(2, 2, Outcome.DRAW), deriveResult(mapOf("p1" to 2, "p3" to 2), teamA, teamB))
    }

    @Test
    fun theNonBibsCanWin() {
        assertEquals(MatchResult(0, 1, Outcome.B_WINS), deriveResult(mapOf("p4" to 1), teamA, teamB))
    }

    // ---- The adjusted score wins -------------------------------------------------------

    @Test
    fun adjustedReplacesTheTallyAndItsWinnerEvenWhenTheyDisagree() {
        val goals = mapOf("p1" to 3, "p2" to 1) // the tally says 4–0
        assertEquals(MatchResult(1, 2, Outcome.B_WINS), deriveResult(goals, teamA, teamB, adjusted = true, typedA = "1", typedB = "2"))
    }

    @Test
    fun adjustedDrawOverridesATallyThatWasntADraw() {
        assertEquals(
            MatchResult(2, 2, Outcome.DRAW),
            deriveResult(mapOf("p1" to 5), teamA, teamB, adjusted = true, typedA = "2", typedB = "2"),
        )
    }

    @Test
    fun adjustedBlankOrNonNumericBoxesReadAsZero() {
        assertEquals(
            MatchResult(0, 0, Outcome.DRAW),
            deriveResult(mapOf("p1" to 4), teamA, teamB, adjusted = true, typedA = "", typedB = "abc"),
        )
    }

    @Test
    fun notAdjustedIgnoresWhateverIsTyped() {
        assertEquals(
            MatchResult(1, 0, Outcome.A_WINS),
            deriveResult(mapOf("p1" to 1), teamA, teamB, adjusted = false, typedA = "99", typedB = "99"),
        )
    }

    // ---- Players on neither team -------------------------------------------------------

    @Test
    fun aGoalForSomeoneOnNeitherTeamIsIgnored() {
        assertEquals(2, sumGoals(mapOf("p1" to 2, "ghost" to 10), teamA))
        assertEquals(MatchResult(1, 1, Outcome.DRAW), deriveResult(mapOf("p1" to 1, "p3" to 1, "unknown" to 7), teamA, teamB))
    }

    @Test
    fun worksWhenOneTeamIsEmpty() {
        assertEquals(MatchResult(2, 0, Outcome.A_WINS), deriveResult(mapOf("p1" to 2), listOf("p1"), emptyList()))
    }

    @Test
    fun negativeGoalsDontCount() {
        assertEquals(1, sumGoals(mapOf("p1" to 1, "p2" to -3), teamA))
    }

    // ---- Helpers ------------------------------------------------------------------------

    @Test
    fun winnerFromScores_isAPlainComparison() {
        assertEquals(Outcome.A_WINS, winnerFromScores(3, 1))
        assertEquals(Outcome.B_WINS, winnerFromScores(1, 3))
        assertEquals(Outcome.DRAW, winnerFromScores(2, 2))
        assertEquals(Outcome.DRAW, winnerFromScores(0, 0))
    }

    @Test
    fun scoreFromInput_readsDigitsAndTreatsAnythingElseAsZero() {
        assertEquals(7, scoreFromInput(" 7 "))
        assertEquals(0, scoreFromInput(""))
        assertEquals(0, scoreFromInput("x"))
        assertEquals(0, scoreFromInput("-2"))
    }

    // ---- canFinalise: an untouched game must not finalise as a silent 0–0 -----------------

    @Test
    fun canFinalise_blockedWhenNobodyHasTalliedAGoal() {
        assertFalse(canFinalise(emptyMap(), teamA, teamB, adjusted = false))
    }

    @Test
    fun canFinalise_blockedWhenTheOnlyGoalsBelongToNobodyOnEitherTeam() {
        assertFalse(canFinalise(mapOf("ghost" to 3), teamA, teamB, adjusted = false))
    }

    @Test
    fun canFinalise_allowedOnceAGoalIsTallied() {
        assertTrue(canFinalise(mapOf("p1" to 1), teamA, teamB, adjusted = false))
    }

    @Test
    fun canFinalise_aTyped0to0IsARealDraw() {
        assertTrue(canFinalise(emptyMap(), teamA, teamB, adjusted = true))
    }

    @Test
    fun canFinalise_adjustedStaysAllowedWithGoalsTallied() {
        assertTrue(canFinalise(mapOf("p1" to 2), teamA, teamB, adjusted = true))
    }
}
