package io.github.valnrys.teambayes

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.data.StoredPlayer
import io.github.valnrys.teambayes.matchday.toPlayedAt
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An unfinished game comes back: start the real activity with a pending game in storage,
 * resume it from the card, add a goal, recreate the activity, and every goal is there.
 *
 * **What `recreate()` covers.** It's a configuration change, such as a rotation: the
 * activity is rebuilt but the ViewModel is kept. It checks the screen comes back on the
 * record step with the stored goals, not that a killed process does; the ViewModel tests
 * cover that, by building a fresh ViewModel from the saved state.
 *
 * The activity takes its container from the Application, which under
 * [TeamBayesTestRunner] is in memory, and `resetContainer()` gives this test an empty one:
 * the real database on the phone is never opened.
 */
@RunWith(AndroidJUnit4::class)
class ResumeMatchDayTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    // The test Application's in-memory container. Fails loudly if the runner isn't ours,
    // rather than quietly using the real database.
    private val app = ApplicationProvider.getApplicationContext<Context>() as TestTeamBayesApplication
    private val container get() = app.container

    /** The Bibs player who has scored twice. */
    private lateinit var scorer: StoredPlayer

    @Before
    fun seedAPendingGame() = runBlocking {
        app.resetContainer()
        container.playerRepository.addPlayersInOrder(listOf("Ottoline", "Bramwell", "Zephyra", "Kazimir"))
        val four = container.playerRepository.players.first().filter { it.active }.take(4)
        scorer = four[0]
        container.gameRepository.savePending(
            playedAt = LocalDate.now().toPlayedAt(ZoneId.systemDefault()),
            teamA = four.take(2).map { it.id },
            teamB = four.drop(2).map { it.id },
            selection = Selection.GENERATED,
        )
        container.gameRepository.setGoals(scorer.id, 2)
    }

    private fun waitForText(text: String) = composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertGoals(count: Int) =
        composeRule.onNode(hasContentDescription("${scorer.name}, $count goals")).assertExists()

    @Test
    fun resumeFromTheCard_thenRecreate_keepsEveryGoal() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Unfinished match day")
            composeRule.onNodeWithText("Resume →").performClick()

            waitForText("Record result")
            assertGoals(2)
            composeRule.onNode(hasContentDescription("Add a goal for ${scorer.name}")).performScrollTo().performClick()
            assertGoals(3)

            scenario.recreate()

            waitForText("Record result")
            assertGoals(3)
            // The tap's write runs in the background: wait for it to land, up to 5 seconds.
            val stored = runBlocking {
                withTimeout(5_000) {
                    container.gameRepository.pendingGame.first { game ->
                        game?.teamA?.single { it.playerId == scorer.id }?.goals == 3
                    }
                }
            }
            assertEquals(3, stored!!.teamA.single { it.playerId == scorer.id }.goals)
        }
    }
}
