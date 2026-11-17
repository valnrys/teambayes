package io.github.valnrys.teambayes.matchday

import androidx.lifecycle.SavedStateHandle
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.engine.Side
import java.time.LocalDate

// ---------------------------------------------------------------------------------------
// Surviving process death.
//
// Android may kill an app while it's in the background (say, while the organiser takes a
// phone call) and start it again when they come back. A ViewModel doesn't survive that,
// but a SavedStateHandle does: a small bundle of plain values that Android keeps for us.
//
// Only small, plain values go in: player ids, indices, names of enum values, a date, and
// what's been typed (the search text, a new player's name, an adjusted score). Ratings
// and stored players' names are never saved. They are read from storage again, so they
// can't go stale, and the bundle stays well under Android's size limit.
//
// A Bundle can hold Strings, Ints, Longs, Booleans and ArrayList<String>, but not our own
// classes, so each part is written under its own key.
// ---------------------------------------------------------------------------------------

/**
 * The parts of [MatchDayUiState] that are worth keeping across process death: the step,
 * the ticks, the proposals and which one is chosen, the locks, the working copy (the
 * edited proposal or the manual board), the confirm step's teams and its date, and which
 * game the record or done step is showing.
 *
 * What's left out comes back by itself (the players and their ratings, the goals and the
 * adjusted score, all from storage) or is fine to lose (an open dialog, the "Where do they
 * fit?" sheet, a snackbar).
 *
 * @property splits the balancer's proposals as (Bibs, Non-bibs) id lists, without meters:
 *   those are worked out again from the ratings.
 * @property gameId the game on the record or done step, read back from storage on restore.
 * @property typedA the record step's Bibs score box as typed. Storage keeps the number;
 *   this keeps a cleared box blank rather than showing 0.
 */
internal data class SavedMatchDay(
    val step: Step,
    val selectedIds: List<String>,
    val splits: List<Pair<List<String>, List<String>>>,
    val sampled: Boolean,
    val selectedSplit: Int,
    val locks: Map<String, Side>,
    val workingCopy: WorkingCopy?,
    val chosen: ChosenTeams?,
    val playDate: LocalDate,
    val search: String,
    val newPlayerName: String,
    val gameId: String? = null,
    val typedA: String = "",
    val typedB: String = "",
) {
    /** Writes every part into [handle], replacing what was there. */
    fun writeTo(handle: SavedStateHandle) {
        handle[KEY_STEP] = step.name
        handle[KEY_SELECTED] = ArrayList(selectedIds)
        handle[KEY_SPLIT_COUNT] = splits.size
        splits.forEachIndexed { i, (a, b) ->
            handle[keySplitA(i)] = ArrayList(a)
            handle[keySplitB(i)] = ArrayList(b)
        }
        handle[KEY_SAMPLED] = sampled
        handle[KEY_SELECTED_SPLIT] = selectedSplit
        handle[KEY_LOCKED_A] = ArrayList(locks.filterValues { it == Side.A }.keys)
        handle[KEY_LOCKED_B] = ArrayList(locks.filterValues { it == Side.B }.keys)
        handle[KEY_COPY_MODE] = workingCopy?.mode?.name
        handle[KEY_COPY_A] = workingCopy?.teamA?.let(::ArrayList)
        handle[KEY_COPY_B] = workingCopy?.teamB?.let(::ArrayList)
        handle[KEY_COPY_POOL] = workingCopy?.pool?.let(::ArrayList)
        handle[KEY_CHOSEN_SELECTION] = chosen?.selection?.name
        handle[KEY_CHOSEN_A] = chosen?.teamA?.let(::ArrayList)
        handle[KEY_CHOSEN_B] = chosen?.teamB?.let(::ArrayList)
        // A LocalDate isn't a Bundle type; its day number (days since 1 January 1970) is.
        handle[KEY_PLAY_DATE] = playDate.toEpochDay()
        handle[KEY_SEARCH] = search
        handle[KEY_NEW_PLAYER] = newPlayerName
        handle[KEY_GAME_ID] = gameId
        handle[KEY_TYPED_A] = typedA
        handle[KEY_TYPED_B] = typedB
    }

    companion object {
        /** The saved match day in [handle], or `null` if nothing was saved (a fresh start). */
        fun readFrom(handle: SavedStateHandle): SavedMatchDay? {
            val step = handle.get<String>(KEY_STEP)?.let(Step::valueOf) ?: return null
            val splitCount = handle.get<Int>(KEY_SPLIT_COUNT) ?: 0
            val copyMode = handle.get<String>(KEY_COPY_MODE)?.let(Selection::valueOf)
            val chosenSelection = handle.get<String>(KEY_CHOSEN_SELECTION)?.let(Selection::valueOf)
            return SavedMatchDay(
                step = step,
                selectedIds = handle.ids(KEY_SELECTED),
                splits = List(splitCount) { i -> handle.ids(keySplitA(i)) to handle.ids(keySplitB(i)) },
                sampled = handle.get<Boolean>(KEY_SAMPLED) ?: false,
                selectedSplit = handle.get<Int>(KEY_SELECTED_SPLIT) ?: 0,
                locks = handle.ids(KEY_LOCKED_A).associateWith { Side.A } +
                    handle.ids(KEY_LOCKED_B).associateWith { Side.B },
                workingCopy = copyMode?.let {
                    WorkingCopy(it, handle.ids(KEY_COPY_A), handle.ids(KEY_COPY_B), handle.ids(KEY_COPY_POOL))
                },
                chosen = chosenSelection?.let {
                    ChosenTeams(handle.ids(KEY_CHOSEN_A), handle.ids(KEY_CHOSEN_B), it)
                },
                playDate = LocalDate.ofEpochDay(checkNotNull(handle.get<Long>(KEY_PLAY_DATE))),
                search = handle.get<String>(KEY_SEARCH).orEmpty(),
                newPlayerName = handle.get<String>(KEY_NEW_PLAYER).orEmpty(),
                gameId = handle.get<String>(KEY_GAME_ID),
                typedA = handle.get<String>(KEY_TYPED_A).orEmpty(),
                typedB = handle.get<String>(KEY_TYPED_B).orEmpty(),
            )
        }

        private fun SavedStateHandle.ids(key: String): List<String> = get<ArrayList<String>>(key).orEmpty()

        private const val KEY_STEP = "step"
        private const val KEY_SELECTED = "selected"
        private const val KEY_SPLIT_COUNT = "splitCount"
        private fun keySplitA(index: Int) = "split${index}A"
        private fun keySplitB(index: Int) = "split${index}B"
        private const val KEY_SAMPLED = "sampled"
        private const val KEY_SELECTED_SPLIT = "selectedSplit"
        private const val KEY_LOCKED_A = "lockedA"
        private const val KEY_LOCKED_B = "lockedB"
        private const val KEY_COPY_MODE = "copyMode"
        private const val KEY_COPY_A = "copyA"
        private const val KEY_COPY_B = "copyB"
        private const val KEY_COPY_POOL = "copyPool"
        private const val KEY_CHOSEN_SELECTION = "chosenSelection"
        private const val KEY_CHOSEN_A = "chosenA"
        private const val KEY_CHOSEN_B = "chosenB"
        private const val KEY_PLAY_DATE = "playDate"
        private const val KEY_SEARCH = "search"
        private const val KEY_NEW_PLAYER = "newPlayer"
        private const val KEY_GAME_ID = "gameId"
        private const val KEY_TYPED_A = "typedA"
        private const val KEY_TYPED_B = "typedB"
    }
}

/** The parts of this state worth saving. */
internal fun MatchDayUiState.toSaved() = SavedMatchDay(
    step = step,
    selectedIds = selectedIds.toList(),
    splits = splits.map { it.teamA to it.teamB },
    sampled = sampled,
    selectedSplit = selectedSplit,
    locks = locks,
    workingCopy = workingCopy,
    chosen = chosen,
    playDate = playDate,
    search = search,
    newPlayerName = newPlayerName,
    gameId = recording?.gameId ?: finished?.gameId,
    typedA = recording?.typedA.orEmpty(),
    typedB = recording?.typedB.orEmpty(),
)

/**
 * The match day to show once the players have loaded from storage, rebuilt from what was
 * saved, with every meter worked out again from today's ratings.
 *
 * A record or done step comes back as the roster here: its game has to be read from
 * storage first, which the ViewModel does (and it lands on the roster if the game has
 * gone).
 *
 * Saved ids are checked against [players]. If the saved teams mention someone who isn't
 * there any more, the teams are dropped and the organiser lands on the roster with their
 * ticks, rather than meeting a crash or a nameless chip.
 */
internal fun SavedMatchDay.restore(players: List<Player>): MatchDayUiState {
    val known = players.map { it.id }.toSet()
    val ratings = players.associate { it.id to it.rating }
    val roster = MatchDayUiState(
        players = players,
        selectedIds = selectedIds.filter { it in known }.toSet(),
        search = search,
        newPlayerName = newPlayerName,
        playDate = playDate,
    )

    val teamIds = splits.flatMap { (a, b) -> a + b } +
        workingCopy?.let { it.teamA + it.teamB + it.pool }.orEmpty() +
        chosen?.let { it.teamA + it.teamB }.orEmpty()
    val stepIsComplete = when (step) {
        Step.ROSTER -> true
        Step.GENERATE -> splits.isNotEmpty()
        Step.MANUAL -> workingCopy != null
        // Confirm needs the board that Back returns to, as well as its own teams.
        Step.CONFIRM -> when (chosen?.selection) {
            null -> false
            Selection.GENERATED -> splits.isNotEmpty()
            Selection.EDITED -> splits.isNotEmpty() && workingCopy != null
            Selection.MANUAL -> workingCopy != null
        }
        Step.RECORD, Step.DONE -> false
    }
    if (step == Step.ROSTER || !stepIsComplete || !known.containsAll(teamIds)) return roster

    return roster.copy(
        step = step,
        splits = splits.map { (a, b) -> SplitOption(a, b, checkNotNull(meterFor(ratings, a, b))) },
        sampled = sampled,
        selectedSplit = selectedSplit.coerceIn(0, (splits.size - 1).coerceAtLeast(0)),
        locks = locks.filterKeys { it in known },
        workingCopy = workingCopy,
        liveMeter = workingCopy?.let { meterFor(ratings, it.teamA, it.teamB) },
        chosen = chosen,
    )
}
