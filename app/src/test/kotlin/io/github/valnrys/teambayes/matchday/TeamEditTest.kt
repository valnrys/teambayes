package io.github.valnrys.teambayes.matchday

import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure hand-editing rules in TeamEdit.kt, which follow the original web version of this tool. */
class TeamEditTest {

    // ---- Editing a proposal: A ↔ B -------------------------------------------------------

    @Test
    fun fromSplit_startsEditedWithAnEmptyPool() {
        val copy = fromSplit(listOf("a", "b"), listOf("c", "d"))
        assertEquals(WorkingCopy(Selection.EDITED, listOf("a", "b"), listOf("c", "d"), emptyList()), copy)
    }

    @Test
    fun move_onAProposal_swapsSidesAndJoinsTheEnd() {
        val start = fromSplit(listOf("a", "b"), listOf("c", "d"))

        val once = start.move("a")
        assertEquals(listOf("b"), once.teamA)
        assertEquals(listOf("c", "d", "a"), once.teamB)

        val back = once.move("a")
        assertEquals(listOf("b", "a"), back.teamA)
        assertEquals(listOf("c", "d"), back.teamB)
        assertTrue(back.pool.isEmpty())
    }

    @Test
    fun move_neverChangesTheOriginal() {
        val start = fromSplit(listOf("a", "b"), listOf("c", "d"))
        start.move("a")
        assertEquals(listOf("a", "b"), start.teamA)
    }

    @Test
    fun move_anUnknownPlayerGivesBackTheSameCopy() {
        val start = fromSplit(listOf("a"), listOf("b"))
        assertSame(start, start.move("zz"))
    }

    // ---- The manual board: Pool → Bibs → Non-bibs → Pool --------------------------------

    @Test
    fun move_onTheManualBoard_cyclesPoolBibsNonBibsPool() {
        val start = fromRoster(listOf("a", "b"))
        assertEquals(Selection.MANUAL, start.mode)

        val toBibs = start.move("a")
        assertEquals(listOf("a"), toBibs.teamA)
        assertEquals(listOf("b"), toBibs.pool)

        val toNonBibs = toBibs.move("a")
        assertEquals(emptyList<String>(), toNonBibs.teamA)
        assertEquals(listOf("a"), toNonBibs.teamB)

        val toPool = toNonBibs.move("a")
        assertEquals(emptyList<String>(), toPool.teamB)
        assertEquals(listOf("b", "a"), toPool.pool)
    }

    @Test
    fun nextPlace_saysWhereATapSendsSomeone() {
        val edited = fromSplit(listOf("a"), listOf("b"))
        assertEquals(Place.NON_BIBS, edited.nextPlace("a"))
        assertEquals(Place.BIBS, edited.nextPlace("b"))

        val board = fromRoster(listOf("p", "q", "r")).move("q").move("r").move("r")
        assertEquals(Place.BIBS, board.nextPlace("p"))
        assertEquals(Place.NON_BIBS, board.nextPlace("q"))
        assertEquals(Place.POOL, board.nextPlace("r"))
        assertNull(board.nextPlace("zz"))
    }

    @Test
    fun isComplete_needsAnEmptyPoolAndSomeoneOnEachSide() {
        val board = fromRoster(listOf("a", "b"))
        assertFalse(board.isComplete())
        assertFalse(board.move("a").isComplete()) // b is still in the pool
        assertFalse(board.move("a").move("b").isComplete()) // both on Bibs
        assertTrue(board.move("a").move("b").move("b").isComplete())
    }

    // ---- Advisory size warning -----------------------------------------------------------

    @Test
    fun sizeWarning_onlyWhenTheTeamsDifferByMoreThanOne() {
        assertNull(sizeWarning(5, 5))
        assertNull(sizeWarning(5, 4))
        assertNull(sizeWarning(4, 5))
        assertEquals(SizeGap(6, 4), sizeWarning(6, 4))
        assertEquals(SizeGap(6, 4), sizeWarning(4, 6))
    }

    // ---- Locks ---------------------------------------------------------------------------

    @Test
    fun lockAfterTap_unlockedThenLockedHereThenUnlocked() {
        val locked = lockAfterTap(emptyMap(), "a", Side.A)
        assertEquals(mapOf("a" to Side.A), locked)
        assertEquals(emptyMap<String, Side>(), lockAfterTap(locked, "a", Side.A))
    }

    @Test
    fun lockAfterTap_fromAnotherCardMovesTheLockToThisSide() {
        assertEquals(mapOf("a" to Side.B), lockAfterTap(mapOf("a" to Side.A), "a", Side.B))
    }

    // ---- The meter -----------------------------------------------------------------------

    @Test
    fun meterFor_isNullUntilBothSidesHaveSomeone() {
        val ratings = mapOf("a" to Rating(30.0, 2.0), "b" to Rating(20.0, 2.0))
        assertNull(meterFor(ratings, listOf("a", "b"), emptyList()))
        assertNull(meterFor(ratings, emptyList(), listOf("a")))
    }

    @Test
    fun meterFor_levelTeamsScoreFullQualityAndAFairCoinFlip() {
        val r = Rating(EngineConfig.MU0, EngineConfig.SIGMA0)
        val meter = meterFor(mapOf("a" to r, "b" to r), listOf("a"), listOf("b"))!!
        assertEquals(1.0, meter.quality, 1e-12)
        assertEquals(0.5, meter.winProbabilityA, 1e-12)
    }

    @Test
    fun meterFor_favoursTheStrongerSide() {
        val ratings = mapOf("a" to Rating(35.0, 2.0), "b" to Rating(15.0, 2.0))
        val meter = meterFor(ratings, listOf("a"), listOf("b"))!!
        assertTrue(meter.winProbabilityA > 0.5)
        assertTrue(meter.quality < 1.0)
    }
}
