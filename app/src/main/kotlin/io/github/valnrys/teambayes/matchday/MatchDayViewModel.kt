package io.github.valnrys.teambayes.matchday

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.valnrys.teambayes.AppContainer
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.DuplicateNameException
import io.github.valnrys.teambayes.data.GameRepository
import io.github.valnrys.teambayes.data.PendingGameExistsException
import io.github.valnrys.teambayes.data.PlayerRepository
import io.github.valnrys.teambayes.data.RatingsRepository
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.data.RatedPlayer
import io.github.valnrys.teambayes.data.normaliseName
import io.github.valnrys.teambayes.engine.BalanceResult
import io.github.valnrys.teambayes.engine.Balancer
import io.github.valnrys.teambayes.engine.BalancerPlayer
import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Prior
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.Side
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
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
 * The original web version goes on to record the result and show rating changes. Those
 * steps arrive next; for now, confirming saves the teams as the pending game.
 */
enum class Step {
    /** "Who's playing?": tick players, then generate or make teams by hand. */
    ROSTER,

    /** "Pick a split": three proposals, tap-to-move editing, locks and re-generate. */
    GENERATE,

    /** "Make teams manually": the Pool / Bibs / Non-bibs board. */
    MANUAL,

    /** "Confirm teams": a last look, and the date, before the game is saved. */
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
 * A new player waiting in the "Where do they fit?" sheet.
 *
 * @property name the name typed in "+ New player", without spaces at the ends.
 * @property position where they sit in [MatchDayUiState.ranking]: 0 is above everyone,
 *   `ranking.size` is below everyone.
 * @property saving true while they are being written to storage.
 */
data class NewPlayerPlacement(val name: String, val position: Int, val saving: Boolean = false)

/**
 * Everything the match-day screens draw, as one immutable value.
 *
 * It's deliberately flat: one field per thing the organiser can see or change, in the
 * same shape as the original web version's screen state. Fields belonging to a later
 * step simply sit unused until then.
 *
 * @property players everyone who could play (the active players), in name order (A to Z).
 * @property loading true until the players have been read from storage. Nothing is drawn
 *   meanwhile, which takes a few milliseconds.
 * @property selectedIds who is playing, in the order they were ticked.
 * @property search the text in the search field.
 * @property newPlayerName the text in the "+ New player" field.
 * @property duplicateName set when the last name added was already taken.
 * @property placing the new player being placed in the "Where do they fit?" sheet, or
 *   `null` while the sheet is closed.
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
 * @property playDate the day the game is played, shown on the confirm step. Today by default.
 * @property saving true while Confirm is writing the game to storage.
 * @property discardPrompt set while the "Discard your edits?" dialog is open.
 * @property notice a one-off message for the snackbar; cleared once shown.
 */
data class MatchDayUiState(
    val players: List<Player>,
    val loading: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val search: String = "",
    val newPlayerName: String = "",
    val duplicateName: String? = null,
    val placing: NewPlayerPlacement? = null,
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
    val playDate: LocalDate = LocalDate.now(),
    val saving: Boolean = false,
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
     * The players best first, by current mu, for the "Where do they fit?" sheet. Ties go
     * by name, so the order never jumps about between redraws.
     */
    val ranking: List<Player> by lazy {
        players.sortedWith(compareByDescending<Player> { it.rating.mu }.thenBy { it.name.lowercase() })
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
 * The starting mu for a new player placed at [position] in [ranking] (best first).
 *
 * Their neighbours are the players just above and just below that spot, and
 * [Prior.insertBetween] takes it from there: the midpoint between them, or one step
 * beyond the end. [ranking] must be sorted by current mu, best first, as
 * [MatchDayUiState.ranking] is, because `insertBetween` refuses neighbours the wrong way
 * round.
 */
fun startingMuAt(ranking: List<Player>, position: Int): Double = Prior.insertBetween(
    above = ranking.getOrNull(position - 1)?.rating?.mu,
    below = ranking.getOrNull(position)?.rating?.mu,
)

/**
 * The moment stored for a game played on this day: **local noon**, in epoch milliseconds.
 *
 * Storage keeps an instant, but the organiser picks a day. Midnight would be fragile: the
 * same instant read in a time zone an hour to the west is the day before, and on a
 * daylight-saving change midnight can fall in the hour that's skipped. Noon is twelve
 * hours from either edge, so the day reads back the same anywhere within twelve hours of
 * here, and clock changes, which happen at night, never move it.
 */
fun LocalDate.toPlayedAt(zone: ZoneId): Long = atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

/**
 * Runs match day: the step machine, the balancer, and every tap in between.
 *
 * The screen reads [state] and calls the public functions here; it never changes
 * anything itself.
 *
 * **Where the data comes from.** The roster is the active players from [playerRepository],
 * each with their current rating, from [RatingsRepository.roster]: one feed, worked out
 * off the main thread by replaying every finished game (a player with none has their
 * starting mu and [EngineConfig.SIGMA0]). It's watched, so a newly added player appears
 * by themselves. "Confirm ✓" saves the teams
 * through [gameRepository] as the pending game.
 *
 * **Surviving rotation and process death.** A [ViewModel] outlives screen rotations for
 * free. Android can also kill the whole app in the background; then [savedStateHandle]
 * brings back the step, ticks, proposals, edits, locks and date (see [SavedMatchDay]).
 * Every change of state is mirrored into it; ratings are read from storage again.
 *
 * Built by [factory] in the app; tests call the constructor with fakes.
 *
 * @param savedStateHandle the bundle that outlives process death. Tests pass a plain
 *   `SavedStateHandle()`, or a used one to act out a restart.
 * @param generateDispatcher where the balancer runs, off the main thread. Tests pass a
 *   test dispatcher so they decide when the work finishes.
 * @param clock gives "today" for the date and the time zone for [toPlayedAt]. Tests pass a
 *   fixed one.
 */
class MatchDayViewModel(
    private val playerRepository: PlayerRepository,
    private val gameRepository: GameRepository,
    ratingsRepository: RatingsRepository,
    private val savedStateHandle: SavedStateHandle,
    private val generateDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), MatchDayActions {

    /** What process death left behind, applied once the players have loaded. */
    private val restored: SavedMatchDay? = SavedMatchDay.readFrom(savedStateHandle)

    private val _state = MutableStateFlow(MatchDayUiState(players = emptyList(), loading = true, playDate = today()))

    /** The current screen state. Collect it; it always has a value. */
    val state: StateFlow<MatchDayUiState> = _state.asStateFlow()

    /** Every stored name, inactive players included, as [nameKey]s: names must be unique among all of them. */
    private var takenNames: Set<String> = emptySet()

    /**
     * Players just saved from "+ New player" that storage hasn't reported back yet, by id.
     *
     * Saving returns before the players flow emits again, and the new player is ticked at
     * once. Until the flow catches up they're shown from here, so every id on the roster
     * has a name and a rating (Make teams manually and Generate both rely on that). Each
     * leaves this map as soon as an emission includes them.
     */
    private val awaitingStorage = mutableMapOf<String, Player>()

    /** The balancer run in progress, if any, so it can be cancelled. */
    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            ratingsRepository.roster.map { roster ->
                awaitingStorage.keys.removeAll(roster.map { it.stored.id }.toSet())
                takenNames = (roster.map { it.stored.name } + awaitingStorage.values.map { it.name })
                    .map(::nameKey)
                    .toSet()
                (roster.filter { it.stored.active }.map { it.toPlayer() } + awaitingStorage.values)
                    .sortedBy { it.name.lowercase() }
            }.collect(::showPlayers)
        }
        // Mirror the state into the saved-state bundle. Only once loaded, so the empty
        // placeholder never overwrites a match day waiting to be restored.
        viewModelScope.launch {
            state.filterNot { it.loading }
                .map { it.toSaved() }
                .distinctUntilChanged()
                .collect { it.writeTo(savedStateHandle) }
        }
    }

    /**
     * Shows the latest players and ratings. The first time, this is also when a match day
     * saved before process death comes back. Afterwards the meters are worked out again,
     * in case a rating moved.
     */
    private fun showPlayers(players: List<Player>) = _state.update { current ->
        if (current.loading) {
            restored?.restore(players) ?: MatchDayUiState(players = players, playDate = current.playDate)
        } else {
            val ratings = players.associate { it.id to it.rating }
            current.copy(
                players = players,
                splits = current.splits.map { it.copy(meter = meterFor(ratings, it.teamA, it.teamB) ?: it.meter) },
                liveMeter = current.workingCopy?.let { meterFor(ratings, it.teamA, it.teamB) },
            )
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

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
     * "Add" next to "+ New player": opens "Where do they fit?" so the organiser can place
     * the new player among the others before they're saved.
     *
     * Blank names are ignored. A name already taken is refused with a message, so two
     * "Sam"s can't be confused on the pitch. Names are compared as storage compares them
     * (ignoring case, spaces at the ends and doubled spaces inside), counting players no
     * longer on the roster.
     *
     * The new player starts in the middle of the ranking: most newcomers are about
     * average, and it's the shortest drag to either end.
     */
    override fun addPlayer() {
        val current = _state.value
        val name = normaliseName(current.newPlayerName)
        if (name.isEmpty() || current.placing != null) return
        if (nameKey(name) in takenNames) {
            _state.update { it.copy(duplicateName = name) }
            return
        }
        _state.update {
            it.copy(placing = NewPlayerPlacement(name, position = it.ranking.size / 2), duplicateName = null)
        }
    }

    /**
     * Moves the new player to [position] in the ranking, from a drag or from TalkBack's
     * "Move up" and "Move down". Positions past either end are pulled back in.
     */
    override fun moveNewPlayer(position: Int) = _state.update {
        val placing = it.placing
        if (placing == null || placing.saving) {
            it
        } else {
            it.copy(placing = placing.copy(position = position.coerceIn(0, it.ranking.size)))
        }
    }

    /** "Cancel" on the sheet, or swiping it away: nothing is saved and the typed name stays. */
    override fun cancelNewPlayer() = _state.update {
        if (it.placing?.saving == true) it else it.copy(placing = null)
    }

    /**
     * "Save" on the sheet: stores the new player with a starting mu between their
     * neighbours (see [startingMuAt]) and ticks them. They appear on the roster as soon
     * as storage reports them.
     *
     * Like ticking someone, it stops a balancer run in progress, which wouldn't include
     * them. If the name turns out to be taken after all (storage also compares accented
     * letters, such as "Émile" and "émile"), the sheet closes and the field says so.
     */
    override fun saveNewPlayer() {
        val current = _state.value
        val placing = current.placing ?: return
        if (placing.saving) return
        val startingMu = startingMuAt(current.ranking, placing.position)
        _state.update { it.copy(placing = placing.copy(saving = true)) }
        viewModelScope.launch {
            try {
                val id = playerRepository.addPlayer(placing.name, startingMu)
                cancelGenerating()
                // Show and tick them now, in one update, rather than wait for storage to
                // report them: see [awaitingStorage].
                // If storage was quick and has already reported them, there's nothing to wait for.
                val player = Player(id, placing.name, Rating(startingMu, EngineConfig.SIGMA0))
                val alreadyShown = _state.value.players.any { it.id == id }
                if (!alreadyShown) awaitingStorage[id] = player
                takenNames = takenNames + nameKey(player.name)
                _state.update {
                    it.copy(
                        players = if (alreadyShown) it.players else (it.players + player).sortedBy { p -> p.name.lowercase() },
                        placing = null,
                        newPlayerName = "",
                        selectedIds = it.selectedIds + id,
                        problem = null,
                    )
                }
            } catch (e: DuplicateNameException) {
                _state.update { it.copy(placing = null, duplicateName = e.name) }
            } catch (e: CancellationException) {
                throw e // The screen went away; not a failure to report.
            } catch (e: Exception) {
                _state.update { it.copy(placing = null, notice = R.string.notice_player_save_failed) }
            }
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
     * The day picked in the confirm step's date picker. A future day is ignored: games are
     * replayed in date order, so it would sort after games really played before it. The
     * picker doesn't offer one either.
     */
    override fun setPlayDate(date: LocalDate) {
        // A picker left open while Confirm saves can't change the date under the save.
        if (_state.value.saving || date.isAfter(today())) return
        _state.update { it.copy(playDate = date) }
    }

    /**
     * "Confirm ✓": saves the teams as the pending game, dated [MatchDayUiState.playDate]
     * (stored as local noon, see [toPlayedAt]), with the badge's GENERATED, EDITED or
     * MANUAL as its selection.
     *
     * Once saved, it says so and returns to the roster. The ticks are kept, so starting
     * again doesn't mean re-ticking everyone. Locks are cleared: the roster has no lock
     * controls, so a lock left behind would act unseen. The date goes back to today.
     *
     * Divergence from the original web version, for now: that moves straight on to
     * recording the result. Recording results arrives next; until then the game waits,
     * pending, in storage.
     *
     * If an unfinished game is already stored, nothing is saved and the organiser stays
     * here with a message, teams intact. A second tap while saving does nothing.
     */
    override fun confirm() {
        val current = _state.value
        val chosen = current.chosen
        if (current.step != Step.CONFIRM || chosen == null || current.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            @StringRes val failure: Int? = try {
                gameRepository.savePending(
                    playedAt = current.playDate.toPlayedAt(clock.zone),
                    teamA = chosen.teamA,
                    teamB = chosen.teamB,
                    selection = chosen.selection,
                )
                null
            } catch (e: PendingGameExistsException) {
                R.string.notice_pending_game_exists
            } catch (e: CancellationException) {
                throw e // The screen went away; not a failure to report.
            } catch (e: Exception) {
                R.string.notice_game_save_failed
            }
            _state.update {
                if (failure != null) {
                    it.copy(saving = false, notice = failure)
                } else {
                    it.copy(
                        step = Step.ROSTER,
                        splits = emptyList(),
                        selectedSplit = 0,
                        locks = emptyMap(),
                        workingCopy = null,
                        liveMeter = null,
                        chosen = null,
                        discardPrompt = null,
                        playDate = today(),
                        saving = false,
                        notice = R.string.notice_game_saved,
                    )
                }
            }
        }
    }

    /** The snackbar has shown [MatchDayUiState.notice], so it can be cleared. */
    override fun noticeShown() = _state.update { it.copy(notice = null) }

    /**
     * "← Back", or the phone's back gesture: one step back.
     *
     * Leaving the generate or manual step drops any hand edits, as the original web
     * version does, and the locks, which can't be seen or changed from the roster.
     * Leaving the confirm step keeps both, so you land on the board you came from.
     * Ignored while the game is being saved.
     */
    override fun back() {
        if (_state.value.saving) return
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
                            // The engine lists players by id, which means nothing to a person.
                            // Name order is easy to scan and gives nothing away on a shared screen.
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

    companion object {
        /**
         * Builds the ViewModel from the app's [AppContainer], with a [SavedStateHandle]
         * that Android keeps across process death. Pass it to Compose's `viewModel()`.
         * This is the whole of our "dependency injection": no library needed.
         */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MatchDayViewModel(
                    playerRepository = container.playerRepository,
                    gameRepository = container.gameRepository,
                    ratingsRepository = container.ratingsRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

/** A stored player as match day sees them, with their current rating (their starting one if they have no games yet). */
private fun RatedPlayer.toPlayer() = Player(id = stored.id, name = stored.name, rating = rating)

/**
 * How two names are compared: in storage's one form (see [normaliseName]), ignoring case.
 * So "Sam  Smith" and "sam smith" are the same name, as storage would also say.
 */
private fun nameKey(name: String): String = normaliseName(name).lowercase()

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
