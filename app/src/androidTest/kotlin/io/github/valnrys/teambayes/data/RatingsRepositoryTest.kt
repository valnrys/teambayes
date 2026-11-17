package io.github.valnrys.teambayes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.replay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatingsRepositoryTest : DatabaseTest() {

    private suspend fun playGame(
        playedAt: Long,
        teamA: List<String>,
        teamB: List<String>,
        outcome: Outcome,
    ): String {
        games.savePending(playedAt, teamA, teamB, Selection.GENERATED)
        return games.finalise(outcome, 1, 0)
    }

    @Test
    fun ratingsAreStartingRatingsUntilAGameIsFinalised() = runTest {
        players.addPlayersInOrder(listOf("Amy", "Bob"))
        val ratings = ratings.ratings.first()
        assertEquals(2, ratings.size)
        assertEquals(EngineConfig.SIGMA0, ratings.values.first().sigma, 0.0)
    }

    @Test
    fun ratingsChangeAfterFinalisingAndMatchAnEngineReplay() = runTest {
        players.addPlayersInOrder(listOf("Amy", "Bob", "Cal", "Dee"))
        val ids = players.players.first()
        val (amy, bob, cal, dee) = listOf("Amy", "Bob", "Cal", "Dee").map { n -> ids.first { it.name == n }.id }
        val before = ratings.ratings.first()

        playGame(100, listOf(amy, bob), listOf(cal, dee), Outcome.A_WINS)
        playGame(200, listOf(amy, cal), listOf(bob, dee), Outcome.DRAW)

        val after = ratings.ratings.first()
        assertNotEquals(before, after)

        val finals = games.finalGames.first()
        val expected = replay(
            ids.associate { it.id to Rating(it.startingMu, EngineConfig.SIGMA0) },
            finals.map { it.toEngineGame() },
        ).ratings
        assertEquals(expected, after)
    }

    @Test
    fun ratingChangeOfABackDatedGameIgnoresLaterGames() = runTest {
        players.addPlayersInOrder(listOf("Amy", "Bob"))
        val (amy, bob) = players.players.first().map { it.id }

        val first = playGame(100, listOf(amy), listOf(bob), Outcome.A_WINS)
        val third = playGame(300, listOf(amy), listOf(bob), Outcome.B_WINS)
        // Entered last, played in between: not the latest by played time.
        val middle = playGame(200, listOf(amy), listOf(bob), Outcome.DRAW)

        val afterFirst = ratings.ratingsBefore(middle)
        val change = ratings.ratingChange(middle)
        assertEquals(afterFirst.getValue(amy), change.getValue(amy).first)
        assertEquals(afterFirst.getValue(bob), change.getValue(bob).first)
        // "After" is the rating following the middle game, not today's rating.
        assertEquals(ratings.ratingsBefore(third).getValue(amy), change.getValue(amy).second)
        assertNotEquals(ratings.ratings.first().getValue(amy), change.getValue(amy).second)
        assertEquals(setOf(amy, bob), ratings.ratingChange(first).keys)
    }

    @Test
    fun rosterPairsPlayersWithCurrentRatings() = runTest {
        players.addPlayersInOrder(listOf("Bob", "Amy"))
        val (amy, bob) = players.players.first().map { it.id }
        playGame(100, listOf(amy), listOf(bob), Outcome.A_WINS)

        val roster = ratings.roster.first()
        assertEquals(listOf("Amy", "Bob"), roster.map { it.stored.name })
        assertEquals(ratings.ratings.first(), roster.associate { it.stored.id to it.rating })
    }
}
