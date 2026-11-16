package io.github.valnrys.teambayes.matchday

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Match day on a real phone, driven the way a person would: by visible text and by the
 * names TalkBack reads out.
 */
@RunWith(AndroidJUnit4::class)
class MatchDayScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val players = SamplePlayers.all.sortedBy { it.name }
    private val four = players.take(4).map { it.name }

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** A tappable chip, found by its TalkBack name, such as "Ottoline, Pool". */
    private fun chip(description: String) = hasContentDescription(description) and isButton

    /** A tappable chip for [name] on either side. Only the chosen card's chips are buttons. */
    private fun anyChip(name: String) = hasContentDescription("$name, ", substring = true) and isButton

    private fun showScreen() {
        // Built outside setContent: a composable would make a new one on every redraw.
        val viewModel = MatchDayViewModel(players)
        composeRule.setContent {
            TeamBayesTheme { MatchDayRoute(viewModel) }
        }
    }

    private fun tickFour() {
        four.forEach { composeRule.onNodeWithText(it).performScrollTo().performClick() }
        composeRule.onNodeWithText(four.first()).assertIsOn()
        composeRule.onNodeWithText("4 selected").assertExists()
    }

    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().performClick()
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
    fun tickGenerateMoveOneChip_showsEdited() {
        generateAndMoveOneChip()
        composeRule.onNodeWithText("Model:", substring = true).assertExists()
    }

    @Test
    fun choosingAnotherOptionWhileEdited_asksFirst() {
        generateAndMoveOneChip()

        tap("Option 2")
        composeRule.onNodeWithText("Discard your edits?").assertExists()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Discard your edits?").assertDoesNotExist()
        composeRule.onNodeWithText("EDITED").assertExists()

        tap("Option 2")
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
        waitForText("Saving games arrives in the next milestone")
        composeRule.onNodeWithText("Who's playing?").assertExists()
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
        waitForText("Saving games arrives in the next milestone")
    }
}
