package io.github.valnrys.teambayes.matchday

import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.Side
import io.github.valnrys.teambayes.engine.WengLin

// ---------------------------------------------------------------------------------------
// Hand-editing teams, with the same rules as the original web version of this tool.
//
// Everything in this file is a pure function: it takes values and returns new values,
// never changes what it was given, and knows nothing about Android or the screen. That
// makes it the easiest code in the app to test, and it is where the fiddly rules live
// (who moves where, when teams are ready, when to warn). The ViewModel only calls in.
//
// Players are referred to by id. Team A is always the Bibs and team B the Non-bibs.
// How the teams were made (generated, edited or manual) is the storage layer's
// [Selection], so the badge on the confirm step is exactly what gets saved.
// ---------------------------------------------------------------------------------------

/**
 * Teams that someone is editing by hand: a "working copy", separate from the balancer's
 * proposals so that those stay as they were.
 *
 * @property mode [Selection.EDITED] (started from a proposal, so [pool] is always empty)
 *   or [Selection.MANUAL] (started on the blank manual board).
 * @property teamA the Bibs, in the order they joined.
 * @property teamB the Non-bibs, in the order they joined.
 * @property pool players not on either team yet. Only the manual board uses it.
 */
data class WorkingCopy(
    val mode: Selection,
    val teamA: List<String>,
    val teamB: List<String>,
    val pool: List<String> = emptyList(),
) {
    init {
        require(mode != Selection.GENERATED) { "A working copy is EDITED or MANUAL, never GENERATED" }
    }
}

/** Starts editing a proposed split: everyone stays where the balancer put them. */
fun fromSplit(teamA: List<String>, teamB: List<String>): WorkingCopy =
    WorkingCopy(Selection.EDITED, teamA, teamB)

/** Starts the manual board: everyone begins in the pool, in the order given. */
fun fromRoster(playerIds: List<String>): WorkingCopy =
    WorkingCopy(Selection.MANUAL, teamA = emptyList(), teamB = emptyList(), pool = playerIds)

/**
 * Moves one player to their next place. This is the only way teams change by hand.
 *
 * - Editing a proposal: Bibs and Non-bibs swap, A ↔ B.
 * - Manual board: Pool → Bibs → Non-bibs → Pool.
 *
 * The player joins the end of their new list. An id that isn't on the board gives back
 * the very same [WorkingCopy], unchanged.
 */
fun WorkingCopy.move(playerId: String): WorkingCopy = when (playerId) {
    in teamA -> copy(teamA = teamA - playerId, teamB = teamB + playerId)
    in teamB -> when (mode) {
        Selection.MANUAL -> copy(teamB = teamB - playerId, pool = pool + playerId)
        else -> copy(teamB = teamB - playerId, teamA = teamA + playerId)
    }
    in pool -> copy(pool = pool - playerId, teamA = teamA + playerId)
    else -> this
}

/**
 * Where [playerId] would go on their next [move], or `null` if they aren't on the board.
 * The screen uses it to tell TalkBack users what a tap will do ("move to Non-bibs").
 */
fun WorkingCopy.nextPlace(playerId: String): Place? = when (playerId) {
    in teamA -> Place.NON_BIBS
    in teamB -> if (mode == Selection.MANUAL) Place.POOL else Place.BIBS
    in pool -> Place.BIBS
    else -> null
}

/** Where a player can be on the board. */
enum class Place { POOL, BIBS, NON_BIBS }

/** Ready to confirm: nobody left in the pool, and someone on each team. */
fun WorkingCopy.isComplete(): Boolean = pool.isEmpty() && teamA.isNotEmpty() && teamB.isNotEmpty()

/**
 * Two team sizes that differ by more than one, bigger first: "6 v 4".
 *
 * Advisory only. Real nights are messy and the rating engine copes with uneven teams,
 * so this turns the confirm button amber but never stops anyone confirming.
 */
data class SizeGap(val bigger: Int, val smaller: Int)

/** The [SizeGap] between teams of [sizeA] and [sizeB], or `null` if they are within one. */
fun sizeWarning(sizeA: Int, sizeB: Int): SizeGap? =
    if (kotlin.math.abs(sizeA - sizeB) <= 1) null else SizeGap(maxOf(sizeA, sizeB), minOf(sizeA, sizeB))

/**
 * The next lock for [playerId] after a tap on their lock in a card where they play for [side].
 *
 * A lock tells the next re-generate to keep a player on one side. A tap cycles:
 * unlocked → locked to this side → unlocked. If they were locked to the *other* side
 * (from a different option card), the tap moves the lock to this side. The original web
 * version behaved the same way.
 */
fun lockAfterTap(locks: Map<String, Side>, playerId: String, side: Side): Map<String, Side> =
    when (locks[playerId]) {
        side -> locks - playerId
        else -> locks + (playerId to side)
    }

/**
 * The model's view of one pair of teams: how even, and who it expects to win.
 *
 * Commentary, never a gate: nothing in the app stops you confirming because of it.
 *
 * @property quality 0 to 1, where 1.0 is as even as these players could be. See [WengLin.quality].
 * @property winProbabilityA the chance the Bibs win. The Non-bibs get the rest.
 */
data class Meter(val quality: Double, val winProbabilityA: Double)

/**
 * Scores [teamA] against [teamB] with the engine, or `null` while either side is empty.
 *
 * One evaluation is a handful of sums and two bell-curve lookups, so it's cheap enough to
 * run on the main thread straight after every tap, with no delay.
 *
 * @param ratings every player's rating, by id. Every id in both teams must be present.
 */
fun meterFor(ratings: Map<String, Rating>, teamA: List<String>, teamB: List<String>): Meter? {
    if (teamA.isEmpty() || teamB.isEmpty()) return null
    val a = teamA.map(ratings::getValue)
    val b = teamB.map(ratings::getValue)
    return Meter(quality = WengLin.quality(a, b), winProbabilityA = WengLin.predictWin(a, b).teamA)
}
