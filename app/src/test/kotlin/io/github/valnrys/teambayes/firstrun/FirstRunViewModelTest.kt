package io.github.valnrys.teambayes.firstrun

import androidx.lifecycle.SavedStateHandle
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.DuplicateNameException
import io.github.valnrys.teambayes.matchday.MAX_NAME_LENGTH
import io.github.valnrys.teambayes.matchday.SamplePlayers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunViewModelTest {

    private val repository = FakePlayerRepository()
    private var handle = SavedStateHandle()
    private var viewModel = FirstRunViewModel(repository, handle)

    private val names get() = viewModel.state.value.names

    @Before
    fun setUp() {
        // viewModelScope runs on the main dispatcher, which doesn't exist in JVM tests.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun add(vararg players: String) = players.forEach {
        viewModel.setDraft(it)
        viewModel.addPlayer()
    }

    @Test
    fun addsAPlayerAndClearsTheField() {
        add("Ottoline")
        assertEquals(listOf("Ottoline"), names)
        assertEquals("", viewModel.state.value.draft)
    }

    @Test
    fun trimsSpacesAndIgnoresBlankNames() {
        add("  Bramwell  ", "   ", "")
        assertEquals(listOf("Bramwell"), names)
    }

    @Test
    fun cutsTheDraftAtTheMaximumLength() {
        viewModel.setDraft("x".repeat(MAX_NAME_LENGTH + 10))
        assertEquals(MAX_NAME_LENGTH, viewModel.state.value.draft.length)
    }

    @Test
    fun refusesADuplicateIgnoringCase() {
        add("Zephyra")
        viewModel.setDraft(" zephyra ")
        viewModel.addPlayer()
        assertEquals(listOf("Zephyra"), names)
        assertEquals("zephyra", viewModel.state.value.duplicateName)
        // Typing again clears the warning.
        viewModel.setDraft("Kaz")
        assertNull(viewModel.state.value.duplicateName)
    }

    @Test
    fun removesAPlayer() {
        add("Ottoline", "Bramwell", "Zephyra")
        viewModel.removePlayer("Bramwell")
        assertEquals(listOf("Ottoline", "Zephyra"), names)
    }

    @Test
    fun nextNeedsTwoPlayers() {
        add("Ottoline")
        assertFalse(viewModel.state.value.canContinue)
        viewModel.next()
        assertEquals(FirstRunStep.ADD, viewModel.state.value.step)

        add("Bramwell")
        assertTrue(viewModel.state.value.canContinue)
        viewModel.next()
        assertEquals(FirstRunStep.RANK, viewModel.state.value.step)
    }

    @Test
    fun hintShowsUntilFourPlayers() {
        add("A1", "B1", "C1")
        assertTrue(viewModel.state.value.belowMatchDayMinimum)
        add("D1")
        assertFalse(viewModel.state.value.belowMatchDayMinimum)
    }

    @Test
    fun backKeepsTheRanking() {
        add("Ottoline", "Bramwell", "Zephyra")
        viewModel.next()
        viewModel.move(2, 0)
        viewModel.back()
        assertEquals(FirstRunStep.ADD, viewModel.state.value.step)
        assertEquals(listOf("Zephyra", "Ottoline", "Bramwell"), names)
    }

    @Test
    fun movesUpAndDownWithTheSameFunctionAsDragging() {
        add("Ottoline", "Bramwell", "Zephyra")
        viewModel.move(1, 0) // "Move up" on Bramwell
        assertEquals(listOf("Bramwell", "Ottoline", "Zephyra"), names)
        viewModel.move(0, 1) // "Move down" on Bramwell
        assertEquals(listOf("Ottoline", "Bramwell", "Zephyra"), names)
        viewModel.move(0, 2) // a long drag
        assertEquals(listOf("Bramwell", "Zephyra", "Ottoline"), names)
    }

    @Test
    fun ignoresMovesOutsideTheList() {
        add("Ottoline", "Bramwell")
        viewModel.move(0, -1)
        viewModel.move(1, 2)
        viewModel.move(5, 0)
        assertEquals(listOf("Ottoline", "Bramwell"), names)
    }

    @Test
    fun saveSendsNamesBestToWorstAndMarksSaved() {
        add("Ottoline", "Bramwell", "Zephyra")
        viewModel.next()
        viewModel.move(2, 0)
        viewModel.save()
        assertEquals(listOf(listOf("Zephyra", "Ottoline", "Bramwell")), repository.saves)
        assertTrue(viewModel.state.value.saved)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun aSaveFinishingAfterRecreationIsStillSeen() = runTest {
        add("Ottoline", "Bramwell")
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        viewModel.save()
        assertTrue(viewModel.state.value.saving)
        // The activity is recreated mid-save: same ViewModel, new screen. Nothing captured
        // from the old screen is needed, because the result lands in state.
        gate.complete(Unit)
        assertTrue(viewModel.state.value.saved)
        // And even a brand-new ViewModel (process death) sees it from the saved bundle.
        assertTrue(FirstRunViewModel(repository, handle).state.value.saved)
    }

    @Test
    fun cannotReorderOrGoBackWhileSaving() = runTest {
        add("Ottoline", "Bramwell")
        viewModel.next()
        repository.gate = CompletableDeferred()
        viewModel.save()
        viewModel.move(1, 0)
        viewModel.back()
        assertEquals(listOf("Ottoline", "Bramwell"), names)
        assertEquals(FirstRunStep.RANK, viewModel.state.value.step)
    }

    @Test
    fun staysLockedAfterASuccessfulSave() {
        add("Ottoline", "Bramwell")
        viewModel.next()
        viewModel.save()
        assertTrue(viewModel.state.value.busy)
        viewModel.move(1, 0)
        viewModel.back()
        assertEquals(listOf("Ottoline", "Bramwell"), names)
        assertEquals(FirstRunStep.RANK, viewModel.state.value.step)
    }

    @Test
    fun aFailedSaveShowsAProblemAndIsNotSaved() {
        add("Ottoline", "Bramwell")
        repository.failure = IllegalStateException("disk full")
        viewModel.save()
        assertFalse(viewModel.state.value.saved)
        assertEquals(R.string.first_run_save_failed, viewModel.state.value.saveProblem)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun aDuplicateFromTheRepositoryGoesBackToStepOneWithTheName() {
        add("Ottoline", "Bramwell")
        viewModel.next()
        repository.stored = listOf("bramwell")
        repository.failure = DuplicateNameException("Bramwell")
        viewModel.save()
        assertEquals(FirstRunStep.ADD, viewModel.state.value.step)
        assertEquals("Bramwell", viewModel.state.value.duplicateName)
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun ifEveryNameIsAlreadyStoredTheSaveCountsAsDone() {
        add("Ottoline", "Bramwell")
        viewModel.next()
        repository.stored = listOf("ottoline", "BRAMWELL")
        repository.failure = DuplicateNameException("Ottoline")
        viewModel.save()
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun namesAreComparedAfterCollapsingInnerSpaces() {
        add("Mary  Ann")
        assertEquals(listOf("Mary Ann"), names)
        add("mary ann")
        assertEquals(listOf("Mary Ann"), names)
        assertEquals("mary ann", viewModel.state.value.duplicateName)
    }

    @Test
    fun loadsFourteenSamplePlayers() {
        add("Someone")
        viewModel.loadSamplePlayers()
        assertEquals(14, names.size)
        assertEquals(SamplePlayers.all.map { it.name }, names)
        assertEquals(names.map { it.lowercase() }.toSet().size, names.size) // all different
    }

    @Test
    fun namesAndStepSurviveProcessDeath() {
        add("Ottoline", "Bramwell", "Zephyra")
        viewModel.next()
        viewModel.move(2, 0)
        viewModel.setDraft("Kaz")
        // Android restarts the app: a new ViewModel is made with the saved bundle.
        viewModel = FirstRunViewModel(repository, handle)
        assertEquals(listOf("Zephyra", "Ottoline", "Bramwell"), names)
        assertEquals(FirstRunStep.RANK, viewModel.state.value.step)
        assertEquals("Kaz", viewModel.state.value.draft)
    }
}
