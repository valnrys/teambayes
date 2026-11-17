package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.data.db.AppearanceEntity
import io.github.valnrys.teambayes.data.db.GameEntity
import io.github.valnrys.teambayes.data.db.GameWithAppearances
import io.github.valnrys.teambayes.engine.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MappingTest {

    private fun row(status: String, winner: String? = null, scoreA: Int? = null, scoreB: Int? = null) =
        GameWithAppearances(
            game = GameEntity(
                id = "g1", playedAt = 1_000, status = status,
                pendingSlot = if (status == "pending") 1 else null,
                winner = winner, scoreA = scoreA, scoreB = scoreB,
                selection = "edited", createdAt = 2_000,
            ),
            appearances = listOf(
                AppearanceEntity("g1", "zed", "b", 1),
                AppearanceEntity("g1", "bob", "a", 2),
                AppearanceEntity("g1", "amy", "a", 0),
            ),
        )

    @Test
    fun `winner codes round-trip`() {
        for (outcome in Outcome.entries) assertEquals(outcome, outcomeFromCode(outcome.toCode()))
        assertEquals(listOf("a", "b", "draw"), Outcome.entries.map { it.toCode() })
    }

    @Test
    fun `selection and team codes are lower case`() {
        assertEquals("generated", Selection.GENERATED.toCode())
        assertEquals("manual", Selection.MANUAL.toCode())
        assertEquals(Selection.EDITED, selectionFromCode("edited"))
        assertEquals("a", Team.A.toCode())
    }

    @Test
    fun `unknown codes fail loudly`() {
        assertThrows(IllegalStateException::class.java) { outcomeFromCode("tie") }
        assertThrows(IllegalStateException::class.java) { selectionFromCode("random") }
    }

    @Test
    fun `pending row without a score has no override`() {
        val pending = row("pending").toPendingGame()
        assertNull(pending.scoreOverride)
        assertEquals(Selection.EDITED, pending.selection)
        assertEquals(listOf("amy", "bob"), pending.teamA.map { it.playerId })
        assertEquals(listOf(PlayerGoals("zed", 1)), pending.teamB)
    }

    @Test
    fun `pending row with a score has an override`() {
        assertEquals(Score(3, 2), row("pending", scoreA = 3, scoreB = 2).toPendingGame().scoreOverride)
    }

    @Test
    fun `final row maps to a final game`() {
        val game = row("final", winner = "draw", scoreA = 2, scoreB = 2).toFinalGame()
        assertEquals(Outcome.DRAW, game.outcome)
        assertEquals(2, game.scoreA)
        assertEquals(1_000, game.playedAt)
        assertEquals(2_000, game.createdAt)
    }

    @Test
    fun `a row is only read as the kind it is`() {
        assertThrows(IllegalStateException::class.java) { row("final", "a", 1, 0).toPendingGame() }
        assertThrows(IllegalStateException::class.java) { row("pending").toFinalGame() }
        assertThrows(IllegalStateException::class.java) { row("final", winner = null, scoreA = 1, scoreB = 0).toFinalGame() }
    }
}
