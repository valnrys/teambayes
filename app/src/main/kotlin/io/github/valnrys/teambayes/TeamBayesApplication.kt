package io.github.valnrys.teambayes

import android.app.Application

/**
 * The app's `Application`: Android creates it once, before any screen. It builds the
 * [AppContainer] so every screen shares the same database. Registered in the manifest.
 */
class TeamBayesApplication : Application() {

    /** The app's shared database and repositories. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
