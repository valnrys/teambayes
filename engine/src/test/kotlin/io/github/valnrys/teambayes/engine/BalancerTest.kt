package io.github.valnrys.teambayes.engine

import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A text key that is the same for a split and its mirror, and different for any
 * other split. Used to spot duplicates and mirrors.
 */
fun canonicalKey(split: Split): String {
    val a = split.teamA.sorted().joinToString(",")
    val b = split.teamB.sorted().joinToString(",")
    return if (a < b) "$a|$b" else "$b|$a"
}

class BalancerTest {

    private fun roster(n: Int, random: Random, sameSigma: Boolean = true): List<BalancerPlayer> =
        List(n) { i ->
            val sigma = if (sameSigma) EngineConfig.SIGMA0 else 1 + random.nextDouble() * 7
            BalancerPlayer("p%02d".format(i), Rating(10 + random.nextDouble() * 30, sigma))
        }

    private fun splitsOf(result: BalanceResult) = assertIs<BalanceResult.Splits>(result)

    @Test
    fun `best split has the smallest mu gap for even n`() {
        val random = Random(42)
        repeat(30) { trial ->
            val n = listOf(4, 6, 8, 10, 12, 14)[trial % 6]
            val players = roster(n, random)
            val best = splitsOf(Balancer.balance(players, k = 1)).splits.single()

            val mu = players.associate { it.id to it.rating.mu }
            val gap = abs(best.teamA.sumOf { mu.getValue(it) } - best.teamB.sumOf { mu.getValue(it) })

            val total = mu.values.sum()
            var smallest = Double.MAX_VALUE
            for (mask in 0 until (1 shl n)) {
                if (Integer.bitCount(mask) != n / 2) continue
                val sumA = players.indices.filter { mask shr it and 1 == 1 }.sumOf { players[it].rating.mu }
                smallest = minOf(smallest, abs(sumA - (total - sumA)))
            }
            assertEquals(smallest, gap, 1e-9, "trial $trial, n = $n")
        }
    }

    @Test
    fun `returned splits are distinct, not mirrors, and respect locks`() {
        val random = Random(7)
        repeat(40) { trial ->
            val n = 4 + trial % 11
            val players = roster(n, random, sameSigma = false)
            val ids = players.map { it.id }.shuffled(random)
            val locks = ids.take(random.nextInt(0, 3)).associateWith { if (random.nextBoolean()) Side.A else Side.B }

            val result = Balancer.balance(players, locks, k = 50, random = Random(1))
            if (result !is BalanceResult.Splits) return@repeat // some random locks are impossible
            val splits = result.splits
            assertEquals(splits.size, splits.map { canonicalKey(it) }.toSet().size, "trial $trial")
            for (split in splits) {
                assertEquals(n, (split.teamA + split.teamB).toSet().size)
                assertTrue(abs(split.teamA.size - split.teamB.size) <= 1)
                for ((id, side) in locks) {
                    assertTrue(id in (if (side == Side.A) split.teamA else split.teamB), "trial $trial lock $id")
                }
            }
            assertEquals(splits.sortedByDescending { it.drawProbability }, splits)
        }
    }

    @Test
    fun `odd n tries the extra player on either side`() {
        val players = roster(5, Random(3))
        val splits = splitsOf(Balancer.balance(players, k = 100)).splits
        assertEquals(10, splits.size) // C(5,2) distinct partitions
        assertTrue(splits.any { it.teamA.size == 2 })
        assertTrue(splits.any { it.teamA.size == 3 })
    }

    @Test
    fun `locks with odd n allow either team to be larger`() {
        val players = roster(7, Random(3))
        val locks = mapOf("p00" to Side.A)
        val splits = splitsOf(Balancer.balance(players, locks, k = 1000)).splits
        assertEquals(35, splits.size) // C(7,3) partitions, A always holds p00
        assertTrue(splits.any { it.teamA.size == 3 } && splits.any { it.teamA.size == 4 })
    }

    @Test
    fun `win probability matches the model`() {
        val players = roster(8, Random(5), sameSigma = false)
        val split = splitsOf(Balancer.balance(players, k = 1)).splits.single()
        val rating = players.associate { it.id to it.rating }
        val a = split.teamA.map { rating.getValue(it) }
        val b = split.teamB.map { rating.getValue(it) }
        assertEquals(WengLin.predictDraw(a, b), split.drawProbability, 0.0)
        assertEquals(WengLin.predictWin(a, b).teamA, split.winProbabilityA, 0.0)
    }

    @Test
    fun `ties keep enumeration order so results are deterministic`() {
        val players = List(6) { BalancerPlayer("p$it", Rating(25.0, 8.0)) }
        val first = splitsOf(Balancer.balance(players, k = 3)).splits
        val second = splitsOf(Balancer.balance(players, k = 3)).splits
        assertEquals(first, second)
        assertEquals(listOf("p0", "p1", "p2"), first.first().teamA)
    }

    @Test
    fun `20 players or fewer is exhaustive, 21 and 22 are sampled`() {
        assertFalse(splitsOf(Balancer.balance(roster(20, Random(1)), k = 3)).sampled)
        assertTrue(splitsOf(Balancer.balance(roster(21, Random(1)), k = 3)).sampled)
        assertTrue(splitsOf(Balancer.balance(roster(22, Random(1)), k = 3)).sampled)
    }

    @Test
    fun `the same seed gives the same sampled result`() {
        val players = roster(22, Random(9), sameSigma = false)
        val locks = mapOf("p03" to Side.B)
        val one = Balancer.balance(players, locks, random = Random(123))
        val two = Balancer.balance(players, locks, random = Random(123))
        assertEquals(one, two)
        val splits = splitsOf(one).splits
        assertEquals(5, splits.size)
        assertTrue(splits.all { "p03" in it.teamB && it.teamA.size + it.teamB.size == 22 })
    }

    @Test
    fun `results do not depend on the order players are passed in`() {
        val random = Random(11)
        for (n in listOf(5, 8, 11, 22)) {
            val players = roster(n, random, sameSigma = false)
            val locks = mapOf(players[1].id to Side.A, players[2].id to Side.B)
            for (given in listOf(emptyMap(), locks)) {
                val expected = Balancer.balance(players, given, k = 8, random = Random(5))
                repeat(5) { seed ->
                    val shuffled = players.shuffled(Random(seed))
                    assertEquals(expected, Balancer.balance(shuffled, given, k = 8, random = Random(5)), "n = $n")
                }
            }
        }
    }

    @Test
    fun `without locks the lowest id is on team A and teams list ids in order`() {
        val players = roster(9, Random(2)).reversed()
        for (split in splitsOf(Balancer.balance(players, k = 20)).splits) {
            assertEquals("p00", split.teamA.first())
            assertEquals(split.teamA.sorted(), split.teamA)
            assertEquals(split.teamB.sorted(), split.teamB)
        }
    }

    @Test
    fun `repeat calls give identical results including the default seed`() {
        val players = roster(23, Random(4), sameSigma = false)
        assertEquals(Balancer.balance(players), Balancer.balance(players))
    }

    @Test
    fun `k below 1 is rejected`() {
        for (k in listOf(0, -3)) {
            val error = assertFailsWith<IllegalArgumentException> { Balancer.balance(roster(6, Random(1)), k = k) }
            assertTrue("k" in error.message.orEmpty())
        }
    }

    @Test
    fun `k larger than the number of splits returns them all`() {
        assertEquals(3, splitsOf(Balancer.balance(roster(4, Random(1)), k = 50)).splits.size)
    }

    @Test
    fun `too few players is impossible`() {
        for (n in 0..3) {
            assertEquals(
                BalanceResult.Impossible(BalanceResult.Reason.TOO_FEW_PLAYERS),
                Balancer.balance(roster(n, Random(1))),
            )
        }
    }

    @Test
    fun `duplicate player is impossible`() {
        val players = roster(5, Random(1)) + BalancerPlayer("p00", Rating(25.0, 8.0))
        assertEquals(
            BalanceResult.Impossible(BalanceResult.Reason.DUPLICATE_PLAYER),
            Balancer.balance(players),
        )
    }

    @Test
    fun `locking an unknown player is impossible`() {
        assertEquals(
            BalanceResult.Impossible(BalanceResult.Reason.UNKNOWN_LOCKED_PLAYER),
            Balancer.balance(roster(6, Random(1)), mapOf("nobody" to Side.A)),
        )
    }

    @Test
    fun `too many locks on one side is impossible`() {
        val players = roster(6, Random(1))
        val locks = mapOf("p00" to Side.A, "p01" to Side.A, "p02" to Side.A, "p03" to Side.A)
        assertEquals(
            BalanceResult.Impossible(BalanceResult.Reason.LOCKS_UNSATISFIABLE),
            Balancer.balance(players, locks),
        )
        // And in the sampled range too.
        val big = roster(22, Random(1))
        val bigLocks = big.take(12).associate { it.id to Side.B }
        assertEquals(
            BalanceResult.Impossible(BalanceResult.Reason.LOCKS_UNSATISFIABLE),
            Balancer.balance(big, bigLocks),
        )
    }

    /** Checks everything that must hold for any returned split list. */
    private fun assertWellFormed(n: Int, locks: Map<String, Side>, splits: List<Split>, label: String) {
        assertEquals(splits.size, splits.map { canonicalKey(it) }.toSet().size, "$label: duplicates or mirrors")
        for (split in splits) {
            assertEquals(n, (split.teamA + split.teamB).toSet().size, label)
            assertTrue(abs(split.teamA.size - split.teamB.size) <= 1, label)
            for ((id, side) in locks) {
                assertTrue(id in (if (side == Side.A) split.teamA else split.teamB), "$label: lock $id")
            }
        }
        assertEquals(splits.sortedByDescending { it.drawProbability }, splits, "$label: not in descending order")
    }

    @Test
    fun `sampled results are well formed, with odd n and locks`() {
        val random = Random(77)
        for (n in listOf(21, 22, 23, 24, 25)) {
            val players = roster(n, random, sameSigma = false)
            val ids = players.map { it.id }
            for (lockCount in 0..2) {
                val locks = ids.shuffled(random).take(lockCount).mapIndexed { i, id ->
                    id to if (i % 2 == 0) Side.A else Side.B
                }.toMap()
                val result = splitsOf(Balancer.balance(players, locks, k = 200, random = Random(n)))
                assertTrue(result.sampled, "n = $n, ${locks.size} locks")
                assertEquals(200, result.splits.size)
                assertWellFormed(n, locks, result.splits, "n = $n, ${locks.size} locks")
            }
        }
    }

    @Test
    fun `many locks on a big roster leave few splits, which are all tried`() {
        // 21 players, 10 locked to A: A needs one more of 11 free, so 11 + ... = 12 splits.
        val players = roster(21, Random(1), sameSigma = false)
        val locks = players.take(10).associate { it.id to Side.A }
        val result = splitsOf(Balancer.balance(players, locks, k = 1000))
        assertFalse(result.sampled)
        assertEquals(12, result.splits.size)
        assertWellFormed(21, locks, result.splits, "21 with 10 locks")
    }

    @Test
    fun `the exhaustive limit decides, not the roster size`() {
        val players = roster(23, Random(2), sameSigma = false)
        // 5 locked to A: C(18,6) + C(18,7) = 50,388 splits, so every one is tried.
        val five = players.take(5).associate { it.id to Side.A }
        val exhaustive = splitsOf(Balancer.balance(players, five, k = 100_000))
        assertFalse(exhaustive.sampled)
        assertEquals(50_388, exhaustive.splits.size)
        assertWellFormed(23, five, exhaustive.splits, "23 with 5 locks")

        // 3 locked to A: C(20,8) + C(20,9) = 293,930 splits, so it samples.
        val three = players.take(3).associate { it.id to Side.A }
        val sampled = splitsOf(Balancer.balance(players, three, k = 100_000))
        assertTrue(sampled.sampled)
        assertWellFormed(23, three, sampled.splits, "23 with 3 locks")

        // Even n: 22 with 3 locks is C(19,8) = 75,582 (all tried); with 2 locks it is C(20,9) = 167,960 (sampled).
        val evenPlayers = roster(22, Random(2), sameSigma = false)
        assertFalse(splitsOf(Balancer.balance(evenPlayers, evenPlayers.take(3).associate { it.id to Side.A }, k = 1)).sampled)
        assertTrue(splitsOf(Balancer.balance(evenPlayers, evenPlayers.take(2).associate { it.id to Side.A }, k = 1)).sampled)
    }

    @Test
    fun `sampling picks each team size in proportion to its number of splits`() {
        // 27 players, 6 locked to A, 21 free. Team A has 13 players in C(21,7) = 116,280
        // splits and 14 players in C(21,8) = 203,490, so 36.4% of all splits have 13.
        val players = roster(27, Random(3), sameSigma = false)
        val locks = players.take(6).associate { it.id to Side.A }
        val result = splitsOf(Balancer.balance(players, locks, k = 200_000, random = Random(2024)))
        assertTrue(result.sampled)
        assertEquals(100_000, result.splits.size)
        val share = result.splits.count { it.teamA.size == 13 }.toDouble() / result.splits.size
        assertEquals(116_280.0 / (116_280 + 203_490), share, 0.01, "share of splits with 13 on team A")
    }

    @Test
    fun `the biggest roster works and one more is refused`() {
        val biggest = roster(Balancer.MAX_PLAYERS, Random(6))
        val result = splitsOf(Balancer.balance(biggest, k = 3, random = Random(1)))
        assertTrue(result.sampled)
        assertWellFormed(Balancer.MAX_PLAYERS, emptyMap(), result.splits, "64 players")
        assertEquals(
            BalanceResult.Impossible(BalanceResult.Reason.TOO_MANY_PLAYERS),
            Balancer.balance(roster(Balancer.MAX_PLAYERS + 1, Random(6))),
        )
    }

    @Test
    fun `exhaustive max really is exhaustive`() {
        // The largest case with no locks: 20 players, one fixed on A, C(19,9) = 92,378 splits.
        assertTrue(92_378 <= Balancer.EXHAUSTIVE_LIMIT)
        assertFalse(splitsOf(Balancer.balance(roster(Balancer.EXHAUSTIVE_MAX, Random(1)))).sampled)
    }

    @Test
    fun `timing smoke test for 18 and 20 players`() {
        // Thread allocation counters are a HotSpot extension; fall back to "n/a" elsewhere.
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        for (n in listOf(18, 20)) {
            val players = roster(n, Random(n), sameSigma = false)
            Balancer.balance(players) // warm up the JIT so the numbers are steady
            var result: BalanceResult? = null
            val before = bean?.getThreadAllocatedBytes(Thread.currentThread().id)
            val millis = measureTimeMillis { result = Balancer.balance(players) }
            val after = bean?.getThreadAllocatedBytes(Thread.currentThread().id)
            val megabytes = if (before != null && after != null) "${(after - before) / 1_000_000} MB" else "n/a"
            println("Balancer: $n players took $millis ms, allocated $megabytes")
            assertEquals(5, splitsOf(result!!).splits.size)
        }
    }
}
