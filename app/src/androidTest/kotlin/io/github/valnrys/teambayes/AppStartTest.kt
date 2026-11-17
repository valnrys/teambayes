package io.github.valnrys.teambayes

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.firstrun.NAME_FIELD_TAG
import io.github.valnrys.teambayes.matchday.ROSTER_LIST_TAG
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app from the top, on an empty database: first run appears, and once the players
 * are saved, match day appears with them on the roster.
 */
@RunWith(AndroidJUnit4::class)
class AppStartTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val container = inMemoryContainer()

    @After
    fun closeDatabase() = container.database.close()

    @Test
    fun firstRun_thenMatchDayWithTheSavedPlayers() {
        composeRule.setContent { TeamBayesTheme { TeamBayesApp(container) } }

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Add your players").fetchSemanticsNodes().isNotEmpty() }
        val names = listOf("Ottoline", "Bramwell", "Zephyra", "Kazimir")
        names.forEach { name ->
            composeRule.onNodeWithTag(NAME_FIELD_TAG).performTextInput(name)
            composeRule.onNodeWithText("Add").performClick()
        }
        // The footer hides while the keyboard is open (see StepLayout), so close it first.
        Espresso.closeSoftKeyboard()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Next →").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Next →").performClick()
        composeRule.onNodeWithText("Save players ✓").performClick()

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Who's playing?").fetchSemanticsNodes().isNotEmpty() }
        names.forEach { name ->
            composeRule.onNodeWithTag(ROSTER_LIST_TAG).performScrollToNode(hasText(name))
        }
        composeRule.onNodeWithText("Generate teams (0) →").assertExists()
    }
}
