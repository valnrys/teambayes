package io.github.valnrys.teambayes.matchday

import androidx.lifecycle.SavedStateHandle
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.FinalGame
import io.github.valnrys.teambayes.data.PlayerGoals
import io.github.valnrys.teambayes.data.Team
import io.github.valnrys.teambayes.data.Score
import io.github.valnrys.teambayes.data.PendingGame
import io.github.valnrys.teambayes.data.RatingsRepository
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.data.StoredPlayer
import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.engine.Prior
import io.github.valnrys.teambayes.engine.Rating
import io.github.valnrys.teambayes.engine.Side
import io.github.valnrys.teambayes.fakes.InMemoryGameRepository
import io.github.valnrys.teambayes.fakes.InMemoryPlayerRepository
import io.github.valnrys.teambayes.fakes.storedFrom
import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * Storage is replaced by in-memory repositories (see `fakes/`) holding the sample
 * players, so each test starts with fourteen stored players and no games.
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

    /** 17 November 2026, 9am in London. */
    private val clock = Clock.fixed(Instant.parse("2026-11-17T09:00:00Z"), ZoneId.of("Europe/London"))
    private val today = LocalDate.of(2026, 11, 17)

    private val playerRepository = InMemoryPlayerRepository(players.map { storedFrom(it) })
    private val gameRepository = InMemoryGameRepository()
    private val savedState = SavedStateHandle()

    /**
     * A ViewModel on the in-memory repositories. Everything but the balancer runs at once,
     * so the roster has loaded by the time this returns.
     */
    private fun newViewModel(
        players: InMemoryPlayerRepository = playerRepository,
        games: InMemoryGameRepository = gameRepository,
        handle: SavedStateHandle = savedState,
    ) = MatchDayViewModel(
        playerRepository = players,
        gameRepository = games,
        ratingsRepository = RatingsRepository(players, games, dispatcher = mainDispatcher),
        savedStateHandle = handle,
        generateDispatcher = balancerDispatcher,
        clock = clock,
    )

    /** A ViewModel with the first [count] sample players ticked. */
    private fun viewModel(count: Int) = newViewModel().apply {
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
        val vm = newViewModel()
        assertFalse(vm.now.loading)
        assertEquals(players.map { it.name }.sorted(), vm.now.players.map { it.name })
        assertTrue(vm.now.selectedIds.isEmpty())
        assertFalse(vm.now.canMakeTeams)
        assertEquals(today, vm.now.playDate)
    }

    @Test
    fun roster_leavesOutInactivePlayers() {
        val retired = StoredPlayer("old", "Retired Rita", active = false, startingMu = 25.0, createdAt = 0)
        val vm = newViewModel(players = InMemoryPlayerRepository(players.map { storedFrom(it) } + retired))
        assertFalse(vm.now.players.any { it.id == "old" })
        assertEquals(players.size, vm.now.players.size)
    }

    @Test
    fun roster_usesStartingRatingsUntilGamesAreFinalised() {
        val vm = newViewModel()
        players.forEach { assertEquals(it.rating, vm.now.ratingsById.getValue(it.id)) }
    }

    @Test
    fun roster_usesRatingsReplayedFromFinalisedGames() {
        // The best two beat the worst two: the winners' mu goes up, the losers' down.
        val (a1, a2) = players.take(2).map { it.id }
        val (b1, b2) = players.takeLast(2).map { it.id }
        val game = FinalGame(
            id = "g1", playedAt = 0, createdAt = 0, selection = Selection.GENERATED,
            outcome = Outcome.A_WINS, scoreA = 3, scoreB = 1,
            teamA = listOf(PlayerGoals(a1, 0), PlayerGoals(a2, 0)),
            teamB = listOf(PlayerGoals(b1, 0), PlayerGoals(b2, 0)),
        )
        val vm = newViewModel(games = InMemoryGameRepository(listOf(game)))

        val before = players.associate { it.id to it.rating }
        val after = vm.now.ratingsById
        assertTrue(after.getValue(a1).mu > before.getValue(a1).mu)
        assertTrue(after.getValue(b1).mu < before.getValue(b1).mu)
        assertTrue(after.getValue(a1).sigma < EngineConfig.SIGMA0)
        // Someone who didn't play keeps their starting rating.
        val bench = players[5].id
        assertEquals(before.getValue(bench), after.getValue(bench))
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
    fun addPlayer_opensTheSheetInTheMiddleAndSavesNothingYet() {
        val vm = viewModel(0)
        vm.setNewPlayerName("  Aurelio ")
        vm.addPlayer()

        assertEquals(NewPlayerPlacement("Aurelio", position = players.size / 2), vm.now.placing)
        assertTrue(playerRepository.added.isEmpty())
        assertEquals(players.size, vm.now.players.size)
    }

    @Test
    fun placementRanking_isBestFirstByCurrentMu() {
        val vm = viewModel(0)
        // The sample players are made best first, and nobody has played yet.
        assertEquals(players.map { it.id }, vm.now.ranking.map { it.id })
        val mus = vm.now.ranking.map { it.rating.mu }
        assertEquals(mus.sortedDescending(), mus)
    }

    @Test
    fun saveNewPlayer_storesTheMidpointOfTheNeighboursMuAndTicksThem() {
        val vm = viewModel(0)
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()
        vm.moveNewPlayer(3) // between the 3rd and 4th best
        vm.saveNewPlayer()

        val ranking = players // best first
        val expected = Prior.insertBetween(ranking[2].rating.mu, ranking[3].rating.mu)
        assertEquals(listOf("Aurelio" to expected), playerRepository.added)
        assertEquals((ranking[2].rating.mu + ranking[3].rating.mu) / 2, expected, 1e-12)

        val added = vm.now.players.single { it.name == "Aurelio" }
        assertEquals(setOf(added.id), vm.now.selectedIds)
        assertEquals(Rating(expected, EngineConfig.SIGMA0), added.rating)
        assertNull(vm.now.placing)
        assertEquals("", vm.now.newPlayerName)
        assertEquals("Aurelio", vm.now.players.first().name) // still A to Z
    }

    @Test
    fun saveNewPlayer_atTheTopOrBottomStepsOneBetaPastTheEnd() {
        val vm = viewModel(0)
        vm.setNewPlayerName("Top")
        vm.addPlayer()
        vm.moveNewPlayer(-5) // pulled back to the top
        assertEquals(0, vm.now.placing!!.position)
        vm.saveNewPlayer()

        vm.setNewPlayerName("Bottom")
        vm.addPlayer()
        vm.moveNewPlayer(999) // pulled back to the bottom
        vm.saveNewPlayer()

        val best = players.first().rating.mu
        val worst = players.last().rating.mu
        assertEquals(
            listOf("Top" to best + EngineConfig.BETA, "Bottom" to worst - EngineConfig.BETA),
            playerRepository.added,
        )
    }

    @Test
    fun startingMuAt_usesNeighboursFromTheMuOrderNotTheNameOrder() {
        // Alphabetical order would put Zed (high mu) below Amy (low mu): insertBetween would refuse.
        val amy = Player("a", "Amy", Rating(20.0, EngineConfig.SIGMA0))
        val zed = Player("z", "Zed", Rating(30.0, EngineConfig.SIGMA0))
        val state = MatchDayUiState(players = listOf(amy, zed))
        assertEquals(listOf(zed, amy), state.ranking)
        assertEquals(25.0, startingMuAt(state.ranking, 1), 1e-12)
    }

    @Test
    fun saveNewPlayer_beforeStorageReportsThemTheyCanAlreadyPlay() = runTest(mainDispatcher) {
        val slow = InMemoryPlayerRepository(players.map { storedFrom(it) }, reportAddsAtOnce = false)
        val vm = newViewModel(players = slow)
        players.take(3).forEach { vm.toggleSelected(it.id) }
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()
        vm.saveNewPlayer()

        // Stored, but the flow hasn't emitted yet: the roster shows them anyway.
        val added = vm.now.players.single { it.name == "Aurelio" }
        assertTrue(added.id in vm.now.selectedIds)
        assertEquals("Aurelio", vm.now.namesById[added.id])

        // Make teams manually used to crash here, looking up a name it didn't have.
        vm.makeTeamsManually()
        assertTrue(added.id in vm.now.workingCopy!!.pool)
        vm.back()

        // Generate includes them.
        vm.generate()
        advanceUntilIdle()
        vm.now.splits.forEach { assertTrue(added.id in it.teamA + it.teamB) }

        // The same name again is refused before storage reports the first.
        vm.setNewPlayerName("aurelio")
        vm.addPlayer()
        assertEquals("aurelio", vm.now.duplicateName)

        // When storage catches up, they're listed once, with the same id.
        slow.reportAdds()
        assertEquals(added, vm.now.players.single { it.name == "Aurelio" })
        assertEquals(players.size + 1, vm.now.players.size)
    }

    @Test
    fun saveNewPlayer_secondTapWhileSavingDoesNothing() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        playerRepository.gate = gate
        val vm = viewModel(0)
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()

        vm.saveNewPlayer()
        assertTrue(vm.now.placing!!.saving)
        vm.saveNewPlayer() // the double tap
        gate.complete(Unit)

        assertEquals(listOf("Aurelio"), playerRepository.added.map { it.first })
        assertNull("No 'already exists' from the second tap", vm.now.duplicateName)
        assertNull(vm.now.placing)
        assertEquals(1, vm.now.players.count { it.name == "Aurelio" })
    }

    @Test
    fun saveNewPlayer_whenStorageReportsThemFirstTheyAreListedOnce() = runTest(mainDispatcher) {
        // The default fake's flow emits inside addPlayer, before it returns its id.
        val vm = viewModel(0)
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()
        vm.saveNewPlayer()
        assertEquals(1, vm.now.players.count { it.name == "Aurelio" })

        // Another emission (the next player) mustn't bring back a second copy.
        vm.setNewPlayerName("Beatrix")
        vm.addPlayer()
        vm.saveNewPlayer()
        assertEquals(1, vm.now.players.count { it.name == "Aurelio" })
        assertEquals(1, vm.now.players.count { it.name == "Beatrix" })
        assertEquals(players.size + 2, vm.now.players.size)
    }

    @Test
    fun cancelNewPlayer_closesTheSheetAndKeepsTheName() {
        val vm = viewModel(0)
        vm.setNewPlayerName("Aurelio")
        vm.addPlayer()
        vm.cancelNewPlayer()

        assertNull(vm.now.placing)
        assertEquals("Aurelio", vm.now.newPlayerName)
        assertTrue(playerRepository.added.isEmpty())
    }

    @Test
    fun addPlayer_refusesADuplicateIgnoringCase() {
        val vm = viewModel(0)
        vm.setNewPlayerName("tamsin")
        vm.addPlayer()

        assertEquals("tamsin", vm.now.duplicateName)
        assertNull("No sheet for a name that's taken", vm.now.placing)
        assertEquals(players.size, vm.now.players.size)
        assertTrue(vm.now.selectedIds.isEmpty())
        // Typing again clears the message.
        vm.setNewPlayerName("tamsin2")
        assertNull(vm.now.duplicateName)
    }

    @Test
    fun addPlayer_comparesNamesAsStorageDoes() {
        val sam = StoredPlayer("s", "Sam Smith", active = true, startingMu = 25.0, createdAt = 0)
        val vm = newViewModel(players = InMemoryPlayerRepository(listOf(sam)))
        vm.setNewPlayerName("  sam   SMITH ")
        vm.addPlayer()
        assertEquals("sam SMITH", vm.now.duplicateName)
        assertNull(vm.now.placing)
    }

    @Test
    fun addPlayer_refusesTheNameOfAnInactivePlayer() {
        val retired = StoredPlayer("old", "Rita", active = false, startingMu = 25.0, createdAt = 0)
        val vm = newViewModel(players = InMemoryPlayerRepository(listOf(retired)))
        vm.setNewPlayerName("RITA")
        vm.addPlayer()
        assertEquals("RITA", vm.now.duplicateName)
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
        assertEquals(Step.RECORD, vm.now.step)
        assertNull(vm.now.notice)
        assertNull(vm.now.workingCopy)
        assertEquals(10, vm.now.selectedIds.size)
    }

    @Test
    fun confirm_savesThePendingGameWithItsProvenanceAndToday() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.movePlayer(vm.now.splits[0].teamA.first())
        vm.continueToConfirm()
        val chosen = vm.now.chosen!!

        vm.confirm()

        // Noon on 17 November in London (GMT, so UTC+0).
        val noon = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli()
        assertEquals(
            listOf(InMemoryGameRepository.SavedPending(noon, chosen.teamA, chosen.teamB, Selection.EDITED)),
            gameRepository.savedPending,
        )
    }

    @Test
    fun confirm_savesGeneratedAndManualProvenance() = runTest(mainDispatcher) {
        val vm = generated(4)
        vm.continueToConfirm()
        vm.confirm()
        assertEquals(Selection.GENERATED, gameRepository.savedPending.single().selection)

        // A second match day, on storage with no pending game yet.
        val freshGames = InMemoryGameRepository()
        val manual = newViewModel(games = freshGames, handle = SavedStateHandle())
        manual.toggleSelected(players[0].id)
        manual.toggleSelected(players[1].id)
        manual.makeTeamsManually()
        manual.movePlayer(players[0].id) // → Bibs
        manual.movePlayer(players[1].id); manual.movePlayer(players[1].id) // → Non-bibs
        manual.continueToConfirm()
        manual.confirm()
        assertEquals(Selection.MANUAL, freshGames.savedPending.single().selection)
    }

    @Test
    fun confirm_storesAPickedDateAtLocalNoonAndThenResetsToToday() = runTest(mainDispatcher) {
        val vm = generated(4)
        vm.continueToConfirm()
        // A summer date: London is on BST (UTC+1), so local noon is 11:00 UTC.
        vm.setPlayDate(LocalDate.of(2026, 7, 4))
        vm.confirm()

        assertEquals(Instant.parse("2026-07-04T11:00:00Z").toEpochMilli(), gameRepository.savedPending.single().playedAt)
        assertEquals(today, vm.now.playDate)
    }

    @Test
    fun setPlayDate_ignoresAFutureDay() = runTest(mainDispatcher) {
        val vm = generated(4)
        vm.continueToConfirm()
        vm.setPlayDate(today.plusDays(1))
        assertEquals(today, vm.now.playDate)
        vm.setPlayDate(today.minusDays(1))
        assertEquals(today.minusDays(1), vm.now.playDate)
    }

    @Test
    fun whileConfirmIsSaving_backAndTheDateAreIgnored() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        gameRepository.gate = gate
        val vm = generated(4)
        vm.continueToConfirm()
        val chosen = vm.now.chosen

        vm.confirm()
        assertTrue(vm.now.saving)
        vm.back() // the system Back gesture
        vm.setPlayDate(today.minusDays(3)) // a picker that was already open
        assertEquals(Step.CONFIRM, vm.now.step)
        assertEquals(chosen, vm.now.chosen)
        assertEquals(today, vm.now.playDate)

        gate.complete(Unit)
        assertEquals(Step.RECORD, vm.now.step)
        assertEquals(today.toPlayedAt(clock.zone), gameRepository.savedPending.single().playedAt)
    }

    @Test
    fun toPlayedAt_isLocalNoonWhateverTheZone() {
        val day = LocalDate.of(2026, 3, 29) // the day the UK's clocks go forward
        assertEquals(Instant.parse("2026-03-29T11:00:00Z").toEpochMilli(), day.toPlayedAt(ZoneId.of("Europe/London")))
        assertEquals(Instant.parse("2026-03-29T16:00:00Z").toEpochMilli(), day.toPlayedAt(ZoneId.of("America/New_York")))
    }

    @Test
    fun confirm_withAnUnfinishedGameAlreadyStoredSaysSoAndKeepsTheTeams() = runTest(mainDispatcher) {
        gameRepository.savePending(0, listOf(players[0].id), listOf(players[1].id), Selection.MANUAL)
        val vm = generated(4)
        vm.continueToConfirm()
        val chosen = vm.now.chosen

        vm.confirm()

        assertEquals(R.string.notice_pending_game_exists, vm.now.notice)
        assertEquals(Step.CONFIRM, vm.now.step)
        assertEquals(chosen, vm.now.chosen)
        assertFalse(vm.now.saving)
        assertEquals(1, gameRepository.savedPending.size) // only the one stored before
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
        vm.saveNewPlayer()
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

    // ---- Process death ----------------------------------------------------------------------

    @Test
    fun processDeath_restoresTheEditedProposalLocksAndTicks() = runTest(mainDispatcher) {
        val vm = generated(10)
        vm.selectSplit(1)
        val split = vm.now.splits[1]
        val locked = split.teamB.last()
        vm.toggleLock(locked, Side.B)
        vm.movePlayer(split.teamA.first())
        val before = vm.now

        // A new ViewModel on the same saved state is what Android builds after process death.
        val restored = newViewModel().now

        assertEquals(Step.GENERATE, restored.step)
        assertEquals(before.selectedIds, restored.selectedIds)
        assertEquals(before.splits, restored.splits) // meters worked out again, to the same values
        assertEquals(1, restored.selectedSplit)
        assertEquals(before.locks, restored.locks)
        assertEquals(before.workingCopy, restored.workingCopy)
        assertEquals(before.liveMeter, restored.liveMeter)
    }

    @Test
    fun processDeath_restoresTheManualBoard() = runTest(mainDispatcher) {
        val vm = viewModel(4)
        vm.makeTeamsManually()
        vm.movePlayer(players[0].id)
        vm.movePlayer(players[1].id); vm.movePlayer(players[1].id)

        val restored = newViewModel().now

        assertEquals(Step.MANUAL, restored.step)
        assertEquals(vm.now.workingCopy, restored.workingCopy)
        assertEquals(listOf(players[2].id, players[3].id), restored.workingCopy!!.pool)
        assertNotNull(restored.liveMeter)
    }

    @Test
    fun processDeath_restoresTheConfirmStepAndItsDate() = runTest(mainDispatcher) {
        val vm = generated(4)
        vm.continueToConfirm()
        vm.setPlayDate(LocalDate.of(2026, 11, 10))

        val restored = newViewModel()

        assertEquals(Step.CONFIRM, restored.now.step)
        assertEquals(vm.now.chosen, restored.now.chosen)
        assertEquals(LocalDate.of(2026, 11, 10), restored.now.playDate)
        // And it still saves.
        restored.confirm()
        assertEquals(Selection.GENERATED, gameRepository.savedPending.single().selection)
    }

    @Test
    fun processDeath_withAPlayerWhoIsNoLongerThereFallsBackToTheRoster() = runTest(mainDispatcher) {
        generated(4).continueToConfirm()
        // The same saved state, but storage no longer has the best player.
        val fewer = InMemoryPlayerRepository(players.drop(1).map { storedFrom(it) })

        val restored = newViewModel(players = fewer).now

        assertEquals(Step.ROSTER, restored.step)
        assertNull(restored.chosen)
        assertEquals(players.drop(1).take(3).map { it.id }.toSet(), restored.selectedIds)
    }

    @Test
    fun freshStart_withNothingSavedIsAnEmptyRoster() {
        val vm = newViewModel(handle = SavedStateHandle())
        assertEquals(Step.ROSTER, vm.now.step)
        assertTrue(vm.now.selectedIds.isEmpty())
    }

    @Test
    fun restore_confirmWithoutTheBoardItReturnsToFallsBackToTheRoster() {
        val ids = players.take(4).map { it.id }
        val chosen = ChosenTeams(ids.take(2), ids.drop(2), Selection.EDITED)
        val confirmOnly = SavedMatchDay(
            step = Step.CONFIRM, selectedIds = ids, splits = emptyList(), sampled = false, selectedSplit = 0,
            locks = emptyMap(), workingCopy = null, chosen = chosen, playDate = today, search = "", newPlayerName = "",
        )
        assertEquals(Step.ROSTER, confirmOnly.restore(players).step)
        assertEquals(Step.ROSTER, confirmOnly.copy(chosen = chosen.copy(selection = Selection.MANUAL)).restore(players).step)
        assertEquals(Step.ROSTER, confirmOnly.copy(chosen = chosen.copy(selection = Selection.GENERATED)).restore(players).step)

        // With the board Back returns to, it comes back on the confirm step.
        val board = WorkingCopy(Selection.MANUAL, ids.take(2), ids.drop(2))
        val manual = confirmOnly.copy(chosen = chosen.copy(selection = Selection.MANUAL), workingCopy = board)
        assertEquals(Step.CONFIRM, manual.restore(players).step)
    }

    // ---- Record result --------------------------------------------------------------------

    /** A ViewModel on the record step of a 2 v 2 game it just confirmed by hand. */
    private fun TestScope.recording(): MatchDayViewModel = viewModel(4).apply {
        makeTeamsManually()
        movePlayer(players[0].id); movePlayer(players[1].id) // → Bibs
        movePlayer(players[2].id); movePlayer(players[2].id) // → Non-bibs
        movePlayer(players[3].id); movePlayer(players[3].id)
        continueToConfirm()
        confirm()
    }

    private val bib get() = players[0].id
    private val otherBib get() = players[1].id
    private val nonBib get() = players[2].id

    @Test
    fun confirm_goesStraightToRecordWithTheSavedTeamsAndNoGoals() = runTest(mainDispatcher) {
        val vm = recording()
        val recording = vm.now.recording!!
        assertEquals(Step.RECORD, vm.now.step)
        assertEquals(gameRepository.pendingNow!!.id, recording.gameId)
        assertEquals(listOf(bib, otherBib), recording.teamA)
        assertEquals(listOf(nonBib, players[3].id), recording.teamB)
        assertTrue(recording.goals.isEmpty())
        assertFalse(recording.adjusted)
        assertFalse("A game on record isn't offered as unfinished", vm.now.showPendingCard)
    }

    @Test
    fun goals_areStoredOnEveryTapAndNeverGoBelowZero() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.changeGoals(bib, +1)
        vm.changeGoals(nonBib, +1)
        vm.changeGoals(nonBib, -1)
        vm.changeGoals(nonBib, -1) // already 0
        vm.changeGoals("stranger", +1) // not in this game

        assertEquals(mapOf(bib to 2, nonBib to 0), vm.now.recording!!.goals)
        val stored = gameRepository.pendingNow!!
        assertEquals(2, stored.teamA.single { it.playerId == bib }.goals)
        assertEquals(0, stored.teamB.single { it.playerId == nonBib }.goals)
    }

    @Test
    fun result_followsTheGoals() = runTest(mainDispatcher) {
        val vm = recording()
        assertEquals(MatchResult(0, 0, Outcome.DRAW), vm.now.recording!!.result)
        vm.changeGoals(bib, +1)
        vm.changeGoals(otherBib, +1)
        vm.changeGoals(nonBib, +1)
        assertEquals(MatchResult(2, 1, Outcome.A_WINS), vm.now.recording!!.result)
    }

    @Test
    fun finalise_isBlockedUntilAGoalIsTallied() = runTest(mainDispatcher) {
        val vm = recording()
        assertFalse(vm.now.recording!!.canFinalise)
        vm.finalise()
        assertEquals(Step.RECORD, vm.now.step)
        assertTrue(gameRepository.finalised.isEmpty())

        vm.changeGoals(nonBib, +1)
        assertTrue(vm.now.recording!!.canFinalise)
    }

    @Test
    fun adjustScore_seedsTheTallyStoresTheTypedScoreAndDecidesTheWinner() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.changeGoals(bib, +1)

        vm.toggleAdjustScore()
        var recording = vm.now.recording!!
        assertTrue(recording.adjusted)
        assertEquals("2" to "0", recording.typedA to recording.typedB)
        assertEquals(Score(2, 0), gameRepository.pendingNow!!.scoreOverride)

        // An own goal for the Non-bibs, twice: 2–2.
        vm.setTypedScore(Team.B, "2x")
        recording = vm.now.recording!!
        assertEquals("2", recording.typedB)
        assertEquals(MatchResult(2, 2, Outcome.DRAW), recording.result)
        assertEquals(Score(2, 2), gameRepository.pendingNow!!.scoreOverride)

        // Off again: back to the tally, and the stored override is cleared.
        vm.toggleAdjustScore()
        assertEquals(MatchResult(2, 0, Outcome.A_WINS), vm.now.recording!!.result)
        assertNull(gameRepository.pendingNow!!.scoreOverride)
    }

    @Test
    fun adjustScore_allowsATyped0to0() = runTest(mainDispatcher) {
        val vm = recording()
        vm.toggleAdjustScore()
        assertTrue(vm.now.recording!!.canFinalise)
        vm.finalise()
        assertEquals(listOf(Triple(Outcome.DRAW, 0, 0)), gameRepository.finalised)
    }

    @Test
    fun typedScore_isIgnoredUnlessAdjusting() = runTest(mainDispatcher) {
        val vm = recording()
        vm.setTypedScore(Team.A, "5")
        assertEquals("", vm.now.recording!!.typedA)
        assertNull(gameRepository.pendingNow!!.scoreOverride)
    }

    @Test
    fun finalise_storesTheResultAndShowsTheMovement() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(nonBib, +1)
        vm.finalise()

        assertEquals(listOf(Triple(Outcome.B_WINS, 0, 1)), gameRepository.finalised)
        assertNull(gameRepository.pendingNow)
        assertEquals(Step.DONE, vm.now.step)
        val finished = vm.now.finished!!
        assertEquals(MatchResult(0, 1, Outcome.B_WINS), finished.result)
        val movements = finished.movements
        assertEquals(4, movements.size)
        assertEquals(movements.map { it.change }.sortedDescending(), movements.map { it.change })
        // The winners went up, the losers down.
        val byId = movements.associate { it.playerId to it.change }
        assertTrue(byId.getValue(nonBib) > 0)
        assertTrue(byId.getValue(bib) < 0)
    }

    @Test
    fun finalise_thatFailsStaysOnRecordWithAMessage() = runTest(mainDispatcher) {
        gameRepository.failFinalise = true
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.finalise()
        assertEquals(Step.RECORD, vm.now.step)
        assertFalse(vm.now.recording!!.finalising)
        assertEquals(R.string.notice_finalise_failed, vm.now.notice)
    }

    @Test
    fun startAnotherMatchDay_isAnEmptyRoster() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.finalise()
        vm.startAnotherMatchDay()

        assertEquals(Step.ROSTER, vm.now.step)
        assertTrue(vm.now.selectedIds.isEmpty())
        assertNull(vm.now.finished)
        assertNull(vm.now.recording)
        assertFalse(vm.now.showPendingCard)
    }

    @Test
    fun back_fromRecordKeepsTheGameAndOffersItOnTheRoster() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.back()

        assertEquals(Step.ROSTER, vm.now.step)
        assertTrue(vm.now.showPendingCard)
        assertEquals(1, gameRepository.pendingNow!!.teamA.single { it.playerId == bib }.goals)
    }

    @Test
    fun movementsFrom_isSkillAfterMinusSkillBeforeBiggestRiseFirst() {
        val sigma = EngineConfig.SIGMA0
        val changes = mapOf(
            "a" to (Rating(25.0, sigma) to Rating(26.0, sigma)), // +40
            "b" to (Rating(25.0, sigma) to Rating(25.0, sigma)), // 0
            "c" to (Rating(25.0, sigma) to Rating(24.5, sigma)), // -20
            "d" to (Rating(25.0, sigma) to Rating(25.0, sigma - 0.25)), // +20: less doubt
        )
        val movements = movementsFrom(changes, mapOf("a" to "Ann", "b" to "Bo", "c" to "Cy", "d" to "Di"))
        assertEquals(listOf("a" to 40, "d" to 20, "b" to 0, "c" to -20), movements.map { it.playerId to it.change })
    }

    // ---- Unfinished match day --------------------------------------------------------------

    /** A pending game from 10 November, Bibs [bib] (2 goals) and [otherBib] v the next two. */
    private fun seedPending(scoreOverride: Score? = null) = gameRepository.seedPending(
        PendingGame(
            id = "earlier",
            playedAt = LocalDate.of(2026, 11, 10).toPlayedAt(clock.zone),
            createdAt = 0,
            selection = Selection.GENERATED,
            teamA = listOf(PlayerGoals(players[0].id, 2), PlayerGoals(players[1].id, 0)),
            teamB = listOf(PlayerGoals(players[2].id, 1), PlayerGoals(players[3].id, 0)),
            scoreOverride = scoreOverride,
        ),
    )

    @Test
    fun pendingGame_showsTheCardWithItsDate() {
        seedPending()
        val vm = newViewModel()
        assertTrue(vm.now.showPendingCard)
        assertEquals(LocalDate.of(2026, 11, 10), vm.now.pendingGameDate)
    }

    @Test
    fun resume_opensRecordWithTheStoredGoalsAndAdjustedScore() {
        seedPending(scoreOverride = Score(3, 1))
        val vm = newViewModel()
        vm.resumeUnfinished()

        val recording = vm.now.recording!!
        assertEquals(Step.RECORD, vm.now.step)
        assertEquals("earlier", recording.gameId)
        assertEquals(2, recording.goals[players[0].id])
        assertEquals(1, recording.goals[players[2].id])
        assertTrue(recording.adjusted)
        assertEquals(MatchResult(3, 1, Outcome.A_WINS), recording.result)
    }

    @Test
    fun discard_asksFirstAndCancelKeepsTheGame() {
        seedPending()
        val vm = newViewModel()
        vm.discardUnfinished()
        assertTrue(vm.now.discardGamePrompt)
        assertNotNull("Nothing deleted before the answer", gameRepository.pendingNow)

        vm.keepUnfinished()
        assertFalse(vm.now.discardGamePrompt)
        assertNotNull(gameRepository.pendingNow)
        assertTrue(vm.now.showPendingCard)
    }

    @Test
    fun discard_confirmedDeletesTheGameAndClearsTheTicks() {
        seedPending()
        val vm = newViewModel()
        vm.toggleSelected(players[5].id)
        vm.discardUnfinished()
        vm.confirmDiscardUnfinished()

        assertNull(gameRepository.pendingNow)
        assertFalse(vm.now.showPendingCard)
        assertTrue(vm.now.selectedIds.isEmpty())
    }

    @Test
    fun confirmDiscardUnfinished_withoutTheDialogDoesNothing() {
        seedPending()
        val vm = newViewModel()
        vm.confirmDiscardUnfinished()
        assertNotNull(gameRepository.pendingNow)
    }

    // ---- Process death on the result steps ------------------------------------------------

    @Test
    fun processDeath_onRecordComesBackOnRecordWithEveryGoal() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.changeGoals(bib, +1)
        vm.toggleAdjustScore()
        vm.setTypedScore(Team.B, "") // cleared, so it reads 0 but shows blank

        val restored = newViewModel().now

        assertEquals(Step.RECORD, restored.step)
        val recording = restored.recording!!
        assertEquals(2, recording.goals[bib])
        assertTrue(recording.adjusted)
        assertEquals("2" to "", recording.typedA to recording.typedB)
        assertFalse(restored.showPendingCard)
    }

    @Test
    fun processDeath_onRecordWhenTheGameHasGoneLandsOnTheRoster() = runTest(mainDispatcher) {
        recording()
        gameRepository.discardPending()

        val restored = newViewModel().now

        assertEquals(Step.ROSTER, restored.step)
        assertNull(restored.recording)
    }

    @Test
    fun processDeath_onDoneComesBackOnDone() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.finalise()
        val finished = vm.now.finished

        val restored = newViewModel().now

        assertEquals(Step.DONE, restored.step)
        assertEquals(finished, restored.finished)
    }

    // ---- Taps racing storage -----------------------------------------------------------------

    @Test
    fun aFailedGoalWrite_isUndoneOnScreenAndSaid() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        gameRepository.failRecordWrites = true
        vm.changeGoals(bib, +1)

        assertEquals("The screen shows what's stored", 1, vm.now.recording!!.goals[bib])
        assertEquals(R.string.notice_record_save_failed, vm.now.notice)
    }

    @Test
    fun finalise_refusesWhenStorageDiffersFromTheScreen_thenSavesWhatsStoredOnTheNextTap() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1) // stored
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        gameRepository.failRecordWrites = true
        vm.changeGoals(bib, +1) // waiting, and will fail
        vm.finalise() // queued behind it, with 2–0 on screen

        gate.complete(Unit)

        assertTrue("Nothing finalised with a score storage doesn't have", gameRepository.finalised.isEmpty())
        assertEquals(Step.RECORD, vm.now.step)
        assertFalse(vm.now.recording!!.finalising)
        assertEquals(1, vm.now.recording!!.goals[bib])

        gameRepository.writeGate = null
        gameRepository.failRecordWrites = false
        vm.finalise()
        assertEquals(listOf(Triple(Outcome.A_WINS, 1, 0)), gameRepository.finalised)
    }

    @Test
    fun backThenResume_whileAGoalIsBeingStored_showsTheStoredCount() = runTest(mainDispatcher) {
        val vm = recording()
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        vm.changeGoals(bib, +1) // still being written
        vm.back()
        vm.resumeUnfinished() // waits for the write

        gate.complete(Unit)

        assertEquals(Step.RECORD, vm.now.step)
        assertEquals(1, vm.now.recording!!.goals[bib])
    }

    @Test
    fun discard_hidesTheCardAtOnceAndKeepsTicksMadeMeanwhile() = runTest(mainDispatcher) {
        seedPending()
        val vm = newViewModel()
        vm.toggleSelected(players[6].id)
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        vm.discardUnfinished()
        vm.confirmDiscardUnfinished()

        assertFalse("The card goes before the delete finishes", vm.now.showPendingCard)
        assertTrue("Ticks clear at once", vm.now.selectedIds.isEmpty())
        vm.toggleSelected(players[5].id) // while the delete runs
        vm.resumeUnfinished() // too late: the card has gone

        gate.complete(Unit)

        assertNull(gameRepository.pendingNow)
        assertEquals(setOf(players[5].id), vm.now.selectedIds)
        assertEquals(Step.ROSTER, vm.now.step)
        assertNull(vm.now.recording)
    }

    @Test
    fun discard_thatFailsBringsTheCardBackWithAMessage() {
        seedPending()
        gameRepository.failDiscard = true
        val vm = newViewModel()
        vm.discardUnfinished()
        vm.confirmDiscardUnfinished()

        assertNotNull(gameRepository.pendingNow)
        assertTrue(vm.now.showPendingCard)
        assertEquals(R.string.notice_discard_failed, vm.now.notice)
    }

    @Test
    fun confirm_waitsForADiscardStillRunning() = runTest(mainDispatcher) {
        seedPending()
        val vm = newViewModel()
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        vm.discardUnfinished()
        vm.confirmDiscardUnfinished() // still deleting
        players.take(4).forEach { vm.toggleSelected(it.id) }
        vm.generate()
        advanceUntilIdle()
        vm.continueToConfirm()
        vm.confirm() // must not meet the old game

        gate.complete(Unit)

        assertEquals(Step.RECORD, vm.now.step)
        assertEquals(1, gameRepository.savedPending.size)
        assertNull(vm.now.notice)
    }

    @Test
    fun setTypedScore_keepsOnlyAsciiDigits() = runTest(mainDispatcher) {
        val vm = recording()
        vm.toggleAdjustScore()
        vm.setTypedScore(Team.A, "٣4") // an Arabic-Indic three, then a 4
        assertEquals("4", vm.now.recording!!.typedA)
    }

    // ---- Finalise trusts storage, not the screen -------------------------------------------

    @Test
    fun finalise_whenAWriteFailedWithAnotherQueuedBehindIt_savesNothingAndShowsStorage() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1) // stored: 1–0
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        gameRepository.failNextRecordWrites = 1
        vm.changeGoals(bib, +1) // will fail
        vm.changeGoals(nonBib, +1) // queued behind it, will succeed
        vm.finalise() // the screen says 2–1

        gate.complete(Unit)

        assertTrue("2–1 isn't what's stored", gameRepository.finalised.isEmpty())
        assertEquals(R.string.notice_record_save_failed, vm.now.notice)
        val recording = vm.now.recording!!
        assertFalse(recording.finalising)
        assertEquals(MatchResult(1, 1, Outcome.DRAW), recording.result) // what storage holds

        gameRepository.writeGate = null
        vm.finalise()
        assertEquals(listOf(Triple(Outcome.DRAW, 1, 1)), gameRepository.finalised)
    }

    @Test
    fun finalise_whenAFailedWritesResyncAlsoFailed_stillSavesNothingWrong() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1) // stored: 1–0
        gameRepository.failNextRecordWrites = 1
        gameRepository.failNextReads = 1 // the resync after the failure can't read either
        vm.changeGoals(bib, +1)
        assertEquals("The screen is still wrong", 2, vm.now.recording!!.goals[bib])

        vm.finalise()

        assertTrue(gameRepository.finalised.isEmpty())
        assertEquals(1, vm.now.recording!!.goals[bib])
        vm.finalise()
        assertEquals(listOf(Triple(Outcome.A_WINS, 1, 0)), gameRepository.finalised)
    }

    @Test
    fun aFailedScoreWrite_showsTheStoredScoreNotTheTypedOne() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.toggleAdjustScore() // stored override 1–0
        gameRepository.failNextRecordWrites = 1
        vm.setTypedScore(Team.B, "3")
        assertEquals("0", vm.now.recording!!.typedB)
        assertEquals(MatchResult(1, 0, Outcome.A_WINS), vm.now.recording!!.result)

        // Switching adjust off fails too: the screen stays adjusted, as storage is.
        gameRepository.failNextRecordWrites = 1
        vm.toggleAdjustScore()
        assertTrue(vm.now.recording!!.adjusted)
        assertEquals(Score(1, 0), gameRepository.pendingNow!!.scoreOverride)
    }

    @Test
    fun backDiscardThenGenerate_beforeTheDiscardFinishes_staysOnGenerate() = runTest(mainDispatcher) {
        val vm = recording()
        vm.back()
        assertNull("Back leaves the record step behind", vm.now.recording)
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        vm.discardUnfinished()
        vm.confirmDiscardUnfinished()
        players.take(4).forEach { vm.toggleSelected(it.id) }
        vm.generate()
        advanceUntilIdle()
        assertEquals(Step.GENERATE, vm.now.step)

        gate.complete(Unit)

        assertEquals("The discard doesn't pull the organiser off Generate", Step.GENERATE, vm.now.step)
        assertNull(gameRepository.pendingNow)
        assertNull(vm.now.pendingGame)
        assertNull(vm.now.pendingGameDate)
    }

    @Test
    fun resume_isIgnoredWhileTheDiscardQuestionIsOpen() {
        seedPending()
        val vm = newViewModel()
        vm.discardUnfinished()
        vm.resumeUnfinished()
        assertEquals(Step.ROSTER, vm.now.step)
        assertNull(vm.now.recording)
    }

    @Test
    fun finalise_whileAdjusted_refusesIfAStoredGoalDiffersEvenThoughTheResultMatches() = runTest(mainDispatcher) {
        val vm = recording()
        vm.changeGoals(bib, +1)
        vm.toggleAdjustScore() // the score is now typed: 1–0
        gameRepository.failNextRecordWrites = 1
        val gate = CompletableDeferred<Unit>()
        gameRepository.writeGate = gate
        vm.changeGoals(otherBib, +1) // will fail; the typed 1–0 is unchanged
        vm.finalise()

        gate.complete(Unit)

        assertTrue("The stored goals aren't what was on screen", gameRepository.finalised.isEmpty())
        assertEquals(R.string.notice_record_save_failed, vm.now.notice)
        assertNull(vm.now.recording!!.goals[otherBib]?.takeIf { it > 0 })

        gameRepository.writeGate = null
        vm.finalise()
        assertEquals(listOf(Triple(Outcome.A_WINS, 1, 0)), gameRepository.finalised)
    }

    @Test
    fun today_comesFromTheClock() = runTest(mainDispatcher) {
        val vm = generated(4)
        vm.continueToConfirm()
        assertEquals(today, vm.now.today)
    }
}
