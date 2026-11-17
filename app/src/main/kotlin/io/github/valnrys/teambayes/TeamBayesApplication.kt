package io.github.valnrys.teambayes

import android.app.Application

/**
 * The app's `Application`: Android creates it once, before any screen. It builds the
 * [AppContainer] so every screen shares the same database. Registered in the manifest.
 *
 * It's `open` for one reason: the instrumented tests run the app with their own subclass,
 * which overrides [createContainer] to use an in-memory database, so a test can never
 * touch the real one on the phone (see `TeamBayesTestRunner` in `src/androidTest`).
 */
open class TeamBayesApplication : Application() {

    /** The app's shared database and repositories. A test subclass may replace it between tests. */
    lateinit var container: AppContainer
        protected set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
    }

    /** Builds the container. The app uses the database file on the phone. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
