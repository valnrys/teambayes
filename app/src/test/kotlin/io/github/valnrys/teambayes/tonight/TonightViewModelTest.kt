package io.github.valnrys.teambayes.tonight

import io.github.valnrys.teambayes.R
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Tests for [TonightViewModel] that run on the laptop, with no phone needed.
 *
 * Two test dispatchers share one clock. The "main thread" one is unconfined, so the
 * ViewModel's coroutine starts at once. The "balancing thread" one is standard: it
 * runs nothing until the test calls [advanceUntilIdle]. That leaves the ViewModel
 * suspended mid-balance, so we can see the in-between "Balancing" state and cancel it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TonightViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = UnconfinedTestDispatcher(scheduler)
    private val dispatcher = StandardTestDispatcher(scheduler)

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main, which doesn't exist on the JVM.
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(ticked: Int, balanceDispatcher: CoroutineDispatcher = dispatcher) = TonightViewModel(
        players = SamplePlayers.tonight,
        initiallyTicked = SamplePlayers.tonight.take(ticked).map { it.id }.toSet(),
        balanceDispatcher = balanceDispatcher,
    )

    private fun TestScope.balanced(ticked: Int): TonightViewModel =
        viewModel(ticked).also {
            it.balance()
            advanceUntilIdle()
        }

    @Test
    fun toggle_ticksAndUnticksAPlayer() = runTest(mainDispatcher) {
        val vm = viewModel(ticked = 0)

        vm.toggle("p01")
        assertEquals(setOf("p01"), vm.state.value.ticked)
        assertEquals(1, vm.state.value.playingCount)

        vm.toggle("p01")
        assertTrue(vm.state.value.ticked.isEmpty())
    }

    @Test
    fun balance_showsBalancingThenTheBestSplit() = runTest(mainDispatcher) {
        val vm = viewModel(ticked = 8)

        vm.balance()
        assertEquals(TonightResult.Balancing, vm.state.value.result)

        advanceUntilIdle()
        val teams = vm.state.value.result as TonightResult.Teams
        assertEquals(0, teams.index)
        assertEquals(5, teams.total)
        assertEquals(4, teams.split.teamA.size)
        assertEquals(4, teams.split.teamB.size)
        assertEquals(vm.state.value.ticked, (teams.split.teamA + teams.split.teamB).toSet())
    }

    @Test
    fun reroll_cyclesThroughKeptSplitsAndWrapsAround() = runTest(mainDispatcher) {
        val vm = balanced(ticked = 8)
        val seen = mutableListOf<TonightResult.Teams>()

        repeat(5) {
            seen += vm.state.value.result as TonightResult.Teams
            vm.reroll()
        }

        assertEquals(listOf(0, 1, 2, 3, 4), seen.map { it.index })
        assertEquals(5, seen.distinctBy { it.split }.size)
        // The fifth press wraps back to the first option.
        assertEquals(seen.first(), vm.state.value.result)
    }

    @Test
    fun reroll_withNoTeamsDoesNothing() = runTest(mainDispatcher) {
        val vm = viewModel(ticked = 8)

        vm.reroll()

        assertEquals(TonightResult.Idle, vm.state.value.result)
    }

    @Test
    fun balance_withTooFewPlayersGivesAProblem() = runTest(mainDispatcher) {
        val vm = balanced(ticked = 3)

        assertEquals(TonightResult.Problem(R.string.problem_too_few), vm.state.value.result)
    }

    @Test
    fun toggle_clearsTheShownTeams() = runTest(mainDispatcher) {
        val vm = balanced(ticked = 8)
        assertTrue(vm.state.value.result is TonightResult.Teams)

        vm.toggle("p10")

        assertEquals(TonightResult.Idle, vm.state.value.result)
        // And a reroll can't bring the old teams back.
        vm.reroll()
        assertEquals(TonightResult.Idle, vm.state.value.result)
    }

    @Test
    fun toggle_whileBalancingCancelsTheResult() = runTest(mainDispatcher) {
        val vm = viewModel(ticked = 8)

        vm.balance()
        // The balancer hasn't run yet: the ViewModel is suspended inside withContext.
        assertEquals(TonightResult.Balancing, vm.state.value.result)
        vm.toggle("p10")
        advanceUntilIdle()

        assertEquals(TonightResult.Idle, vm.state.value.result)
    }

    @Test
    fun balance_twiceCancelsTheFirstRun() = runTest(mainDispatcher) {
        val manual = ManualDispatcher()
        val vm = viewModel(ticked = 8, balanceDispatcher = manual)

        vm.balance()
        vm.balance()
        assertEquals(2, manual.pending)

        // Let only the first run finish. If it hadn't been cancelled, it would now
        // publish its teams. Cancelled, it publishes nothing.
        manual.runNext()
        assertEquals(TonightResult.Balancing, vm.state.value.result)

        manual.runNext()
        assertTrue(vm.state.value.result is TonightResult.Teams)
    }
}

/** A dispatcher that holds work until the test says [runNext], one task at a time. */
private class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    val pending: Int get() = queue.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun runNext() = queue.removeFirst().run()
}
