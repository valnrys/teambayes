package io.github.valnrys.teambayes.firstrun

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.data.PlayerRepository
import io.github.valnrys.teambayes.data.StoredPlayer
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First run on a real phone: add three players, move one up the way TalkBack does, save,
 * and check what reached the repository.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Remembers what it was asked to save. */
    private class RecordingRepository : PlayerRepository {
        var saved: List<String>? = null
        override val players: Flow<List<StoredPlayer>> = MutableStateFlow(emptyList())
        override suspend fun addPlayersInOrder(namesBestToWorst: List<String>) {
            saved = namesBestToWorst
        }
        override suspend fun addPlayer(name: String, startingMu: Double): String = error("unused")
    }

    @Test
    fun addThreeReorderWithAccessibilityActionAndSave() {
        val repository = RecordingRepository()
        var done = false
        composeRule.setContent {
            TeamBayesTheme { FirstRunRoute(repository = repository, onDone = { done = true }) }
        }

        // Step 1: Next is locked until two players are added.
        composeRule.onNodeWithText("Next →").assertIsNotEnabled()
        listOf("Ottoline", "Bramwell", "Zephyra").forEach { name ->
            composeRule.onNodeWithTag(NAME_FIELD_TAG).performTextInput(name)
            composeRule.onNodeWithText("Add").performClick()
        }
        // The footer hides while the keyboard is open (see StepLayout), so close it first,
        // as a person would, and wait for the footer to come back.
        Espresso.closeSoftKeyboard()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Next →").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Next →").assertIsEnabled().performClick()

        // Step 2: "Move up" on the third player, the way TalkBack's actions menu does it.
        val row = composeRule.onNode(hasText("3. Zephyra"))
        val moveUp = row.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move up" }
        composeRule.runOnIdle { moveUp.action() }
        composeRule.onNode(hasText("2. Zephyra")).assertExists()

        composeRule.onNodeWithText("Save players ✓").performClick()
        composeRule.waitUntil(5_000) { done }
        assertEquals(listOf("Ottoline", "Zephyra", "Bramwell"), repository.saved)
    }
}
