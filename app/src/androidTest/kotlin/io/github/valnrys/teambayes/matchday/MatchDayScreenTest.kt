package io.github.valnrys.teambayes.matchday

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.inMemoryContainer
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Match day on a real phone, driven the way a person would: by visible text and by the
 * names TalkBack reads out.
 *
 * Each test gets the app's real repositories on a fresh in-memory database, holding the
 * fourteen sample players, so what Confirm saves can be read back.
 */
@RunWith(AndroidJUnit4::class)
class MatchDayScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val players = SamplePlayers.all.sortedBy { it.name }
    private val four = players.take(4).map { it.name }

    private val container = inMemoryContainer().apply {
        runBlocking { playerRepository.addPlayersInOrder(SamplePlayers.all.map { it.name }) }
    }

    @After
    fun closeDatabase() = container.database.close()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** A tappable chip, found by its TalkBack name, such as "Ottoline, Pool". */
    private fun chip(description: String) = hasContentDescription(description) and isButton

    /** A tappable chip for [name] on either side. Only the chosen card's chips are buttons. */
    private fun anyChip(name: String) = hasContentDescription("$name, ", substring = true) and isButton

    private fun showScreen() {
        composeRule.setContent {
            TeamBayesTheme { MatchDayRoute(container) }
        }
        waitForText("Who's playing?")
    }

    /** The pending game in storage, or `null`. */
    private fun pendingGame() = runBlocking { container.gameRepository.pendingGame.first() }

    /**
     * Ticks four players. The roster list is lazy, so rows below the fold don't exist
     * until scrolled to: scroll the list itself to each row, then tap it.
     */
    private fun tickFour() {
        four.forEach { name ->
            composeRule.onNodeWithTag(ROSTER_LIST_TAG).performScrollToNode(hasText(name))
            // Check straight away, while the row is certainly still on screen.
            composeRule.onNodeWithText(name).performClick().assertIsOn()
        }
        composeRule.onNodeWithText("4 selected").assertExists()
    }

    /** Taps a pinned button, such as "Continue →". It's always on screen, so no scrolling. */
    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performClick()
    }

    private fun tapChip(description: String) {
        composeRule.onNode(chip(description)).performScrollTo().performClick()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun generateAndMoveOneChip() {
        showScreen()
        tickFour()
        tap("Generate teams (4) →")
        waitForText("Option 1")
        composeRule.onNodeWithText("Pick a split").assertExists()

        composeRule.onAllNodes(anyChip(four.first())).onFirst().performScrollTo().performClick()
        composeRule.onNodeWithText("EDITED").assertExists()
    }

    @Test
    fun search_showsOnlyTheMatchingPlayer() {
        showScreen()
        val wanted = players.last().name
        val other = players.first().name

        composeRule.onNode(hasSetTextAction() and hasText("Search players…")).performTextInput(wanted.take(4))

        composeRule.onNodeWithText(wanted).assertIsDisplayed()
        composeRule.onNodeWithText(other).assertDoesNotExist()
    }

    @Test
    fun tickGenerateMoveOneChip_showsEdited() {
        generateAndMoveOneChip()
        composeRule.onNodeWithText("Model:", substring = true).assertExists()
    }

    @Test
    fun choosingAnotherOptionWhileEdited_asksFirst() {
        generateAndMoveOneChip()

        composeRule.onNodeWithText("Option 2").performScrollTo().performClick()
        composeRule.onNodeWithText("Discard your edits?").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Discard your edits?").assertDoesNotExist()
        composeRule.onNodeWithText("EDITED").assertExists()

        composeRule.onNodeWithText("Option 2").performScrollTo().performClick()
        composeRule.onNodeWithText("Discard").performClick()
        composeRule.onNodeWithText("EDITED").assertDoesNotExist()
    }

    @Test
    fun manualBoard_continuesOnceThePoolIsEmpty_thenConfirms() {
        showScreen()
        tickFour()
        tap("Make teams manually")
        composeRule.onNodeWithText("Continue →").assertIsNotEnabled()

        // Two to the Bibs (one tap each), two to the Non-bibs (two taps each).
        four.take(2).forEach { tapChip("$it, Pool") }
        four.drop(2).forEach {
            tapChip("$it, Pool")
            tapChip("$it, Bibs")
        }
        composeRule.onNodeWithText("Model:", substring = true).assertExists()

        tap("Continue →")
        composeRule.onNodeWithText("Confirm teams").assertExists()
        composeRule.onNodeWithText("MANUAL").assertExists()

        tap("Confirm ✓")
        waitForText("Game saved. Recording results arrives next.")
        composeRule.onNodeWithText("Who's playing?").assertExists()

        val saved = checkNotNull(pendingGame())
        assertEquals(Selection.MANUAL, saved.selection)
        assertEquals(2, saved.teamA.size)
        assertEquals(2, saved.teamB.size)
    }

    @Test
    fun unevenTeams_warnOnTheButtonButStillConfirm() {
        showScreen()
        tickFour()
        tap("Make teams manually")

        // Three Bibs, one Non-bib: 3 v 1.
        four.take(3).forEach { tapChip("$it, Pool") }
        tapChip("${four.last()}, Pool")
        tapChip("${four.last()}, Bibs")

        tap("Continue →")
        tap("Teams uneven (3 v 1) — sure?")
        waitForText("Game saved. Recording results arrives next.")
    }

    @Test
    fun newPlayer_isPlacedByRankSavedAndTicked() {
        showScreen()
        composeRule.onNodeWithTag(ROSTER_LIST_TAG).performScrollToNode(hasText("+ New player"))
        composeRule.onNode(hasSetTextAction() and hasText("+ New player")).performTextInput("Aurelio")
        composeRule.onNodeWithText("Add").performClick()

        // The sheet starts the new player in the middle: 8th of 15.
        waitForText("Where do they fit?")
        val row = composeRule.onNode(hasContentDescription("8. Aurelio, new player"))
        val moveUp = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move up" }
        composeRule.runOnIdle { moveUp.action() }
        composeRule.onNode(hasContentDescription("7. Aurelio, new player")).assertExists()

        tap("Save ✓")
        composeRule.waitUntil(5_000) {
            runBlocking { container.playerRepository.players.first() }.any { it.name == "Aurelio" }
        }
        composeRule.onNodeWithText("Where do they fit?").assertDoesNotExist()

        // Saved, ticked and on the roster in name order (the first, from A to Z).
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag(ROSTER_LIST_TAG).performScrollToNode(hasText("Aurelio"))
        composeRule.onNode(hasText("Aurelio") and isToggleable()).assertIsOn()
        composeRule.onNodeWithText("1 selected").assertExists()
    }
}
