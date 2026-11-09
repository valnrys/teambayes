package io.github.valnrys.teambayes.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PriorTest {

    private val beta = EngineConfig.BETA
    private val mu0 = EngineConfig.MU0

    @Test
    fun `endpoints are two betas either side of the average`() {
        val mus = Prior.fromRankOrder(7)
        assertEquals(7, mus.size)
        assertEquals(mu0 + 2 * beta, mus.first(), 1e-12)
        assertEquals(mu0 - 2 * beta, mus.last(), 1e-12)
    }

    @Test
    fun `values are evenly spaced and strictly falling`() {
        val mus = Prior.fromRankOrder(9)
        val gaps = mus.zipWithNext { better, worse -> better - worse }
        assertTrue(gaps.all { it > 0 })
        gaps.forEach { assertEquals(gaps.first(), it, 1e-12) }
        assertEquals(4 * beta / 8, gaps.first(), 1e-12)
    }

    @Test
    fun `one player gets the average`() {
        assertEquals(listOf(mu0), Prior.fromRankOrder(1))
    }

    @Test
    fun `two players get the two endpoints`() {
        val (best, worst) = Prior.fromRankOrder(2)
        assertEquals(mu0 + 2 * beta, best, 1e-12)
        assertEquals(mu0 - 2 * beta, worst, 1e-12)
    }

    @Test
    fun `no players gives an empty list and negative counts are rejected`() {
        assertEquals(emptyList(), Prior.fromRankOrder(0))
        assertFailsWith<IllegalArgumentException> { Prior.fromRankOrder(-1) }
    }

    @Test
    fun `insertBetween uses the midpoint when both neighbours exist`() {
        assertEquals(24.0, Prior.insertBetween(above = 30.0, below = 18.0), 1e-12)
    }

    @Test
    fun `insertBetween goes one beta below the last player`() {
        assertEquals(20.0 - beta, Prior.insertBetween(above = 20.0, below = null), 1e-12)
    }

    @Test
    fun `insertBetween goes one beta above the first player`() {
        assertEquals(30.0 + beta, Prior.insertBetween(above = null, below = 30.0), 1e-12)
    }

    @Test
    fun `insertBetween allows equal neighbours`() {
        assertEquals(22.0, Prior.insertBetween(above = 22.0, below = 22.0), 0.0)
    }

    @Test
    fun `insertBetween can go past the ends of the ranking range`() {
        val worst = mu0 - 2 * beta
        assertEquals(worst - beta, Prior.insertBetween(above = worst, below = null), 1e-12)
    }

    @Test
    fun `insertBetween rejects neighbours in the wrong order`() {
        val error = assertFailsWith<IllegalArgumentException> { Prior.insertBetween(above = 10.0, below = 20.0) }
        assertTrue("above" in error.message.orEmpty() && "below" in error.message.orEmpty())
    }

    @Test
    fun `insertBetween rejects values that are not finite`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Prior.insertBetween(above = bad, below = null) }
            assertFailsWith<IllegalArgumentException> { Prior.insertBetween(above = null, below = bad) }
            assertFailsWith<IllegalArgumentException> { Prior.insertBetween(above = bad, below = 1.0) }
        }
    }

    @Test
    fun `insertBetween with no neighbours gives the average`() {
        assertEquals(mu0, Prior.insertBetween(null, null), 0.0)
    }
}
