package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.replay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingsReplayTest {

    private val players = listOf(player("a", 30.0), player("b", 25.0), player("c", 20.0), player("d", 25.0))

    private val g1 = finalGame("g1", listOf("a", "b"), listOf("c", "d"), Outcome.A_WINS, playedAt = 100)
    private val g2 = finalGame("g2", listOf("a", "c"), listOf("b", "d"), Outcome.DRAW, playedAt = 200)
    private val g3 = finalGame("g3", listOf("a", "d"), listOf("b", "c"), Outcome.B_WINS, playedAt = 300)

    @Test
    fun `no games means starting ratings`() {
        val ratings = currentRatings(players, emptyList())
        assertEquals(Rating(30.0, EngineConfig.SIGMA0), ratings.getValue("a"))
        assertEquals(4, ratings.size)
    }

    @Test
    fun `current ratings equal a direct engine replay`() {
        val direct = replay(
            players.associate { it.id to Rating(it.startingMu, EngineConfig.SIGMA0) },
            listOf(g1, g2, g3).map { it.toEngineGame() },
        ).ratings
        assertEquals(direct, currentRatings(players, listOf(g3, g1, g2))) // any input order
    }

    @Test
    fun `ratings before a game replay only the earlier games`() {
        assertEquals(currentRatings(players, emptyList()), ratingsBefore(players, listOf(g1, g2, g3), "g1"))
        assertEquals(currentRatings(players, listOf(g1)), ratingsBefore(players, listOf(g1, g2, g3), "g2"))
        assertEquals(currentRatings(players, listOf(g1, g2)), ratingsBefore(players, listOf(g1, g2, g3), "g3"))
    }

    @Test
    fun `a back-dated game is replayed first`() {
        val backdated = finalGame("g0", listOf("b"), listOf("d"), Outcome.A_WINS, playedAt = 50)
        val before = ratingsBefore(players, listOf(g1, backdated), "g1")
        assertEquals(currentRatings(players, listOf(backdated)), before)
        assertTrue(before.getValue("b").mu > 25.0)
    }

    @Test
    fun `rating change of a back-dated game ignores games played after it`() {
        // g2 is entered last but was played between g1 and g3.
        val games = listOf(g1, g3, g2)
        val change = ratingChange(players, games, "g2")

        val beforeG2 = currentRatings(players, listOf(g1))
        val afterG2 = currentRatings(players, listOf(g1, g2))
        assertEquals(setOf("a", "b", "c", "d"), change.keys)
        for ((id, pair) in change) {
            assertEquals(beforeG2.getValue(id), pair.first)
            assertEquals(afterG2.getValue(id), pair.second)
        }
        // Using today's ratings as "after" would wrongly include g3.
        assertTrue(afterG2.getValue("a") != currentRatings(players, games).getValue("a"))
    }

    @Test
    fun `rating change for a player's first game starts from their starting rating`() {
        val change = ratingChange(players, listOf(g1, g2), "g1")
        assertEquals(Rating(30.0, EngineConfig.SIGMA0), change.getValue("a").first)
    }

    @Test
    fun `rating change only covers the game's players`() {
        val small = finalGame("s", listOf("a"), listOf("b"), Outcome.A_WINS, playedAt = 10)
        assertEquals(setOf("a", "b"), ratingChange(players, listOf(small), "s").keys)
    }

    @Test
    fun `roster pairs each player with their rating in player order`() {
        val roster = rosterOf(players, listOf(g1))
        assertEquals(players, roster.map { it.stored })
        assertEquals(currentRatings(players, listOf(g1)), roster.associate { it.stored.id to it.rating })
    }

    @Test
    fun `an unknown game id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ratingsBefore(players, listOf(g1), "nope") }
        assertThrows(IllegalArgumentException::class.java) { ratingChange(players, listOf(g1), "nope") }
    }
}
