package io.github.valnrys.teambayes.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.valnrys.teambayes.data.PlayerRepository

/**
 * First run: add the players, rank them roughly, save. Two steps in one screen.
 *
 * This is the entry point other code calls. It doesn't decide *when* first run appears;
 * the caller shows it (for example, while no players are stored yet) and reacts to [onDone].
 *
 * @param repository where the players are saved.
 * @param onDone called after the players have been saved (and again if the screen is
 *   shown again while already saved, so it must be safe to repeat).
 */
@Composable
fun FirstRunRoute(
    repository: PlayerRepository,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The factory hands the ViewModel its repository and a SavedStateHandle, which keeps
    // the typed names if Android kills and later restarts the app.
    val viewModel: FirstRunViewModel = viewModel(
        factory = viewModelFactory {
            initializer { FirstRunViewModel(repository, createSavedStateHandle()) }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reacting to state, not to a callback captured by the ViewModel: if the activity is
    // recreated mid-save, the new composition still sees `saved` and finishes the job.
    // rememberUpdatedState keeps the latest onDone without restarting the effect.
    val latestOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(state.saved) { if (state.saved) latestOnDone() }
    FirstRunScreen(state = state, actions = viewModel, modifier = modifier)
}

/**
 * Draws whichever first-run step [state] says is showing. It holds no state of its own, so
 * previews and tests can drive it with plain values.
 */
@Composable
internal fun FirstRunScreen(
    state: FirstRunUiState,
    actions: FirstRunActions,
    modifier: Modifier = Modifier,
) {
    // The system Back gesture on the ranking step goes back a step, like the on-screen button.
    BackHandler(enabled = state.step == FirstRunStep.RANK) { actions.back() }

    // The app draws edge to edge. safeDrawing covers the system bars, camera cutout and keyboard.
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets.safeDrawing) { innerPadding: PaddingValues ->
        when (state.step) {
            FirstRunStep.ADD -> AddPlayersStep(state, actions, innerPadding)
            FirstRunStep.RANK -> RankPlayersStep(state, actions, innerPadding)
        }
    }
}

/** An [FirstRunActions] that does nothing, for previews. */
internal object NoFirstRunActions : FirstRunActions {
    override fun setDraft(text: String) = Unit
    override fun addPlayer() = Unit
    override fun removePlayer(name: String) = Unit
    override fun loadSamplePlayers() = Unit
    override fun next() = Unit
    override fun back() = Unit
    override fun move(fromIndex: Int, toIndex: Int) = Unit
    override fun save() = Unit
}
