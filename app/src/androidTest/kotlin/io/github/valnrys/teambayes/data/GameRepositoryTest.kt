package io.github.valnrys.teambayes.data

import androidx.room3.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.engine.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameRepositoryTest : DatabaseTest() {

    /** Adds four players and returns their ids, in the order given. */
    private suspend fun fourPlayers(): List<String> =
        listOf("Amy", "Bob", "Cal", "Dee").map { players.addPlayer(it, 25.0) }

    @Test
    fun savePendingStoresTeamsWithZeroGoals() = runTest {
        val (a, b, c, d) = fourPlayers()
        val id = games.savePending(5_000, listOf(a, b), listOf(c, d), Selection.EDITED)

        val pending = games.pendingGame.first()
        assertNotNull(pending)
        assertEquals(id, pending!!.id)
        assertEquals(5_000, pending.playedAt)
        assertEquals(Selection.EDITED, pending.selection)
        assertEquals(setOf(a, b), pending.teamA.map { it.playerId }.toSet())
        assertEquals(setOf(c, d), pending.teamB.map { it.playerId }.toSet())
        assertTrue((pending.teamA + pending.teamB).all { it.goals == 0 })
        assertNull(pending.scoreOverride)
        assertTrue(games.finalGames.first().isEmpty())
    }

    @Test
    fun onlyOnePendingGameAtATime() = runTest {
        val (a, b, c, d) = fourPlayers()
        games.savePending(1, listOf(a), listOf(b), Selection.MANUAL)

        assertThrows(PendingGameExistsException::class.java) {
            kotlinx.coroutines.runBlocking { games.savePending(2, listOf(c), listOf(d), Selection.MANUAL) }
        }
        assertEquals(1L, games.pendingGame.first()!!.playedAt)
    }

    @Test
    fun theSchemaAlsoRefusesASecondPendingGame() = runTest {
        val (a, b) = fourPlayers()
        games.savePending(1, listOf(a), listOf(b), Selection.MANUAL)
        val second = io.github.valnrys.teambayes.data.db.GameEntity(
            id = "sneaky", playedAt = 2, status = "pending", pendingSlot = 1, winner = null,
            scoreA = null, scoreB = null, selection = "manual", createdAt = 2,
        )
        // Skips the repository's check on purpose: the unique index must stop it.
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { db.gameDao().insertGame(second) }
        }
    }

    @Test
    fun invalidTeamsAreRejected() = runTest {
        val (a, b) = fourPlayers()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { games.savePending(1, emptyList(), listOf(b), Selection.MANUAL) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { games.savePending(1, listOf(a), listOf(a), Selection.MANUAL) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { games.savePending(1, listOf(a), listOf("ghost"), Selection.MANUAL) }
        }
        assertNull(games.pendingGame.first())
    }

    @Test
    fun goalsAndScoreOverrideAreStoredOnThePendingGame() = runTest {
        val (a, b) = fourPlayers()
        games.savePending(1, listOf(a), listOf(b), Selection.GENERATED)

        games.setGoals(a, 3)
        games.setScoreOverride(Score(4, 1))

        val pending = games.pendingGame.first()!!
        assertEquals(3, pending.teamA.single().goals)
        assertEquals(Score(4, 1), pending.scoreOverride)

        games.setScoreOverride(null)
        assertNull(games.pendingGame.first()!!.scoreOverride)
    }

    @Test
    fun badGoalWritesAreRejected() = runTest {
        val (a, b, c) = fourPlayers()
        assertThrows(NoPendingGameException::class.java) {
            kotlinx.coroutines.runBlocking { games.setGoals(a, 1) }
        }
        games.savePending(1, listOf(a), listOf(b), Selection.GENERATED)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { games.setGoals(a, -1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { games.setGoals(c, 1) } // not in this game
        }
    }

    @Test
    fun finaliseTurnsPendingIntoAFinalGameAndFreesTheSlot() = runTest {
        val (a, b, c, d) = fourPlayers()
        val id = games.savePending(1, listOf(a), listOf(b), Selection.GENERATED)
        games.setGoals(a, 2)

        assertEquals(id, games.finalise(Outcome.A_WINS, 2, 0))

        assertNull(games.pendingGame.first())
        val final = games.finalGames.first().single()
        assertEquals(id, final.id)
        assertEquals(Outcome.A_WINS, final.outcome)
        assertEquals(2, final.scoreA)
        assertEquals(2, final.teamA.single().goals)

        // A new pending game is now allowed.
        games.savePending(2, listOf(c), listOf(d), Selection.MANUAL)
        assertNotNull(games.pendingGame.first())
        assertEquals(1, games.finalGames.first().size)
    }

    @Test
    fun finaliseWithoutAPendingGameFails() = runTest {
        assertThrows(NoPendingGameException::class.java) {
            kotlinx.coroutines.runBlocking { games.finalise(Outcome.DRAW, 0, 0) }
        }
    }

    @Test
    fun discardRemovesThePendingGameAndItsAppearances() = runTest {
        val (a, b) = fourPlayers()
        games.savePending(1, listOf(a), listOf(b), Selection.GENERATED)
        games.setGoals(a, 1)

        games.discardPending()

        assertNull(games.pendingGame.first())
        val leftover = db.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM appearances") { statement ->
                statement.step()
                statement.getLong(0)
            }
        }
        assertEquals(0L, leftover) // the cascade took them
        games.discardPending() // harmless when nothing is pending
    }

    @Test
    fun discardLeavesFinalGamesAlone() = runTest {
        val (a, b, c, d) = fourPlayers()
        games.savePending(1, listOf(a), listOf(b), Selection.GENERATED)
        games.finalise(Outcome.DRAW, 0, 0)
        games.savePending(2, listOf(c), listOf(d), Selection.GENERATED)

        games.discardPending()

        val final = games.finalGames.first().single()
        assertEquals(listOf(a), final.teamA.map { it.playerId })
    }

    @Test
    fun deletePendingGameCannotEraseAFinalGame() = runTest {
        val (a, b) = fourPlayers()
        val id = games.savePending(1, listOf(a), listOf(b), Selection.GENERATED)
        games.finalise(Outcome.DRAW, 0, 0)

        db.gameDao().deletePendingGame(id)

        assertEquals(1, games.finalGames.first().size)
    }
}
