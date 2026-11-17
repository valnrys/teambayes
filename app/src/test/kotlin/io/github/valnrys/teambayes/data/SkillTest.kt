package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Rating
import org.junit.Assert.assertEquals
import org.junit.Test

class SkillTest {

    @Test
    fun `a brand-new player is 333`() {
        // (25 - 2 * 25/3) * 40 = 333.33...
        assertEquals(333, skillOf(Rating(EngineConfig.MU0, EngineConfig.SIGMA0)))
    }

    @Test
    fun `rounds half up like JavaScript Math round`() {
        assertEquals(1, skillOf(Rating(mu = 0.0125, sigma = 0.0)))   // 0.5
        assertEquals(0, skillOf(Rating(mu = 0.0124, sigma = 0.0)))   // 0.496
        assertEquals(0, skillOf(Rating(mu = -0.0125, sigma = 0.0)))  // -0.5 rounds up to 0
        assertEquals(-1, skillOf(Rating(mu = -0.0374, sigma = 0.0))) // -1.496
    }

    @Test
    fun `uncertainty lowers the number`() {
        assertEquals(1000, skillOf(Rating(mu = 25.0, sigma = 0.0)))
        assertEquals(600, skillOf(Rating(mu = 25.0, sigma = 5.0)))
    }
}
