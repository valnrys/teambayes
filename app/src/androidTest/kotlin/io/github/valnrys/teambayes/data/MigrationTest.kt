package io.github.valnrys.teambayes.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.valnrys.teambayes.data.db.ALL_MIGRATIONS
import io.github.valnrys.teambayes.data.db.SCHEMA_VERSION
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase_Impl
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The safety net for schema changes. The helper reads the exported schemas in
 * `app/schemas/` (committed to git), so it can build the database exactly as an older app
 * version did, fill it with data, and run [ALL_MIGRATIONS] on it.
 *
 * Nothing needs editing here when the schema changes: add the new `Migration` to
 * [ALL_MIGRATIONS] and commit the new `<n>.json`. These tests then pick up the new version
 * and fail if a migration loses data or leaves a table that doesn't match the schema.
 *
 * Only hand-written migrations are covered. If anyone adds an `@AutoMigration` to the
 * `@Database` annotation, this harness must be updated to include it (pass its spec to the
 * helper and count it in [migrationsFollowTheExportedVersions]), or it will wrongly fail.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Each test starts with no database file: the helper refuses to create over an old one. */
    @Before
    fun deleteOldFile() {
        instrumentation.targetContext.deleteDatabase(DB_FILE)
    }

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(DB_FILE),
        driver = BundledSQLiteDriver(),
        databaseClass = TeamBayesDatabase::class,
        databaseFactory = { TeamBayesDatabase_Impl() },
    )

    /** The exported schema versions, from the `<n>.json` files in the test APK's assets. */
    private fun exportedVersions(): List<Int> {
        val folder = TeamBayesDatabase::class.java.name
        return instrumentation.context.assets.list(folder).orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json").toInt() }
            .sorted()
    }

    @Test
    fun everyExportedVersionCanBeCreated() = runTest {
        val versions = exportedVersions()
        assertTrue("no schema files found", versions.isNotEmpty())
        for (version in versions) {
            helper.createDatabase(version).close()
            // The helper won't create over an existing file, so clear it for the next version.
            instrumentation.targetContext.deleteDatabase(DB_FILE)
        }
    }

    @Test
    fun migrationsFollowTheExportedVersions() {
        val versions = exportedVersions()
        assertEquals("versions must run 1..n with no gaps", (1..versions.last()).toList(), versions)
        assertEquals(
            "ALL_MIGRATIONS must hold one migration per step, oldest first",
            versions.zipWithNext(),
            ALL_MIGRATIONS.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun newestExportedSchemaIsTheDatabaseVersion() {
        assertEquals(
            "bump SCHEMA_VERSION and commit the new schema file together",
            SCHEMA_VERSION,
            exportedVersions().last(),
        )
    }

    @Test
    fun dataFromTheOldestVersionSurvivesMigrationToTheNewest() = runTest {
        val oldest = exportedVersions().first()
        val latest = exportedVersions().last()

        // Seed the oldest schema with a pending game and a final game. This SQL is written
        // for version 1 and must keep working: it describes what real users' phones hold.
        helper.createDatabase(oldest).also { db ->
            db.execSQL("INSERT INTO players VALUES ('p1', 'Amy', 1, 30.0, 1000)")
            db.execSQL("INSERT INTO players VALUES ('p2', 'Bob', 1, 20.0, 1001)")
            db.execSQL("INSERT INTO games VALUES ('g1', 5000, 'final', NULL, 'a', 3, 1, 'generated', 5001)")
            db.execSQL("INSERT INTO appearances VALUES ('g1', 'p1', 'a', 3)")
            db.execSQL("INSERT INTO appearances VALUES ('g1', 'p2', 'b', 1)")
            db.execSQL("INSERT INTO games VALUES ('g2', 6000, 'pending', 1, NULL, NULL, NULL, 'manual', 6001)")
            db.execSQL("INSERT INTO appearances VALUES ('g2', 'p1', 'b', 0)")
            db.execSQL("INSERT INTO appearances VALUES ('g2', 'p2', 'a', 0)")
            db.close()
        }

        // Runs every migration and checks the result against the newest schema's JSON.
        val migrated = helper.runMigrationsAndValidate(latest, ALL_MIGRATIONS)

        fun count(sql: String): Long = migrated.prepare(sql).use { statement ->
            statement.step()
            statement.getLong(0)
        }
        assertEquals(2, count("SELECT COUNT(*) FROM players"))
        assertEquals(2, count("SELECT COUNT(*) FROM games"))
        assertEquals(4, count("SELECT COUNT(*) FROM appearances"))
        assertEquals(1, count("SELECT COUNT(*) FROM games WHERE status = 'pending'"))
        assertEquals(3, count("SELECT goals FROM appearances WHERE game_id = 'g1' AND player_id = 'p1'"))
        migrated.close()
    }

    private companion object {
        const val DB_FILE = "migration-test.db"
    }
}
