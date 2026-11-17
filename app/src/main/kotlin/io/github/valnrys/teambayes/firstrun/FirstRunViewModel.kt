package io.github.valnrys.teambayes.firstrun

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.DuplicateNameException
import io.github.valnrys.teambayes.data.PlayerRepository
import io.github.valnrys.teambayes.data.normaliseName
import io.github.valnrys.teambayes.matchday.MAX_NAME_LENGTH
import io.github.valnrys.teambayes.matchday.SamplePlayers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The fewest players first run accepts: a game needs two sides, and one player a side is enough to start. */
const val MIN_PLAYERS_TO_CONTINUE = 2

/** The fewest players match day needs before it can generate teams. */
const val MIN_PLAYERS_FOR_MATCH_DAY = 4

/** The two screens of first run. */
enum class FirstRunStep {
    /** "Add your players": type names. */
    ADD,

    /** "Put them in rough order": drag the best player to the top. */
    RANK,
}

/**
 * Everything the first-run screens draw, as one immutable value.
 *
 * @property step which screen is showing.
 * @property names the players added so far. On the [FirstRunStep.RANK] step this is the
 *   ranking, best player first.
 * @property draft what is typed in the name field.
 * @property duplicateName the name that was refused as already added, or `null`.
 * @property saving `true` while the players are being written to the phone.
 * @property saved `true` once the players are stored. [FirstRunRoute] reacts to this by
 *   calling its `onDone`. It lives in state (and the saved-state bundle), not in a callback,
 *   so a save that finishes after the activity was recreated is still noticed.
 * @property saveProblem why the last save failed, as a string resource, or `null`.
 */
data class FirstRunUiState(
    val step: FirstRunStep = FirstRunStep.ADD,
    val names: List<String> = emptyList(),
    val draft: String = "",
    val duplicateName: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    @StringRes val saveProblem: Int? = null,
) {
    /**
     * `true` from the tap on Save until the screen is left: while saving, and after the save
     * succeeds (when [saved] is set but the route hasn't closed first run yet). The list and
     * buttons stay locked throughout, so nothing flashes back to enabled during the exit.
     */
    val busy: Boolean get() = saving || saved

    /** `true` once there are enough players to move on to ranking. */
    val canContinue: Boolean get() = names.size >= MIN_PLAYERS_TO_CONTINUE

    /** `true` while there are too few players for match day to generate teams. */
    val belowMatchDayMinimum: Boolean get() = names.size < MIN_PLAYERS_FOR_MATCH_DAY
}

/**
 * Everything first-run screens can ask for. [FirstRunViewModel] does the real work;
 * previews pass a do-nothing version.
 */
interface FirstRunActions {
    fun setDraft(text: String)
    fun addPlayer()
    fun removePlayer(name: String)
    fun loadSamplePlayers()
    fun next()
    fun back()
    fun move(fromIndex: Int, toIndex: Int)
    fun save()
}

/**
 * The brains of first run: collects player names, lets the organiser rank them, then
 * stores them through [repository].
 *
 * **Rotation.** A ViewModel outlives screen rotation, so the state survives it for free.
 *
 * **Process death.** Android may kill a backgrounded app and later restart it. A
 * [SavedStateHandle] is the small bundle Android keeps across that, so the step and the
 * names typed so far are written to it on every change and read back here when the
 * ViewModel is created.
 *
 * @param repository where the players are saved.
 * @param savedStateHandle the bundle that outlives process death. The default is an empty
 *   one, handy in tests.
 */
class FirstRunViewModel(
    private val repository: PlayerRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel(), FirstRunActions {

    private val _state = MutableStateFlow(
        FirstRunUiState(
            step = savedStateHandle.get<String>(KEY_STEP)?.let(FirstRunStep::valueOf) ?: FirstRunStep.ADD,
            names = savedStateHandle.get<ArrayList<String>>(KEY_NAMES).orEmpty(),
            draft = savedStateHandle.get<String>(KEY_DRAFT).orEmpty(),
            saved = savedStateHandle.get<Boolean>(KEY_SAVED) ?: false,
        ),
    )

    /** What the screens draw. */
    val state: StateFlow<FirstRunUiState> = _state.asStateFlow()

    /** Sets the new state and mirrors the parts worth keeping into the saved-state bundle. */
    private fun change(transform: (FirstRunUiState) -> FirstRunUiState) {
        val new = transform(_state.value)
        _state.value = new
        savedStateHandle[KEY_STEP] = new.step.name
        savedStateHandle[KEY_NAMES] = ArrayList(new.names) // A Bundle can hold an ArrayList, not any List.
        savedStateHandle[KEY_DRAFT] = new.draft
        savedStateHandle[KEY_SAVED] = new.saved
    }

    /** Updates the name field, cutting the text at [MAX_NAME_LENGTH] characters and clearing any duplicate warning. */
    override fun setDraft(text: String) =
        change { it.copy(draft = text.take(MAX_NAME_LENGTH), duplicateName = null) }

    /**
     * Adds the typed name, without spaces at the ends. Blank names are ignored. A name that
     * is already there, ignoring upper and lower case, is refused with a message, so two
     * "Sam"s can't be confused on the pitch.
     */
    override fun addPlayer() {
        val current = _state.value
        val name = normaliseName(current.draft)
        if (name.isEmpty()) return
        if (current.names.any { it.equals(name, ignoreCase = true) }) {
            change { it.copy(duplicateName = name) }
            return
        }
        change { it.copy(names = it.names + name, draft = "", duplicateName = null) }
    }

    /** Takes [name] off the list. */
    override fun removePlayer(name: String) =
        change { it.copy(names = it.names - name, duplicateName = null) }

    /**
     * Replaces the list with fourteen made-up names, so the owner can try the app without
     * typing. The screen only offers this in debug builds; see [FirstRunRoute].
     */
    override fun loadSamplePlayers() =
        change { it.copy(names = SamplePlayers.all.map { p -> p.name }, draft = "", duplicateName = null) }

    /** Moves on to ranking, if there are enough players. */
    override fun next() {
        if (_state.value.canContinue) change { it.copy(step = FirstRunStep.RANK, saveProblem = null) }
    }

    /** Goes back to adding players. The ranking so far is kept. */
    override fun back() {
        if (!_state.value.busy) change { it.copy(step = FirstRunStep.ADD, saveProblem = null) }
    }

    /**
     * Moves the player at [fromIndex] to [toIndex], shifting the ones in between. Dragging
     * and the TalkBack "Move up" and "Move down" actions all come through here, so every
     * way of reordering behaves the same. Out-of-range moves are ignored.
     */
    override fun move(fromIndex: Int, toIndex: Int) {
        val names = _state.value.names
        if (_state.value.busy) return
        if (fromIndex !in names.indices || toIndex !in names.indices || fromIndex == toIndex) return
        change { it.copy(names = names.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }) }
    }

    /**
     * Saves the players, best first, through the repository, and sets
     * [FirstRunUiState.saved] when done. If saving fails, the problem is shown instead.
     * A second tap while a save is running does nothing.
     *
     * If the repository says a name already exists, but *every* name here is already
     * stored, an earlier save must have gone through (say, the app was restarted before
     * it could say so), so that counts as success. If only some exist, the user is taken
     * back to step 1 to fix the name.
     */
    override fun save() {
        val current = _state.value
        if (current.saving || current.saved || !current.canContinue) return
        change { it.copy(saving = true, saveProblem = null) }
        viewModelScope.launch {
            try {
                repository.addPlayersInOrder(current.names)
                change { it.copy(saving = false, saved = true) }
            } catch (e: DuplicateNameException) {
                val stored = repository.players.first().map { normaliseName(it.name).lowercase() }.toSet()
                if (current.names.all { normaliseName(it).lowercase() in stored }) {
                    change { it.copy(saving = false, saved = true) }
                } else {
                    change { it.copy(saving = false, step = FirstRunStep.ADD, duplicateName = e.name, saveProblem = null) }
                }
            } catch (e: CancellationException) {
                throw e // The screen went away; not a failure to report.
            } catch (e: Exception) {
                change { it.copy(saving = false, saveProblem = R.string.first_run_save_failed) }
            }
        }
    }

    private companion object {
        const val KEY_STEP = "step"
        const val KEY_NAMES = "names"
        const val KEY_DRAFT = "draft"
        const val KEY_SAVED = "saved"
    }
}
