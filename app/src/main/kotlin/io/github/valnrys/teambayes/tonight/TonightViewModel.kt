package io.github.valnrys.teambayes.tonight

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.BalanceResult
import io.github.valnrys.teambayes.engine.Balancer
import io.github.valnrys.teambayes.engine.BalancerPlayer
import io.github.valnrys.teambayes.engine.Split
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many of the best splits we keep, so "Try another" has something to cycle through. */
private const val KEPT_SPLITS = 5

/** What the bottom half of the screen is showing. */
sealed interface TonightResult {

    /** Nothing yet, or the ticks changed since the last result. */
    data object Idle : TonightResult

    /** The balancer is working. */
    data object Balancing : TonightResult

    /**
     * The kept splits, with one on show.
     *
     * Keeping every split here, rather than in a separate field, means the screen state
     * is the only copy: nothing can drift out of step with it.
     *
     * @property splits the kept splits, best first. Never empty.
     * @property index which one is on show, counting from 0 (the screen shows `index + 1`).
     */
    data class Teams(val splits: List<Split>, val index: Int) : TonightResult {

        init {
            require(splits.isNotEmpty()) { "splits must not be empty" }
            require(index in splits.indices) { "index $index is outside 0 until ${splits.size}" }
        }

        /** The split on show. */
        val split: Split get() = splits[index]

        /** How many splits were kept. */
        val total: Int get() = splits.size
    }

    /** Balancing wasn't possible; [message] is a string resource explaining why. */
    data class Problem(@StringRes val message: Int) : TonightResult
}

/**
 * Everything [TonightScreen] draws, as one immutable value.
 *
 * @property players everyone who could play, in display order.
 * @property ticked ids of the players who are playing tonight.
 */
data class TonightUiState(
    val players: List<TonightPlayer>,
    val ticked: Set<String>,
    val result: TonightResult = TonightResult.Idle,
) {
    /** How many players are ticked. */
    val playingCount: Int get() = ticked.size
}

/**
 * Holds the state of the "Tonight" screen and runs the balancer.
 *
 * A [ViewModel] outlives screen rotations, so ticks and shown teams survive them. They do
 * not survive the system killing the app in the background; that is fine for these sample
 * players, because storage arrives in the next milestone. The screen only reads [state]
 * and calls the three actions: [toggle], [balance] and [reroll].
 *
 * If [Balancer.balance] ever threw, the exception would crash the app. That is acceptable
 * for now: on bad input it returns [BalanceResult.Impossible] instead of throwing.
 *
 * @param players who could play tonight.
 * @param initiallyTicked ids that start ticked; everyone by default.
 * @param balanceDispatcher where the balancer runs. Tests pass a test dispatcher so they
 *   control when the work finishes.
 */
class TonightViewModel(
    players: List<TonightPlayer> = SamplePlayers.tonight,
    initiallyTicked: Set<String> = players.map { it.id }.toSet(),
    private val balanceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(TonightUiState(players, initiallyTicked))

    /** The current screen state. Collect it; it always has a value. */
    val state: StateFlow<TonightUiState> = _state.asStateFlow()

    /** The balancing in progress, if any, so a tick can cancel it. */
    private var balanceJob: Job? = null

    /** Ticks or unticks a player. Any teams on show are cleared, because they are now out of date. */
    fun toggle(playerId: String) {
        balanceJob?.cancel()
        _state.update { current ->
            val ticked = if (playerId in current.ticked) current.ticked - playerId else current.ticked + playerId
            current.copy(ticked = ticked, result = TonightResult.Idle)
        }
    }

    /**
     * Balances the ticked players and shows the most even split.
     *
     * The search runs on [balanceDispatcher] so the screen never freezes.
     */
    fun balance() {
        val current = _state.value
        val roster = current.players
            .filter { it.id in current.ticked }
            .map { BalancerPlayer(it.id, it.rating) }

        balanceJob?.cancel()
        _state.update { it.copy(result = TonightResult.Balancing) }
        balanceJob = viewModelScope.launch {
            val outcome = withContext(balanceDispatcher) { Balancer.balance(roster, k = KEPT_SPLITS) }
            val result = when (outcome) {
                is BalanceResult.Impossible -> TonightResult.Problem(outcome.reason.message())
                // The engine always returns at least one split, but Teams requires a
                // non-empty list, so an empty one is shown as a problem, not a crash.
                // Balancer is a fixed object, so there is no seam to fake this in a test.
                is BalanceResult.Splits ->
                    if (outcome.splits.isEmpty()) {
                        TonightResult.Problem(R.string.problem_unexpected)
                    } else {
                        TonightResult.Teams(outcome.splits, index = 0)
                    }
            }
            _state.update { it.copy(result = result) }
        }
    }

    /** Shows the next kept split, wrapping from the last back to the first. Does nothing if no teams are shown. */
    fun reroll() {
        _state.update { current ->
            val shown = current.result as? TonightResult.Teams ?: return@update current
            current.copy(result = shown.copy(index = (shown.index + 1) % shown.total))
        }
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
