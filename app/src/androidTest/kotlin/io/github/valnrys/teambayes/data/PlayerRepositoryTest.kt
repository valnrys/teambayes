package io.github.valnrys.teambayes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.valnrys.teambayes.engine.EngineConfig
import io.github.valnrys.teambayes.engine.Prior
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRepositoryTest : DatabaseTest() {

    @Test
    fun firstRunStoresPlayersWithPriorMus() = runTest {
        players.addPlayersInOrder(listOf("Zoe", "Bob", "Amy"))

        val stored = players.players.first()
        assertEquals(listOf("Amy", "Bob", "Zoe"), stored.map { it.name }) // alphabetical
        val mus = Prior.fromRankOrder(3)
        assertEquals(mus[0], stored.first { it.name == "Zoe" }.startingMu, 1e-12)
        assertEquals(mus[2], stored.first { it.name == "Amy" }.startingMu, 1e-12)
        assertTrue(stored.all { it.active })
    }

    @Test
    fun namesAreUniqueIgnoringCase() = runTest {
        players.addPlayersInOrder(listOf("Amy"))

        val error = assertThrows(DuplicateNameException::class.java) {
            kotlinx.coroutines.runBlocking { players.addPlayer("aMY", EngineConfig.MU0) }
        }
        assertEquals("aMY", error.name)
        assertEquals(1, players.players.first().size)
    }

    @Test
    fun nonAsciiNamesAreAlsoUniqueIgnoringCase() = runTest {
        players.addPlayer("Émile", 25.0)
        assertThrows(DuplicateNameException::class.java) {
            kotlinx.coroutines.runBlocking { players.addPlayer("émile", 25.0) }
        }
    }

    @Test
    fun duplicateInsideOneListAddsNobody() = runTest {
        assertThrows(DuplicateNameException::class.java) {
            kotlinx.coroutines.runBlocking { players.addPlayersInOrder(listOf("Amy", "Bob", "amy")) }
        }
        assertTrue(players.players.first().isEmpty())
    }

    @Test
    fun duplicateOfAnExistingPlayerAddsNobody() = runTest {
        players.addPlayer("Amy", 25.0)
        assertThrows(DuplicateNameException::class.java) {
            kotlinx.coroutines.runBlocking { players.addPlayersInOrder(listOf("Cal", "AMY")) }
        }
        assertEquals(listOf("Amy"), players.players.first().map { it.name })
    }

    @Test
    fun laterAddKeepsTheCallersMuAndReturnsTheId() = runTest {
        val id = players.addPlayer("  Cal ", 27.5)
        val cal = players.players.first().single()
        assertEquals(id, cal.id)
        assertEquals("Cal", cal.name) // trimmed
        assertEquals(27.5, cal.startingMu, 0.0)
    }

    @Test
    fun namesAreNormalisedBeforeComparingAndStoring() = runTest {
        players.addPlayer("Sam  Smith", 25.0)
        assertThrows(DuplicateNameException::class.java) {
            kotlinx.coroutines.runBlocking { players.addPlayer(" sam smith ", 25.0) }
        }
        players.addPlayer("Rene\u0301", 25.0) // decomposed e + accent
        assertThrows(DuplicateNameException::class.java) {
            kotlinx.coroutines.runBlocking { players.addPlayer("Ren\u00e9", 25.0) }
        }
        assertEquals(listOf("Ren\u00e9", "Sam Smith"), players.players.first().map { it.name })
    }
}
