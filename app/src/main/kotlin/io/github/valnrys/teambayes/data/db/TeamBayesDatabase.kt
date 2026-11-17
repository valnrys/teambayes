package io.github.valnrys.teambayes.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * The current schema version. Bump it with every schema change (see [TeamBayesDatabase]);
 * `MigrationTest` checks it matches the newest exported schema file.
 */
const val SCHEMA_VERSION = 1

/**
 * Every migration between schema versions, oldest first. Empty while the schema is still
 * version 1. Add each new `Migration` here, and nowhere else.
 */
val ALL_MIGRATIONS: List<Migration> = emptyList()

/**
 * The app's SQLite database: players, games and appearances.
 *
 * ## Changing the schema (read before editing an entity)
 * Users' games live in this file, so a new version must keep their data:
 * 1. Bump [SCHEMA_VERSION] (the `@Database` version).
 * 2. Write a `Migration` from the old version to the new one and add it to [ALL_MIGRATIONS],
 *    the single list used both by [create] and by the migration tests.
 * 3. Build once, so Room writes `app/schemas/.../<version>.json`, and commit that file.
 * 4. Add a test to `MigrationTest` that opens the old version, migrates, and checks the data.
 *
 * `MigrationTest` then checks, for every exported schema, that data seeded in the oldest
 * version survives a migration to the newest.
 *
 * **Never call `fallbackToDestructiveMigration`:** it would silently wipe the user's games.
 * `NoDestructiveMigrationTest` fails the build if it appears in the app's source.
 * Ratings aren't stored, so a migration only has to carry players and games over.
 */
@Database(
    entities = [PlayerEntity::class, GameEntity::class, AppearanceEntity::class],
    version = SCHEMA_VERSION,
    exportSchema = true,
)
abstract class TeamBayesDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun gameDao(): GameDao

    companion object {
        /** The database file's name, inside the app's private storage. */
        const val FILE_NAME = "teambayes.db"

        /** Opens (creating if needed) the on-phone database. Call once; see `AppContainer`. */
        fun create(context: Context): TeamBayesDatabase =
            Room.databaseBuilder(context.applicationContext, TeamBayesDatabase::class.java, FILE_NAME)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(*ALL_MIGRATIONS.toTypedArray())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
