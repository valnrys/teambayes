package io.github.valnrys.teambayes.matchday

import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MatchDayViewModel] that run on the laptop, with no phone needed.
 *
 * Two test dispatchers share one clock. The "main thread" one is unconfined, so the
 * ViewModel's coroutine starts at once. The "balancer thread" one is standard: it runs
 * nothing until the test calls [advanceUntilIdle], so a test can catch the ViewModel
 * mid-generate and see the "Generating…" state, or cancel it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchDayViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = UnconfinedTestDispatcher(scheduler)
    private val balancerDispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which doesn't exist on the JVM.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val players = SamplePlayers.all

    /** A ViewModel with the first [count] sample players ticked. */
    private fun viewModel(count: Int) = MatchDayViewModel(players, balancerDispatcher).apply {
        players.take(count).forEach { toggleSelected(it.id) }
    }

    /** A ViewModel on the generate step with [count] players' proposals showing. */
    private fun TestScope.generated(count: Int) = viewModel(count).apply {
        generate()
        advanceUntilIdle()
    }

    private val MatchDayViewModel.now get() = state.value

    // ---- Roster -------------------------------------------------------------------------

    @Test
    fun roster_isAlphabeticalAndStartsWithNobodySelected() {
        val vm = MatchDayViewModel(players, balancerDispatcher)
        assertEquals(players.map { it.name }.sorted(), vm.now.players.map { it.name })
        assertTrue(vm.now.selectedIds.isEmpty())
        assertFalse(vm.now.canMakeTeams)
    }

    @Test
    fun search_filtersByNameIgnoringCase() {
        val vm = viewModel(0)
        vm.setSearch("TAM")
        assertEquals(listOf("Tamsin"), vm.now.visiblePlayers.map { it.name })
        vm.setSearch("nobody")
        assertTrue(vm.now.visiblePlayers.isEmpty())
    }

    @Test
    fun addPlayer_addsSelectsAndClearsTheField() {
        val vm = viewModel(0)
        vm.setNewPlayerName("  Aurelio ")
        vm.addPlayer()

        val added = vm.now.players.single { it.name == "Aurelio" }
        assertEquals(setOf(added.id), vm.now.selectedIds)
        assertEquals("", vm.now.newPlayerName)
        assertEquals(players.size + 1, vm.now.players.size)
        assertEquals("Aurelio", vm.now.players.first().name) // still A to Z
    }

    @Test
    fun addPlayer_refusesADuplicateIgnoringCase() {
        val vm = viewModel(0)
        vm.setNewPlayerName("tamsin")
        vm.addPlayer()

        assertEquals("tamsin", vm.now.duplicateName)
        assertEquals(players.size, vm.now.players.size)
        assertTrue(vm.now.selectedIds.isEmpty())
        // Typing again clears the message.
        vm.setNewPlayerName("tamsin2")
        assertNull(vm.now.duplicateName)
    }

    @Test
    fun newPlayerName_isCutToSixtyCharacters() {
        val vm = viewModel(0)
        vm.setNewPlayerName("x".repeat(80))
        assertEquals(MAX_NAME_LENGTH, vm.now.newPlayerName.length)
    }

    @Test
    fun generate_withThreePlayersShowsTheEnginesReasonAndStaysOnTheRoster() = runTest(mainDispatcher) {
        val vm = generated(3)
        assertEquals(Step.ROSTER, vm.now.step)
        assertEquals(R.string.problem_too_few, vm.now.problem)
    }

    // ---- Generate -------------------------------------------------------------------------

    @Test
    fun generate_showsGeneratingThenThreeOptionsWithTheFirstChosen() = runTest(mainDispatcher) {
        val vm = viewModel(10)
        vm.generate()
        assertTrue(vm.now.generating)
        assertEquals(Step.ROSTER, vm.now.step)

        advanceUntilIdle()
        assertFalse(vm.now.generating)
        assertEquals(Step.GENERATE, vm.now.step)
        assertEquals(3, vm.now.splits.size)
        assertEquals(0, vm.now.selectedSplit)
        assertFalse(vm.now.sampled)
        vm.now.splits.forEach { split ->
            assertEquals(5, split.teamA.size)
            assertEquals(vm.now.selectedIds, (split.teamA + split.teamB).toSet())
            assertTrue(split.meter.quality in 0.0..1.0)
        }
        // Best first.
        assertTrue(vm.now.splits.zipWithNext().all { (a, b) -> a.meter.quality >= b.meter.quality })
    }

    @Test
    fun toggle_whileGeneratingCancelsIt() = runTest(mainDispatcher) {
        val vm = viewModel(10)
        vm.generate()
        vm.toggleSelected(players.first().id)
        advanceUntilIdle()

        assertFalse(vm.now.generating)
        assertEquals(Step.ROSTER, vm.now.step)
        assertTrue(vm.now.splits.isEmpty())
    }

    @Test
    fun selectSplit_withoutEditsJustChooses() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.selectSplit(2)
        assertEquals(2, vm.now.selectedSplit)
        assertNull(vm.now.discardPrompt)
    }

    // ---- Tap-to-move ------------------------------------------------------------------------

    @Test
    fun move_makesAnEditedCopyAndRecomputesTheMeterAtOnce() = runTest(mainDispatcher) {
        val vm = generated(10)
        val proposal = vm.now.splits[0]
        val mover = proposal.teamA.first()

        vm.movePlayer(mover)

        val copy = vm.now.workingCopy!!
        assertEquals(Selection.EDITED, copy.mode)
        assertTrue(mover in copy.teamB)
        assertEquals(4, copy.teamA.size)
        // The proposal itself is untouched.
        assertTrue(mover in vm.now.splits[0].teamA)
        // Recomputed, with no waiting: a 4 v 6 split is worse than the best 5 v 5.
        val meter = vm.now.liveMeter!!
        assertTrue(meter.quality < proposal.meter.quality)
        assertNotEquals(proposal.meter.winProbabilityA, meter.winProbabilityA)
    }

    @Test
    fun discardDialog_cancelKeepsTheEditsAndDiscardChoosesTheOtherOption() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.movePlayer(vm.now.splits[0].teamA.first())
        val edits = vm.now.workingCopy

        vm.selectSplit(1)
        assertEquals(DiscardAction.SelectSplit(1), vm.now.discardPrompt)
        assertEquals(0, vm.now.selectedSplit)

        vm.dismissDiscard()
        assertNull(vm.now.discardPrompt)
        assertEquals(edits, vm.now.workingCopy)

        vm.selectSplit(1)
        vm.confirmDiscard()
        assertNull(vm.now.discardPrompt)
        assertNull(vm.now.workingCopy)
        assertNull(vm.now.liveMeter)
        assertEquals(1, vm.now.selectedSplit)
    }

    @Test
    fun regenerate_withEditsAsksFirst() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.movePlayer(vm.now.splits[0].teamA.first())

        vm.regenerate()
        assertEquals(DiscardAction.Regenerate, vm.now.discardPrompt)
        assertFalse(vm.now.generating)

        vm.confirmDiscard()
        assertTrue(vm.now.generating)
        advanceUntilIdle()
        assertNull(vm.now.workingCopy)
        assertEquals(3, vm.now.splits.size)
    }

    // ---- Locks ------------------------------------------------------------------------------

    @Test
    fun locks_applyOnRegenerate() = runTest(mainDispatcher) {
        val vm = generated(10)
        // Lock the way the screen does: a chip's lock pins the player to the side they're on.
        val bib = vm.now.splits[0].teamA.last()
        val nonBib = vm.now.splits[0].teamB.first()
        vm.toggleLock(bib, Side.A)
        vm.toggleLock(nonBib, Side.B)
        assertEquals(mapOf(bib to Side.A, nonBib to Side.B), vm.now.locks)

        vm.regenerate()
        advanceUntilIdle()
        assertEquals(3, vm.now.splits.size)
        vm.now.splits.forEach { split ->
            assertTrue(bib in split.teamA)
            assertTrue(nonBib in split.teamB)
        }
    }

    @Test
    fun locks_clearWhenGoingBackToTheRoster() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.toggleLock(vm.now.splits[0].teamA.first(), Side.A)
        vm.back()
        assertTrue(vm.now.locks.isEmpty())
    }

    @Test
    fun locks_clearAfterConfirm() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.toggleLock(vm.now.splits[0].teamA.first(), Side.A)
        vm.continueToConfirm()
        vm.confirm()
        assertTrue(vm.now.locks.isEmpty())
    }

    @Test
    fun locks_unlockedPlayersCantMakeGenerateFailAfterTicksChange() = runTest(mainDispatcher) {
        // Lock three to the Bibs with six playing (fine: 3 a side), then go back and
        // untick down to four. A leftover lock would make 3 on one side of 2 v 2 impossible.
        val vm = generated(6)
        vm.now.selectedIds.take(3).forEach { vm.toggleLock(it, Side.A) }
        vm.back()
        vm.now.selectedIds.drop(4).forEach { vm.toggleSelected(it) }

        vm.generate()
        advanceUntilIdle()
        assertNull(vm.now.problem)
        assertEquals(Step.GENERATE, vm.now.step)
    }

    @Test
    fun untick_dropsThatPlayersLock() = runTest(mainDispatcher) {
        val vm = generated(10)
        val locked = vm.now.splits[0].teamA.first()
        vm.toggleLock(locked, Side.A)
        vm.toggleSelected(locked)
        assertFalse(locked in vm.now.locks)
        // Ticked again, the old lock doesn't come back.
        vm.toggleSelected(locked)
        assertFalse(locked in vm.now.locks)
    }

    @Test
    fun move_clearsThatPlayersLock() = runTest(mainDispatcher) {
        val vm = generated(10)
        val mover = vm.now.splits[0].teamA.first()
        val other = vm.now.splits[0].teamB.first()
        vm.toggleLock(mover, Side.A)
        vm.toggleLock(other, Side.B)

        vm.movePlayer(mover)

        assertEquals(mapOf(other to Side.B), vm.now.locks)
    }

    @Test
    fun locks_thatCantBeMetShowTheEnginesReason() = runTest(mainDispatcher) {
        val vm = generated(4)
        val before = vm.now.splits
        vm.now.selectedIds.take(3).forEach { vm.toggleLock(it, Side.A) }

        vm.regenerate()
        advanceUntilIdle()

        assertEquals(R.string.problem_locks, vm.now.problem)
        assertEquals(Step.GENERATE, vm.now.step)
        assertEquals(before, vm.now.splits)
    }

    // ---- Manual board ---------------------------------------------------------------------

    @Test
    fun manual_cyclesAndOnlyContinuesWhenThePoolIsEmpty() = runTest(mainDispatcher) {
        val vm = viewModel(4)
        vm.makeTeamsManually()
        assertEquals(Step.MANUAL, vm.now.step)
        val ids = vm.now.selectedIds.toList()
        assertEquals(ids, vm.now.workingCopy!!.pool)
        assertFalse(vm.now.canContinue)

        vm.movePlayer(ids[0]) // → Bibs
        assertNull("No meter until both sides have someone", vm.now.liveMeter)
        vm.movePlayer(ids[1]); vm.movePlayer(ids[1]) // → Non-bibs
        assertNotNull(vm.now.liveMeter)
        assertFalse("Two still in the pool", vm.now.canContinue)

        vm.movePlayer(ids[2])
        vm.movePlayer(ids[3]); vm.movePlayer(ids[3])
        assertTrue(vm.now.canContinue)

        vm.movePlayer(ids[3]) // Non-bibs → back to the pool
        assertEquals(listOf(ids[3]), vm.now.workingCopy!!.pool)
        assertFalse(vm.now.canContinue)

        vm.continueToConfirm()
        assertEquals("Continue is ignored while it's disabled", Step.MANUAL, vm.now.step)
    }

    // ---- Confirm --------------------------------------------------------------------------

    @Test
    fun confirm_unevenManualTeamsWarnButStillConfirm() = runTest(mainDispatcher) {
        val vm = viewModel(10)
        vm.makeTeamsManually()
        val ids = vm.now.selectedIds.toList()
        ids.take(6).forEach { vm.movePlayer(it) }
        ids.drop(6).forEach { vm.movePlayer(it); vm.movePlayer(it) }
        vm.continueToConfirm()

        val chosen = vm.now.chosen!!
        assertEquals(Step.CONFIRM, vm.now.step)
        assertEquals(Selection.MANUAL, chosen.selection)
        assertEquals(SizeGap(6, 4), sizeWarning(chosen.teamA.size, chosen.teamB.size))

        // Back returns to the board with the teams intact.
        vm.back()
        assertEquals(Step.MANUAL, vm.now.step)
        assertEquals(chosen.teamA, vm.now.workingCopy!!.teamA)

        vm.continueToConfirm()
        vm.confirm()
        assertEquals(Step.ROSTER, vm.now.step)
        assertEquals(R.string.notice_saving_next_milestone, vm.now.notice)
        assertNull(vm.now.workingCopy)
        vm.noticeShown()
        assertNull(vm.now.notice)
    }

    @Test
    fun confirm_carriesProvenance() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.continueToConfirm()
        assertEquals(Selection.GENERATED, vm.now.chosen!!.selection)
        assertNull(sizeWarning(vm.now.chosen!!.teamA.size, vm.now.chosen!!.teamB.size))

        vm.back()
        assertEquals(Step.GENERATE, vm.now.step)
        vm.movePlayer(vm.now.splits[0].teamB.first())
        vm.continueToConfirm()
        assertEquals(Selection.EDITED, vm.now.chosen!!.selection)
    }

    @Test
    fun back_fromGenerateDropsEditsAndReturnsToTheRoster() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.movePlayer(vm.now.splits[0].teamA.first())
        vm.back()
        assertEquals(Step.ROSTER, vm.now.step)
        assertNull(vm.now.workingCopy)
        assertEquals(10, vm.now.selectedIds.size)
    }

    // ---- Taps while the balancer is running ---------------------------------------------

    @Test
    fun continue_whileRegeneratingIsIgnored() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.regenerate()
        assertTrue(vm.now.generating)
        assertFalse(vm.now.canContinue)

        vm.continueToConfirm()
        assertEquals(Step.GENERATE, vm.now.step)
        assertNull(vm.now.chosen)

        advanceUntilIdle()
        assertEquals(Step.GENERATE, vm.now.step)
        assertTrue(vm.now.canContinue)
    }

    @Test
    fun moveSelectAndLock_whileRegeneratingAreIgnored() = runTest(mainDispatcher) {
        val vm = generated(10)
        val player = vm.now.splits[0].teamA.first()
        vm.regenerate()

        vm.movePlayer(player)
        vm.selectSplit(1)
        vm.toggleLock(player, Side.A)

        assertNull(vm.now.workingCopy)
        assertEquals(0, vm.now.selectedSplit)
        assertTrue(vm.now.locks.isEmpty())
        assertNull(vm.now.discardPrompt)
    }

    @Test
    fun addPlayer_whileGeneratingCancelsTheRunSoTheNewPlayerIsIncluded() = runTest(mainDispatcher) {
        val vm = viewModel(9)
        vm.generate()
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()
        assertFalse(vm.now.generating)
        advanceUntilIdle()
        assertEquals("The stale run must not arrive", Step.ROSTER, vm.now.step)

        vm.generate()
        advanceUntilIdle()
        val newId = vm.now.players.single { it.name == "Aurelio" }.id
        vm.now.splits.forEach { assertTrue(newId in it.teamA + it.teamB) }
    }

    // ---- Guards -----------------------------------------------------------------------------

    @Test
    fun makeTeamsManually_needsTwoPlayers() {
        val vm = viewModel(1)
        vm.makeTeamsManually()
        assertEquals(Step.ROSTER, vm.now.step)
        assertNull(vm.now.workingCopy)
    }

    @Test
    fun move_ofSomeoneNotInTheSplitChangesNothing() = runTest(mainDispatcher) {
        val vm = generated(10)
        val unticked = players.last().id
        vm.movePlayer(unticked)
        vm.movePlayer("nobody")
        assertNull("No EDITED badge for a move that didn't happen", vm.now.workingCopy)
    }

    @Test
    fun back_closesTheDiscardDialog() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.movePlayer(vm.now.splits[0].teamA.first())
        vm.selectSplit(1)
        assertNotNull(vm.now.discardPrompt)

        vm.back()
        assertNull(vm.now.discardPrompt)
    }

    @Test
    fun continue_closesTheDiscardDialog() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.movePlayer(vm.now.splits[0].teamA.first())
        vm.regenerate()
        assertNotNull(vm.now.discardPrompt)

        vm.continueToConfirm()
        assertEquals(Step.CONFIRM, vm.now.step)
        assertNull(vm.now.discardPrompt)
    }

    @Test
    fun addPlayer_neverReusesAnId() {
        // Two players whose ids leave a gap: counting alone would give "p03" again.
        val gappy = listOf(players[0].copy(id = "p01"), players[1].copy(id = "p03"))
        val vm = MatchDayViewModel(gappy, balancerDispatcher)
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()

        val ids = vm.now.players.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertEquals("p04", vm.now.players.single { it.name == "Aurelio" }.id)
    }

    @Test
    fun back_fromGenerateLeavesNothingToContinueWith() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.back()

        assertTrue(vm.now.splits.isEmpty())
        assertEquals(0, vm.now.selectedSplit)
        assertFalse(vm.now.canContinue)
        vm.continueToConfirm()
        assertEquals(Step.ROSTER, vm.now.step)
        assertNull(vm.now.chosen)
    }

    @Test
    fun makeTeamsManually_dropsOldProposals() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.selectSplit(1)
        assertEquals(1, vm.now.selectedSplit)

        vm.makeTeamsManually()
        assertEquals(Step.MANUAL, vm.now.step)
        assertTrue(vm.now.splits.isEmpty())
        assertEquals(0, vm.now.selectedSplit)
    }
}
