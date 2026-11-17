package io.github.valnrys.teambayes

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs the instrumented tests with [TestTeamBayesApplication] in place of the app's own
 * `Application`, so a test that starts [MainActivity] gets an in-memory database.
 *
 * **Why.** A phone's real database holds real players and maybe a real unfinished match
 * day; a test that discarded "any pending game" would delete it. Two things protect it:
 *
 * - **During the tests**, no test opens the real database file. Each kind gets its data
 *   its own way:
 *   - repository tests extend `DatabaseTest`, which builds a fresh in-memory database;
 *   - `MigrationTest` uses a throwaway file of its own, `migration-test.db`;
 *   - `FirstRunScreenTest` uses a fake repository;
 *   - the match-day screen tests build their own container with [inMemoryContainer];
 *   - only tests that launch [MainActivity] (`ResumeMatchDayTest`) rely on this runner,
 *     which gives the app an in-memory container.
 * - **After the tests**, `android.injected.androidTest.leaveApksInstalledAfterRun` in
 *   `gradle.properties`: by default the test task uninstalls the app when it finishes,
 *   and uninstalling deletes all its data.
 *
 * Neither is a licence to test on a phone in real use. Installing the debug build over
 * the app, or a test that slips past both, could still cost real data. Use an emulator
 * or a spare phone.
 *
 * Registered as `testInstrumentationRunner` in `app/build.gradle.kts`. The production
 * app carries no test switch; it only lets a subclass build the container.
 */
class TeamBayesTestRunner : AndroidJUnitRunner() {
    override fun newApplication(classLoader: ClassLoader, className: String, context: Context): Application =
        super.newApplication(classLoader, TestTeamBayesApplication::class.java.name, context)
}

/** The app, but on a fresh in-memory database that disappears when the tests finish. */
class TestTeamBayesApplication : TeamBayesApplication() {
    override fun createContainer(): AppContainer = inMemoryContainer()

    /**
     * Swaps in a new, empty in-memory database, so one test can't see what another left.
     * Call it in `@Before`, before starting an activity.
     */
    fun resetContainer() {
        container.database.close()
        container = inMemoryContainer()
    }
}
