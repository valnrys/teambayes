package io.github.valnrys.teambayes.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase
import org.junit.After
import org.junit.Before

/**
 * Gives each test a fresh in-memory database (the same one the app uses, but never saved
 * to disk) and the three repositories on top of it. Ids and clock are predictable.
 */
abstract class DatabaseTest {
    protected lateinit var db: TeamBayesDatabase
    protected lateinit var players: RoomPlayerRepository
    protected lateinit var games: RoomGameRepository
    protected lateinit var ratings: RatingsRepository

    private var clock = 1_000L
    private var nextId = 0

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TeamBayesDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .build()
        players = RoomPlayerRepository(db, now = { clock++ }, newId = { "id-${nextId++}" })
        games = RoomGameRepository(db, now = { clock++ }, newId = { "game-${nextId++}" })
        ratings = RatingsRepository(players, games)
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}
