package io.github.valnrys.teambayes.tonight

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Four players: Balance shows both teams and "Option 1 of 3" (four can only split three ways,
 * not five), and Try another moves to "Option 2 of 3". Three players: "Tick at least 4 players". */
@RunWith(AndroidJUnit4::class)
class TonightScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val players = SamplePlayers.tonight

    /** Shows the screen with nobody ticked, so each test does its own ticking. */
    private fun showScreen() {
        val viewModel = TonightViewModel(players, initiallyTicked = emptySet())
        composeRule.setContent {
            TeamBayesTheme {
                TonightRoute(viewModel)
            }
        }
    }

    private fun tick(count: Int) {
        players.take(count).forEach { composeRule.onNodeWithText(it.name).performScrollTo().performClick() }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tickFourPlayers_balance_showsTeamsAndRerolls() {
        showScreen()

        tick(4)
        composeRule.onNodeWithText(players.first().name).assertIsOn()
        composeRule.onNodeWithText("4 playing").assertExists()

        composeRule.onNodeWithText("Balance").performClick()

        waitForText("Option 1 of 3")
        composeRule.onNodeWithText("Team A").assertExists()
        composeRule.onNodeWithText("Team B").assertExists()

        composeRule.onNodeWithText("Try another").performClick()
        composeRule.onNodeWithText("Option 2 of 3").assertExists()
    }

    @Test
    fun tickThreePlayers_balance_showsProblem() {
        showScreen()

        tick(3)
        composeRule.onNodeWithText("Balance").performClick()

        waitForText("Tick at least 4 players")
    }
}
