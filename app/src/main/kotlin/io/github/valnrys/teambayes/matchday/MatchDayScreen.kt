package io.github.valnrys.teambayes.matchday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valnrys.teambayes.AppContainer
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.Team
import io.github.valnrys.teambayes.engine.Side
import io.github.valnrys.teambayes.ui.theme.teamBayesColors
import java.time.LocalDate

/**
 * Everything a match-day screen can ask for. [MatchDayViewModel] does the real work;
 * previews pass [NoActions].
 *
 * Passing one interface, rather than a dozen separate lambdas, keeps every step's
 * signature short. Each function is documented on the ViewModel.
 */
interface MatchDayActions {
    fun toggleSelected(playerId: String)
    fun setSearch(text: String)
    fun setNewPlayerName(text: String)
    fun addPlayer()
    fun moveNewPlayer(position: Int)
    fun saveNewPlayer()
    fun cancelNewPlayer()
    fun generate()
    fun makeTeamsManually()
    fun selectSplit(index: Int)
    fun regenerate()
    fun confirmDiscard()
    fun dismissDiscard()
    fun movePlayer(playerId: String)
    fun toggleLock(playerId: String, side: Side)
    fun continueToConfirm()
    fun setPlayDate(date: LocalDate)
    fun confirm()
    fun resumeUnfinished()
    fun discardUnfinished()
    fun keepUnfinished()
    fun confirmDiscardUnfinished()
    fun changeGoals(playerId: String, change: Int)
    fun toggleAdjustScore()
    fun setTypedScore(team: Team, text: String)
    fun finalise()
    fun startAnotherMatchDay()
    fun noticeShown()
    fun back()
}

/** Does nothing: for previews, which only draw. */
object NoActions : MatchDayActions {
    override fun toggleSelected(playerId: String) = Unit
    override fun setSearch(text: String) = Unit
    override fun setNewPlayerName(text: String) = Unit
    override fun addPlayer() = Unit
    override fun moveNewPlayer(position: Int) = Unit
    override fun saveNewPlayer() = Unit
    override fun cancelNewPlayer() = Unit
    override fun generate() = Unit
    override fun makeTeamsManually() = Unit
    override fun selectSplit(index: Int) = Unit
    override fun regenerate() = Unit
    override fun confirmDiscard() = Unit
    override fun dismissDiscard() = Unit
    override fun movePlayer(playerId: String) = Unit
    override fun toggleLock(playerId: String, side: Side) = Unit
    override fun continueToConfirm() = Unit
    override fun setPlayDate(date: LocalDate) = Unit
    override fun confirm() = Unit
    override fun resumeUnfinished() = Unit
    override fun discardUnfinished() = Unit
    override fun keepUnfinished() = Unit
    override fun confirmDiscardUnfinished() = Unit
    override fun changeGoals(playerId: String, change: Int) = Unit
    override fun toggleAdjustScore() = Unit
    override fun setTypedScore(team: Team, text: String) = Unit
    override fun finalise() = Unit
    override fun startAnotherMatchDay() = Unit
    override fun noticeShown() = Unit
    override fun back() = Unit
}

/**
 * Match day with its [MatchDayViewModel] wired up.
 *
 * This is the only function that knows about the ViewModel. Everything it draws is in
 * [MatchDayScreen], which only takes plain values, so it's easy to preview and test.
 *
 * @param container where the ViewModel gets its repositories, through
 *   [MatchDayViewModel.factory]. `viewModel()` builds it only the first time; after a
 *   rotation it hands back the same one.
 */
@Composable
fun MatchDayRoute(
    container: AppContainer,
    viewModel: MatchDayViewModel = viewModel(factory = MatchDayViewModel.factory(container)),
) {
    // collectAsStateWithLifecycle stops listening while the app is in the background.
    val state by viewModel.state.collectAsStateWithLifecycle()
    MatchDayScreen(state = state, actions = viewModel)
}

/**
 * Match day: roster → pick a split (or the manual board) → confirm → record → done.
 *
 * Stateless: it draws [state] and reports taps through [actions]. One step shows at a
 * time, chosen by [MatchDayUiState.step]; the "Where do they fit?" sheet, the "Discard
 * your edits?" dialog and the snackbar sit on top of whichever it is. Until the players
 * have loaded from storage the screen is blank: a flash of an empty roster would be worse.
 */
@Composable
fun MatchDayScreen(state: MatchDayUiState, actions: MatchDayActions, modifier: Modifier = Modifier) {
    // The phone's back gesture goes one step back, like "← Back". On the roster it's off,
    // so back leaves the app as usual.
    BackHandler(enabled = state.step != Step.ROSTER, onBack = actions::back)

    val snackbarHostState = remember { SnackbarHostState() }
    val notice = state.notice?.let { stringResource(it) }
    // Runs whenever a new notice arrives: show it, then tell the ViewModel it's been shown.
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            actions.noticeShown()
        }
    }

    // The app draws edge to edge. safeDrawing covers the status and navigation bars, the
    // camera cutout and the keyboard, so content and text fields stay clear of all four.
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding: PaddingValues ->
        if (state.loading) return@Scaffold
        when (state.step) {
            Step.ROSTER -> RosterStep(state, actions, innerPadding)
            Step.GENERATE -> PickSplitStep(state, actions, innerPadding)
            Step.MANUAL -> ManualStep(state, actions, innerPadding)
            Step.CONFIRM -> ConfirmStep(state, actions, innerPadding)
            Step.RECORD -> RecordStep(state, actions, innerPadding)
            Step.DONE -> DoneStep(state, actions, innerPadding)
        }
    }

    state.placing?.let { PlacementSheet(state, it, actions) }

    if (state.discardPrompt != null) {
        DiscardDialog(
            message = stringResource(R.string.discard_edits),
            onDiscard = actions::confirmDiscard,
            onCancel = actions::dismissDiscard,
        )
    }
    if (state.discardGamePrompt) {
        DiscardDialog(
            message = stringResource(R.string.discard_unfinished_question),
            onDiscard = actions::confirmDiscardUnfinished,
            onCancel = actions::keepUnfinished,
        )
    }
}

/**
 * A question such as "Discard your edits?" with Cancel and a red Discard, as in the
 * original web version.
 *
 * A standard Material dialog, so TalkBack announces it and moves focus into it, and
 * back or tapping outside counts as Cancel.
 */
@Composable
private fun DiscardDialog(message: String, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.discard), color = MaterialTheme.teamBayesColors.red)
            }
        },
    )
}
