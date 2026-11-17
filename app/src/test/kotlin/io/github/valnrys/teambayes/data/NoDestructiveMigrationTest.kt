package io.github.valnrys.teambayes.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source check, not a behaviour test: `fallbackToDestructiveMigration` makes Room wipe
 * the database when it has no migration to run, which would silently delete a user's
 * games. Nothing in the app may call it. Write a real `Migration` instead (see
 * `TeamBayesDatabase`).
 */
class NoDestructiveMigrationTest {

    @Test
    fun `the app never calls fallbackToDestructiveMigration`() {
        // Unit tests run from the module folder (app/).
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find the app's sources from ${File(".").absolutePath}", sources.isNotEmpty())

        val call = Regex("""\.\s*fallbackToDestructiveMigration\w*\s*[({]""")
        val offenders = sources.filter { call.containsMatchIn(it.readText()) }
        assertTrue("Destructive migration is forbidden, found in: $offenders", offenders.isEmpty())
    }
}
