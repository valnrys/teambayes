package io.github.valnrys.teambayes

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase

/**
 * An [AppContainer] on a fresh in-memory database: the app's real repositories, but
 * empty at the start and gone when the test closes it with `container.database.close()`.
 */
fun inMemoryContainer(): AppContainer {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = Room.inMemoryDatabaseBuilder(context, TeamBayesDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .build()
    return AppContainer(database)
}
