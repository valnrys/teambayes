package io.github.valnrys.teambayes.matchday

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.BalanceResult
import io.github.valnrys.teambayes.engine.Balancer
import io.github.valnrys.teambayes.engine.BalancerPlayer
import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.Side
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many proposals to show. The original web version of this tool also showed three. */
private const val OPTIONS = 3

/** The longest name the "+ New player" field accepts, as in the original web version. */
const val MAX_NAME_LENGTH = 60

/** The fewest players the manual board accepts: one a side. */
const val MIN_PLAYERS_MANUAL = 2

/**
 * The four steps of match day, in the order the organiser meets them.
 *
 * The original web version went on to record the result and show rating changes. Those
 * steps arrive with storage in the next milestone.
 */
enum class Step {
    /** "Who's playing?": tick players, then generate or make teams by hand. */
    ROSTER,

    /** "Pick a split": three proposals, tap-to-move editing, locks and re-generate. */
    GENERATE,

    /** "Make teams manually": the Pool / Bibs / Non-bibs board. */
    MANUAL,

    /** "Confirm teams": a last look before the game is saved. */
    CONFIRM,
}

/**
 * One of the balancer's proposals, ready to draw.
 *
 * @property teamA the Bibs' ids, in name order.
 * @property teamB the Non-bibs' ids, in name order.
 * @property meter the engine's view of this split.
 */
data class SplitOption(val teamA: List<String>, val teamB: List<String>, val meter: Meter)

/** The teams chosen for the confirm step, and how they were made. */
data class ChosenTeams(val teamA: List<String>, val teamB: List<String>, val selection: Selection)

/** What to do if the organiser says "Discard" in the "Discard your edits?" dialog. */
sealed interface DiscardAction {
    /** Show a different proposal. */
    data class SelectSplit(val index: Int) : DiscardAction

    /** Run the balancer again. */
    data object Regenerate : DiscardAction
}

/**
 * Everything the match-day screens draw, as one immutable value.
 *
 * It's deliberately flat: one field per thing the organiser can see or change, in the
 * same shape as the original web version's screen state. Fields belonging to a later
 * step simply sit unused until then.
 *
 * @property players everyone who could play, in name order (A to Z).
 * @property selectedIds who is playing, in the order they were ticked.
 * @property search the text in the search field.
 * @property newPlayerName the text in the "+ New player" field.
 * @property duplicateName set when the last name added was already taken.
 * @property step which screen is showing.
 * @property generating true while the balancer is running. Taps that change the teams
 *   are ignored meanwhile, because the new proposals would overwrite them.
 * @property problem why the last action couldn't be done, as a string resource.
 * @property splits the balancer's proposals, best first.
 * @property sampled true if there were too many splits to try them all.
 * @property selectedSplit which proposal is chosen (its card has the accent border).
 * @property locks players pinned to a side for the next re-generate.
 * @property workingCopy teams being edited by hand, or `null` if nothing has been moved.
 * @property liveMeter the engine's view of [workingCopy], or `null` while a side is empty.
 * @property chosen the teams on the confirm step.
 * @property discardPrompt set while the "Discard your edits?" dialog is open.
 * @property notice a one-off message for the snackbar; cleared once shown.
 */
data class MatchDayUiState(
    val players: List<Player>,
    val selectedIds: Set<String> = emptySet(),
    val search: String = "",
    val newPlayerName: String = "",
    val duplicateName: String? = null,
    val step: Step = Step.ROSTER,
    val generating: Boolean = false,
    @param:StringRes val problem: Int? = null,
    val splits: List<SplitOption> = emptyList(),
    val sampled: Boolean = false,
    val selectedSplit: Int = 0,
    val locks: Map<String, Side> = emptyMap(),
    val workingCopy: WorkingCopy? = null,
    val liveMeter: Meter? = null,
    val chosen: ChosenTeams? = null,
    val discardPrompt: DiscardAction? = null,
    @param:StringRes val notice: Int? = null,
) {
    /** Each player's name, by id. */
    val namesById: Map<String, String> by lazy { players.associate { it.id to it.name } }

    /** Each player's rating, by id, for the meter. */
    val ratingsById: Map<String, Rating> by lazy { players.associate { it.id to it.rating } }

    /** The roster rows to show: names containing the search text, ignoring case. */
    val visiblePlayers: List<Player> by lazy {
        players.filter { it.name.contains(search.trim(), ignoreCase = true) }
    }

    /**
     * Both roster buttons unlock at [MIN_PLAYERS_MANUAL] players, as in the original web
     * version. The manual board works from two. The balancer needs four, so with two or
     * three, Generate shows the engine's own reason rather than staying greyed out with
     * no explanation.
     */
    val canMakeTeams: Boolean get() = selectedIds.size >= MIN_PLAYERS_MANUAL

    /**
     * Continue from the generate or manual step: hand edits must be complete; a proposal
     * always is. Never while generating, because the proposals are about to change, and
     * never from another step.
     */
    val canContinue: Boolean
        get() = (step == Step.GENERATE || step == Step.MANUAL) &&
            !generating &&
            (workingCopy?.isComplete() ?: splits.isNotEmpty())
}

/**
 * Runs match day: the step machine, the balancer, and every tap in between.
 *
 * The screen reads [state] and calls the public functions here; it never changes
 * anything itself. A [ViewModel] outlives screen rotations, so a half-made set of teams
 * survives turning the phone. It doesn't survive the system closing the app in the
 * background yet; storage arrives in the next milestone.
 *
 * @param players who could play. The sample players until storage exists.
 * @param generateDispatcher where the balancer runs, off the main thread. Tests pass a
 *   test dispatcher so they decide when the work finishes.
 */
class MatchDayViewModel(
    players: List<Player> = SamplePlayers.all,
    private val generateDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel(), MatchDayActions {

    private val _state = MutableStateFlow(MatchDayUiState(players.sortedBy { it.name.lowercase() }))

    /** The current screen state. Collect it; it always has a value. */
    val state: StateFlow<MatchDayUiState> = _state.asStateFlow()

    /** The balancer run in progress, if any, so it can be cancelled. */
    private var generateJob: Job? = null

    // ---- Roster -------------------------------------------------------------------------

    /**
     * Ticks or unticks a player. A balancer run in progress is now out of date, so it
     * stops. Unticking also drops the player's lock, so it can't quietly come back if
     * they're ticked again later.
     */
    override fun toggleSelected(playerId: String) {
        cancelGenerating()
        _state.update {
            if (playerId in it.selectedIds) {
                it.copy(selectedIds = it.selectedIds - playerId, locks = it.locks - playerId, problem = null)
            } else {
                it.copy(selectedIds = it.selectedIds + playerId, problem = null)
            }
        }
    }

    /** Updates the search text. */
    override fun setSearch(text: String) = _state.update { it.copy(search = text) }

    /** Updates the "+ New player" text, cut to [MAX_NAME_LENGTH] characters. */
    override fun setNewPlayerName(text: String) =
        _state.update { it.copy(newPlayerName = text.take(MAX_NAME_LENGTH), duplicateName = null) }

    /**
     * Adds the name in the "+ New player" field to the roster and ticks them.
     *
     * Blank names are ignored. A name already on the roster (ignoring case and spaces at
     * the ends) is refused with a message, so two "Sam"s can't be confused on the pitch.
     * The new player starts as an average unknown, [EngineConfig.MU0] ± [EngineConfig.SIGMA0],
     * and lives only in memory for now. Like ticking, it stops a balancer run in progress,
     * which wouldn't include them.
     */
    override fun addPlayer() {
        val current = _state.value
        val name = current.newPlayerName.trim()
        if (name.isEmpty()) return
        if (current.players.any { it.name.equals(name, ignoreCase = true) }) {
            _state.update { it.copy(duplicateName = name) }
            return
        }
        cancelGenerating()
        val usedIds = current.players.map { it.id }.toSet()
        // The first free id after the roster's size. Counting alone could repeat an id
        // if players are ever removed, so check.
        val id = generateSequence(current.players.size + 1) { it + 1 }
            .map(SamplePlayers::idFor)
            .first { it !in usedIds }
        val player = Player(id = id, name = name, rating = Rating(EngineConfig.MU0, EngineConfig.SIGMA0))
        _state.update {
            it.copy(
                players = (it.players + player).sortedBy { p -> p.name.lowercase() },
                selectedIds = it.selectedIds + player.id,
                newPlayerName = "",
                duplicateName = null,
                problem = null,
            )
        }
    }

    /** "Generate teams": runs the balancer and, when it's done, shows the proposals. */
    override fun generate() = runBalancer()

    /** "Make teams manually": skips the balancer and opens the board with everyone in the pool. */
    override fun makeTeamsManually() {
        if (!_state.value.canMakeTeams) return
        cancelGenerating()
        _state.update {
            val board = fromRoster(it.selectedIds.toList())
            // Old proposals don't belong to this board; drop them, as Back to the roster does.
            it.copy(
                step = Step.MANUAL,
                workingCopy = board,
                liveMeter = null,
                splits = emptyList(),
                selectedSplit = 0,
                problem = null,
            )
        }
    }

    // ---- Pick a split -------------------------------------------------------------------

    /** Chooses a proposal. If there are hand edits, asks first, because they would be lost. */
    override fun selectSplit(index: Int) {
        val current = _state.value
        if (current.generating || index == current.selectedSplit || index !in current.splits.indices) return
        if (current.workingCopy != null) {
            _state.update { it.copy(discardPrompt = DiscardAction.SelectSplit(index)) }
        } else {
            _state.update { it.copy(selectedSplit = index) }
        }
    }

    /** "↻ Re-generate": runs the balancer again with the current locks, asking first if there are hand edits. */
    override fun regenerate() {
        val current = _state.value
        if (current.generating) return
        if (current.workingCopy != null) {
            _state.update { it.copy(discardPrompt = DiscardAction.Regenerate) }
        } else {
            runBalancer()
        }
    }

    /** "Discard" in the dialog: drops the hand edits and does what was asked. */
    override fun confirmDiscard() {
        val action = _state.value.discardPrompt ?: return
        _state.update { it.copy(discardPrompt = null, workingCopy = null, liveMeter = null) }
        when (action) {
            is DiscardAction.SelectSplit -> _state.update { it.copy(selectedSplit = action.index) }
            DiscardAction.Regenerate -> runBalancer()
        }
    }

    /** "Cancel" in the dialog, or tapping outside it: keeps the edits. */
    override fun dismissDiscard() = _state.update { it.copy(discardPrompt = null) }

    /**
     * Moves a player to their next place: across to the other team on the chosen proposal,
     * or one step round Pool → Bibs → Non-bibs → Pool on the manual board.
     *
     * The first move on a proposal turns it into an EDITED working copy. The meter is
     * worked out again straight away, on this thread: one evaluation is cheap.
     *
     * Moving a player also clears their lock. This is a deliberate divergence: in the
     * original web version, editing never changed locks. But a lock means "keep them on
     * this side", so after a move the chip would show a lock that no longer matches the
     * side they're on. Clearing it keeps what the screen shows and what re-generate does
     * in step.
     *
     * Ignored while generating, and for an id that isn't on the board.
     */
    override fun movePlayer(playerId: String) {
        _state.update { current ->
            if (current.generating) return@update current
            val start = current.workingCopy
                ?: current.splits.getOrNull(current.selectedSplit)?.let { fromSplit(it.teamA, it.teamB) }
                ?: return@update current
            val moved = start.move(playerId)
            // An unknown id leaves the board as it was: no EDITED badge for nothing.
            if (moved == start) return@update current
            current.copy(
                workingCopy = moved,
                liveMeter = meterFor(current.ratingsById, moved.teamA, moved.teamB),
                locks = current.locks - playerId,
            )
        }
    }

    /** Taps a player's lock in a card where they play on [side]. See [lockAfterTap]. Ignored while generating. */
    override fun toggleLock(playerId: String, side: Side) = _state.update {
        if (it.generating) it else it.copy(locks = lockAfterTap(it.locks, playerId, side))
    }

    /** "Continue →": takes the hand-edited teams, or else the chosen proposal, to the confirm step. */
    override fun continueToConfirm() {
        _state.update { current ->
            if (!current.canContinue) return@update current
            val edited = current.workingCopy
            val chosen = if (edited != null) {
                ChosenTeams(edited.teamA, edited.teamB, edited.mode)
            } else {
                val split = current.splits[current.selectedSplit]
                ChosenTeams(split.teamA, split.teamB, Selection.GENERATED)
            }
            current.copy(step = Step.CONFIRM, chosen = chosen, discardPrompt = null)
        }
    }

    // ---- Confirm and back ---------------------------------------------------------------

    /**
     * "Confirm ✓": for now, says saving arrives next milestone and returns to the roster.
     *
     * The ticks are kept, so starting again doesn't mean re-ticking everyone. Locks are
     * cleared: the roster has no lock controls, so a lock left behind would act unseen.
     * The original web version records the game here and moves on to the result instead.
     */
    override fun confirm() = _state.update {
        it.copy(
            step = Step.ROSTER,
            splits = emptyList(),
            selectedSplit = 0,
            locks = emptyMap(),
            workingCopy = null,
            liveMeter = null,
            chosen = null,
            discardPrompt = null,
            notice = R.string.notice_saving_next_milestone,
        )
    }

    /** The snackbar has shown [MatchDayUiState.notice], so it can be cleared. */
    override fun noticeShown() = _state.update { it.copy(notice = null) }

    /**
     * "← Back", or the phone's back gesture: one step back.
     *
     * Leaving the generate or manual step drops any hand edits, as the original web
     * version does, and the locks, which can't be seen or changed from the roster.
     * Leaving the confirm step keeps both, so you land on the board you came from.
     */
    override fun back() {
        cancelGenerating()
        _state.update {
            when (it.step) {
                Step.ROSTER -> it
                Step.GENERATE, Step.MANUAL -> it.copy(
                    step = Step.ROSTER,
                    splits = emptyList(),
                    selectedSplit = 0,
                    workingCopy = null,
                    liveMeter = null,
                    locks = emptyMap(),
                    problem = null,
                    discardPrompt = null,
                )
                Step.CONFIRM -> it.copy(
                    step = if (it.chosen?.selection == Selection.MANUAL) Step.MANUAL else Step.GENERATE,
                    discardPrompt = null,
                )
            }
        }
    }

    // ---- The balancer -------------------------------------------------------------------

    /**
     * Balances the ticked players on [generateDispatcher], so the screen never freezes,
     * then shows the top [OPTIONS] proposals with the first one chosen.
     *
     * If the engine says no (too few players, impossible locks), its reason shows as
     * plain text and the old proposals stay.
     */
    private fun runBalancer() {
        val current = _state.value
        val roster = current.players
            .filter { it.id in current.selectedIds }
            .map { BalancerPlayer(it.id, it.rating) }
        // Unticking already drops a lock; this is a second line of defence, since a lock
        // on someone not playing would only make the engine refuse.
        val locks = current.locks.filterKeys { it in current.selectedIds }
        val names = current.namesById
        val ratings = current.ratingsById

        cancelGenerating()
        _state.update { it.copy(generating = true, problem = null) }
        generateJob = viewModelScope.launch {
            val result = withContext(generateDispatcher) { Balancer.balance(roster, locks, k = OPTIONS) }
            _state.update {
                when (result) {
                    is BalanceResult.Impossible -> it.copy(generating = false, problem = result.reason.message())
                    is BalanceResult.Splits -> it.copy(
                        generating = false,
                        step = Step.GENERATE,
                        splits = result.splits.map { split ->
                            // The engine lists players by id, which for the sample players is
                            // best first. Name order gives nothing away on a shared screen.
                            val teamA = split.teamA.sortedBy { id -> names.getValue(id).lowercase() }
                            val teamB = split.teamB.sortedBy { id -> names.getValue(id).lowercase() }
                            // The balancer needs four players, so neither side is ever empty.
                            val meter = checkNotNull(meterFor(ratings, teamA, teamB)) { "Balancer returned an empty team" }
                            SplitOption(teamA, teamB, meter)
                        },
                        sampled = result.sampled,
                        selectedSplit = 0,
                        workingCopy = null,
                        liveMeter = null,
                    )
                }
            }
        }
    }

    private fun cancelGenerating() {
        generateJob?.cancel()
        generateJob = null
        _state.update { if (it.generating) it.copy(generating = false) else it }
    }
}

/** The plain-English sentence for each reason the balancer can refuse. */
@StringRes
private fun BalanceResult.Reason.message(): Int = when (this) {
    BalanceResult.Reason.TOO_FEW_PLAYERS -> R.string.problem_too_few
    BalanceResult.Reason.TOO_MANY_PLAYERS -> R.string.problem_too_many
    BalanceResult.Reason.LOCKS_UNSATISFIABLE -> R.string.problem_locks
    BalanceResult.Reason.UNKNOWN_LOCKED_PLAYER,
    BalanceResult.Reason.DUPLICATE_PLAYER,
    -> R.string.problem_unexpected
}
