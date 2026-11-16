package io.github.valnrys.teambayes.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [WengLin.quality]: the draw chance as a share of the best this roster could manage. */
class QualityTest {

    private fun r(mu: Double, sigma: Double = EngineConfig.SIGMA0) = Rating(mu, sigma)

    @Test
    fun `an even split scores exactly 1`() {
        // Total mu is 60 on both sides, with different players on each.
        val teamA = listOf(r(35.0), r(25.0))
        val teamB = listOf(r(30.0), r(30.0))
        assertEquals(1.0, WengLin.quality(teamA, teamB))
    }

    @Test
    fun `quality is the draw chance over the same game made level`() {
        // An oracle built only from predictDraw: 2 v 3, not level, mixed sigmas. Moving
        // one team B player's mu up by the gap makes the totals equal without changing
        // any sigma or team size, which is exactly the "best possible" game.
        val teamA = listOf(r(31.0, 3.0), r(27.0, 6.5))
        val teamB = listOf(r(22.0, 2.0), r(18.5, 7.0), r(12.0, 4.0))
        val gap = teamA.sumOf { it.mu } - teamB.sumOf { it.mu }
        val levelB = listOf(r(22.0 + gap, 2.0)) + teamB.drop(1)

        val expected = WengLin.predictDraw(teamA, teamB) / WengLin.predictDraw(teamA, levelB)
        assertTrue(expected < 0.99, "the split should be clearly uneven, was $expected")
        assertEquals(expected, WengLin.quality(teamA, teamB), 1e-12)
    }

    @Test
    fun `an even split of uneven sizes still scores 1`() {
        val teamA = listOf(r(30.0), r(30.0))
        val teamB = listOf(r(20.0), r(20.0), r(20.0))
        assertEquals(1.0, WengLin.quality(teamA, teamB), 1e-12)
    }

    @Test
    fun `a worse split scores less, and a much worse one less again`() {
        val even = WengLin.quality(listOf(r(35.0), r(15.0)), listOf(r(25.0), r(25.0)))
        val worse = WengLin.quality(listOf(r(35.0), r(25.0)), listOf(r(25.0), r(15.0)))
        val worst = WengLin.quality(listOf(r(35.0), r(25.0)), listOf(r(15.0), r(5.0)))

        assertEquals(1.0, even)
        assertTrue(worse < even, "worse=$worse")
        assertTrue(worst < worse, "worst=$worst, worse=$worse")
        assertTrue(worst > 0.0, "worst=$worst")
    }

    @Test
    fun `swapping the teams gives the same quality`() {
        val teamA = listOf(r(32.0, 3.0), r(21.0, 6.0))
        val teamB = listOf(r(27.0, 8.0), r(18.0, 2.0))
        assertEquals(WengLin.quality(teamA, teamB), WengLin.quality(teamB, teamA), 1e-12)
    }

    @Test
    fun `for one roster it ranks splits exactly as predictDraw does`() {
        val roster = listOf(r(31.0, 2.0), r(28.0, 5.0), r(24.0, 8.0), r(22.0, 1.0), r(19.0, 4.0), r(15.0, 7.0))
        // Every way to put three players on team A.
        val splits = roster.indices.toList().let { idx ->
            idx.flatMap { i -> idx.flatMap { j -> idx.map { k -> setOf(i, j, k) } } }
                .filter { it.size == 3 }
                .distinct()
                .map { a -> roster.filterIndexed { i, _ -> i in a } to roster.filterIndexed { i, _ -> i !in a } }
        }

        val byDraw = splits.sortedByDescending { (a, b) -> WengLin.predictDraw(a, b) }
        val byQuality = splits.sortedByDescending { (a, b) -> WengLin.quality(a, b) }
        assertEquals(byDraw, byQuality)
    }

    @Test
    fun `quality needs a player on each side`() {
        assertFailsWith<IllegalArgumentException> { WengLin.quality(emptyList(), listOf(r(25.0))) }
        assertFailsWith<IllegalArgumentException> { WengLin.quality(listOf(r(25.0)), emptyList()) }
    }
}
