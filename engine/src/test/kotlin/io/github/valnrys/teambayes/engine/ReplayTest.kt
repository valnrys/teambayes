package io.github.valnrys.teambayes.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayTest {

    private val start = Rating(EngineConfig.MU0, EngineConfig.SIGMA0)
    private val pool = listOf("a", "b", "c", "d", "e").associateWith { start }

    private fun game(
        id: String,
        playedAt: Long = 1,
        createdAt: Long = 1,
        teamA: List<String> = listOf("a", "b"),
        teamB: List<String> = listOf("c", "d"),
        outcome: Outcome = Outcome.A_WINS,
    ) = Game(id, playedAt, createdAt, teamA, teamB, outcome)

    @Test
    fun `chains fixture is reproduced through replay`() {
        val checker = FixtureChecker("chains.json via replay()")
        val chains = loadFixture("chains.json")["chains"].list
        checker.checkCount("chains", chains.size, 5)
        for (chain in chains) {
            val name = chain["name"].asString
            val initial = chain["initial"].list.associate { it["id"].asString to it.toRating() }
            checker.checkCount("$name games", chain["games"].list.size, 10)
            val games = chain["games"].list.mapIndexed { i, g ->
                val (a, b) = g["teams"].list.map { team -> team.list.map { it.asString } }
                Game("g$i", playedAt = i * 1000L, createdAt = 0, teamA = a, teamB = b, outcome = outcomeFromRanks(g["ranks"]))
            }
            for (count in 1..games.size) {
                // Replaying the first `count` games must match the fixture after game `count`.
                val result = replay(initial, games.take(count))
                val after = chain["games"].list[count - 1]["ratings_after"].list
                checker.checkCount("$name after $count pool size", after.size, initial.size)
                for (want in after) {
                    val id = want["id"].asString
                    val got = result.ratings.getValue(id)
                    checker.check("$name after ${count} $id mu", got.mu, want["mu"].asDouble)
                    checker.check("$name after ${count} $id sigma", got.sigma, want["sigma"].asDouble)
                }
            }
        }
        checker.assertAllPassed()
    }

    @Test
    fun `input order does not matter`() {
        val games = listOf(
            game("g1", playedAt = 10, outcome = Outcome.A_WINS),
            game("g2", playedAt = 20, teamA = listOf("a", "e"), teamB = listOf("b", "c"), outcome = Outcome.DRAW),
            game("g3", playedAt = 20, createdAt = 5, outcome = Outcome.B_WINS),
            game("g4", playedAt = 20, createdAt = 5, teamA = listOf("e"), teamB = listOf("a"), outcome = Outcome.A_WINS),
            game("g5", playedAt = 5, teamA = listOf("d", "e"), teamB = listOf("a", "b"), outcome = Outcome.B_WINS),
        )
        val expected = replay(pool, games)
        repeat(20) { seed ->
            assertEquals(expected, replay(pool, games.shuffled(kotlin.random.Random(seed))))
        }
    }

    @Test
    fun `ties on playedAt are broken by createdAt then id`() {
        val first = game("z", playedAt = 1, createdAt = 1, outcome = Outcome.A_WINS)
        val second = game("a", playedAt = 1, createdAt = 2, outcome = Outcome.B_WINS)
        val history = replay(pool, listOf(second, first)).history.getValue("a")
        assertEquals(listOf("z", "a"), history.map { it.gameId })
    }

    @Test
    fun `playedAt decides before createdAt`() {
        // Entered last, but played first: it must replay first.
        val playedFirst = game("late-entry", playedAt = 1, createdAt = 99)
        val playedSecond = game("early-entry", playedAt = 2, createdAt = 1, outcome = Outcome.B_WINS)
        val history = replay(pool, listOf(playedSecond, playedFirst)).history.getValue("a")
        assertEquals(listOf("late-entry", "early-entry"), history.map { it.gameId })
    }

    @Test
    fun `a tie on playedAt and createdAt is broken by ascending id`() {
        val g1 = game("b", playedAt = 7, createdAt = 7, outcome = Outcome.A_WINS)
        val g2 = game("a", playedAt = 7, createdAt = 7, outcome = Outcome.B_WINS)
        for (input in listOf(listOf(g1, g2), listOf(g2, g1))) {
            val history = replay(pool, input).history.getValue("a")
            assertEquals(listOf("a", "b"), history.map { it.gameId })
        }
    }

    @Test
    fun `players who never play keep their starting rating and an empty history`() {
        val result = replay(pool, listOf(game("g1")))
        assertEquals(start, result.ratings.getValue("e"))
        assertEquals(emptyList(), result.history.getValue("e"))
        assertEquals(pool.keys, result.ratings.keys)
        assertEquals(pool.keys, result.history.keys)
    }

    @Test
    fun `no games leaves everything as it started`() {
        assertEquals(pool, replay(pool, emptyList()).ratings)
    }

    @Test
    fun `history holds the rating after each game the player played`() {
        val games = listOf(
            game("g1", playedAt = 100),
            game("g2", playedAt = 200, teamA = listOf("a"), teamB = listOf("e"), outcome = Outcome.DRAW),
        )
        val result = replay(pool, games)

        val a = result.history.getValue("a")
        assertEquals(listOf("g1", "g2"), a.map { it.gameId })
        assertEquals(listOf(100L, 200L), a.map { it.playedAt })
        assertEquals(result.ratings.getValue("a"), a.last().rating)

        // After game 1 alone, a's rating is what a one-game replay gives.
        assertEquals(replay(pool, games.take(1)).ratings.getValue("a"), a.first().rating)

        val e = result.history.getValue("e")
        assertEquals(listOf("g2"), e.map { it.gameId })
        assertEquals(result.ratings.getValue("e"), e.single().rating)
        assertEquals(1, result.history.getValue("c").size)
    }

    @Test
    fun `winning raises mu and playing shrinks sigma`() {
        val result = replay(pool, listOf(game("g1")))
        assertTrue(result.ratings.getValue("a").mu > start.mu)
        assertTrue(result.ratings.getValue("c").mu < start.mu)
        assertTrue(result.ratings.getValue("a").sigma < start.sigma)
    }

    @Test
    fun `a bad game anywhere stops the replay before any rating`() {
        val games = listOf(game("g1"), game("g2", teamA = listOf("a", "a")))
        assertFailsWith<IllegalArgumentException> { replay(pool, games) }
    }

    @Test
    fun `unknown player is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            replay(pool, listOf(game("g1", teamA = listOf("a", "zed"))))
        }
        assertTrue("zed" in error.message.orEmpty())
    }

    @Test
    fun `player on both teams is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            replay(pool, listOf(game("g1", teamA = listOf("a", "b"), teamB = listOf("b", "c"))))
        }
        assertTrue("g1" in error.message.orEmpty() && "b" in error.message.orEmpty())
    }

    @Test
    fun `player twice in one team is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            replay(pool, listOf(game("g1", teamA = listOf("a", "a"))))
        }
        assertTrue("g1" in error.message.orEmpty() && "a" in error.message.orEmpty())
    }

    @Test
    fun `empty team is rejected`() {
        for (broken in listOf(game("g1", teamA = emptyList()), game("g1", teamB = emptyList()))) {
            val error = assertFailsWith<IllegalArgumentException> { replay(pool, listOf(broken)) }
            assertTrue("empty" in error.message.orEmpty())
        }
    }

    @Test
    fun `duplicate game id is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            replay(pool, listOf(game("g1", playedAt = 1), game("g1", playedAt = 2)))
        }
        assertTrue("g1" in error.message.orEmpty())
    }
}
