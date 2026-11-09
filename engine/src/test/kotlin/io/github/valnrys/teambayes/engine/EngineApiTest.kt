package io.github.valnrys.teambayes.engine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Behaviour the fixtures don't cover: validation, special values and small API details. */
class EngineApiTest {

    private val player = Rating(25.0, 8.0)

    // ---- Rating ----------------------------------------------------------------

    @Test
    fun `ordinal is mu minus three sigma`() {
        assertEquals(1.0, Rating(25.0, 8.0).ordinal)
        assertEquals(-5.0, Rating(-2.0, 1.0).ordinal)
    }

    @Test
    fun `rating rejects non-finite mu and bad sigma`() {
        for (mu in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("mu=$mu") { Rating(mu, 1.0) }
        }
        for (sigma in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, -Double.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("sigma=$sigma") { Rating(25.0, sigma) }
        }
        Rating(25.0, 0.0) // zero sigma is allowed
    }

    // ---- ThurstoneMostellerFull --------------------------------------------------

    @Test
    fun `newRating uses the model's mu and sigma`() {
        assertEquals(Rating(EngineConfig.MU0, EngineConfig.SIGMA0), ThurstoneMostellerFull().newRating())
        assertEquals(Rating(10.0, 2.0), ThurstoneMostellerFull(mu = 10.0, sigma = 2.0).newRating())
    }

    @Test
    fun `model rejects bad parameters`() {
        val bad = mapOf<String, () -> ThurstoneMostellerFull>(
            "beta 0" to { ThurstoneMostellerFull(beta = 0.0) },
            "beta negative" to { ThurstoneMostellerFull(beta = -1.0) },
            "beta NaN" to { ThurstoneMostellerFull(beta = Double.NaN) },
            "beta infinite" to { ThurstoneMostellerFull(beta = Double.POSITIVE_INFINITY) },
            "kappa 0" to { ThurstoneMostellerFull(kappa = 0.0) },
            "kappa negative" to { ThurstoneMostellerFull(kappa = -1e-4) },
            "kappa NaN" to { ThurstoneMostellerFull(kappa = Double.NaN) },
            "kappa infinite" to { ThurstoneMostellerFull(kappa = Double.POSITIVE_INFINITY) },
            "epsilon negative" to { ThurstoneMostellerFull(epsilon = -0.1) },
            "epsilon NaN" to { ThurstoneMostellerFull(epsilon = Double.NaN) },
            "epsilon infinite" to { ThurstoneMostellerFull(epsilon = Double.POSITIVE_INFINITY) },
            "tau negative" to { ThurstoneMostellerFull(tau = -0.1) },
            "tau NaN" to { ThurstoneMostellerFull(tau = Double.NaN) },
            "tau infinite" to { ThurstoneMostellerFull(tau = Double.POSITIVE_INFINITY) },
            "mu NaN" to { ThurstoneMostellerFull(mu = Double.NaN) },
            "mu infinite" to { ThurstoneMostellerFull(mu = Double.NEGATIVE_INFINITY) },
            "sigma negative" to { ThurstoneMostellerFull(sigma = -1.0) },
            "sigma NaN" to { ThurstoneMostellerFull(sigma = Double.NaN) },
            "sigma infinite" to { ThurstoneMostellerFull(sigma = Double.POSITIVE_INFINITY) },
        )
        for ((label, make) in bad) {
            assertFailsWith<IllegalArgumentException>(label) { make() }
        }
        ThurstoneMostellerFull(sigma = 0.0, epsilon = 0.0, tau = 0.0) // the boundaries are allowed
    }

    @Test
    fun `every entry point rejects an empty team on either side`() {
        val model = WengLin.forGame(4)
        val calls = mapOf<String, (List<Rating>, List<Rating>) -> Unit>(
            "model.rate" to { a, b -> model.rate(a, b, Outcome.DRAW) },
            "model.predictWin" to { a, b -> model.predictWin(a, b) },
            "model.predictDraw" to { a, b -> model.predictDraw(a, b) },
            "WengLin.rate" to { a, b -> WengLin.rate(a, b, Outcome.DRAW) },
            "WengLin.predictWin" to { a, b -> WengLin.predictWin(a, b) },
            "WengLin.predictDraw" to { a, b -> WengLin.predictDraw(a, b) },
        )
        // 1v0 and 0v1: WengLin must report the empty team, not "fewer than two players".
        for ((name, call) in calls) {
            for ((a, b) in listOf(listOf(player) to emptyList(), emptyList<Rating>() to listOf(player))) {
                val e = assertFailsWith<IllegalArgumentException>("$name ${a.size}v${b.size}") { call(a, b) }
                assertEquals("Each team needs at least one player", e.message, "$name ${a.size}v${b.size}")
            }
        }
    }

    @Test
    fun `rate refuses a team with zero variance instead of returning NaN`() {
        val noTau = ThurstoneMostellerFull(tau = 0.0)
        val certain = listOf(Rating(25.0, 0.0), Rating(30.0, 0.0))
        val e = assertFailsWith<IllegalArgumentException> {
            noTau.rate(certain, listOf(player), Outcome.A_WINS)
        }
        assertTrue("variance" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { noTau.rate(listOf(player), certain, Outcome.DRAW) }

        // With the default tau, the same players gain enough variance to be rated.
        val (newA, _) = ThurstoneMostellerFull().rate(certain, listOf(player), Outcome.A_WINS)
        assertTrue(newA.all { it.mu.isFinite() && it.sigma > 0.0 })
    }

    @Test
    fun `predictions accept zero-sigma players`() {
        // No division by a team's own variance here, so openskill accepts this too.
        val certain = listOf(Rating(25.0, 0.0))
        val (pA, pB) = WengLin.predictWin(certain, certain)
        assertEquals(0.5, pA)
        assertEquals(0.5, pB)
        assertTrue(WengLin.predictDraw(certain, certain) in 0.0..1.0)
    }

    @Test
    fun `team sums add players in descending ordinal order`() {
        // Floating-point addition depends on order. Given in this order, the mus sum to
        // (1e16 - 1e16) + 1 = 1. openskill sorts by ordinal first (1e16, 1, -1e16), and
        // 1e16 + 1 rounds away the 1, giving a different total.
        val teamA = listOf(Rating(1e16, 0.0), Rating(-1e16, 0.0), Rating(1.0, 0.0))
        val teamB = listOf(Rating(0.0, 1.0))
        val givenOrder = (1e16 + -1e16) + 1.0
        val ordinalOrder = (1e16 + 1.0) + -1e16
        assertNotEquals(givenOrder, ordinalOrder)

        val model = ThurstoneMostellerFull()
        val spread = sqrt(2 * (model.beta * model.beta) + 0.0 + 1.0)
        val expected = NormalDist.cdf((ordinalOrder - 0.0) / spread)
        assertEquals(expected, model.predictWin(teamA, teamB).teamA)
        assertNotEquals(NormalDist.cdf((givenOrder - 0.0) / spread), expected)
    }

    // ---- WengLin and EngineConfig -----------------------------------------------

    @Test
    fun `WengLin infers the player count from the teams`() {
        val teamA = listOf(Rating(20.0, 3.0), Rating(28.0, 5.0), Rating(25.0, 8.0))
        val teamB = listOf(Rating(31.0, 2.0), Rating(22.0, 6.0))
        val model = WengLin.forGame(5)
        assertEquals(model.rate(teamA, teamB, Outcome.B_WINS), WengLin.rate(teamA, teamB, Outcome.B_WINS))
        assertEquals(model.predictWin(teamA, teamB), WengLin.predictWin(teamA, teamB))
        assertEquals(model.predictDraw(teamA, teamB), WengLin.predictDraw(teamA, teamB))
        assertEquals(EngineConfig.betaFor(5), model.beta)
    }

    @Test
    fun `a game needs at least two players`() {
        assertFailsWith<IllegalArgumentException> { EngineConfig.betaFor(1) }
        assertFailsWith<IllegalArgumentException> { EngineConfig.betaFor(0) }
        assertFailsWith<IllegalArgumentException> { WengLin.forGame(1) }
        assertEquals(EngineConfig.BETA, EngineConfig.betaFor(2))
    }

    @Test
    fun `results destructure in team order`() {
        val (newA, newB) = WengLin.rate(listOf(player), listOf(player, player), Outcome.A_WINS)
        assertEquals(1, newA.size)
        assertEquals(2, newB.size)
        val (pA, pB) = WengLin.predictWin(listOf(Rating(30.0, 1.0)), listOf(Rating(20.0, 1.0)))
        assertTrue(pA > pB)
    }

    // ---- NormalDist special values (fdlibm's results) ---------------------------

    @Test
    fun `erfc and cdf handle NaN, infinities and signed zero like fdlibm`() {
        assertTrue(NormalDist.erfc(Double.NaN).isNaN())
        assertEquals(0.0, NormalDist.erfc(Double.POSITIVE_INFINITY)) // +0.0 exactly
        assertEquals(2.0, NormalDist.erfc(Double.NEGATIVE_INFINITY))
        assertEquals(1.0, NormalDist.erfc(0.0))
        assertEquals(1.0, NormalDist.erfc(-0.0))

        assertTrue(NormalDist.cdf(Double.NaN).isNaN())
        assertEquals(1.0, NormalDist.cdf(Double.POSITIVE_INFINITY))
        assertEquals(0.0, NormalDist.cdf(Double.NEGATIVE_INFINITY))
        assertEquals(0.5, NormalDist.cdf(0.0))
        assertEquals(0.5, NormalDist.cdf(-0.0))
    }

    @Test
    fun `invCdf rejects probabilities outside (0, 1)`() {
        for (p in listOf(0.0, 1.0, -0.1, 1.1, Double.NaN)) {
            assertFailsWith<IllegalArgumentException>("p=$p") { NormalDist.invCdf(p) }
        }
    }
}
