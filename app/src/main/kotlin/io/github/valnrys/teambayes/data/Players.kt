package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.data.db.PlayerDao
import io.github.valnrys.teambayes.data.db.PlayerEntity
import io.github.valnrys.teambayes.data.db.TeamBayesDatabase
import io.github.valnrys.teambayes.engine.Prior
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room3.withWriteTransaction

/**
 * A player as the app stores them.
 *
 * Skill is deliberately not stored: only the [startingMu] the player was given when they
 * joined is. Their current rating is worked out by replaying games (see [RatingsRepository]).
 *
 * @property id unique id, a UUID string.
 * @property name unique among players, ignoring upper/lower case.
 * @property active whether the player appears on the match-day roster.
 * @property startingMu skill estimate before any game; sigma always starts at
 *   [io.github.valnrys.teambayes.engine.EngineConfig.SIGMA0].
 * @property createdAt when the player was added, in epoch milliseconds.
 */
data class StoredPlayer(
    val id: String,
    val name: String,
    val active: Boolean,
    val startingMu: Double,
    val createdAt: Long,
)

/** Where the app's players live. */
interface PlayerRepository {
    /** All players, active and inactive, alphabetical by name. */
    val players: Flow<List<StoredPlayer>>

    /** First run: names best-to-worst; starting mu from Prior.fromRankOrder. Rejects duplicates (case-insensitive) with DuplicateNameException. */
    suspend fun addPlayersInOrder(namesBestToWorst: List<String>)

    /** Later add, with a starting mu chosen by the caller (Prior.insertBetween). Returns the new id. */
    suspend fun addPlayer(name: String, startingMu: Double): String
}

/** Thrown when a player's [name] is already taken, ignoring upper/lower case. */
class DuplicateNameException(val name: String) : IllegalArgumentException("Player '$name' already exists")

/**
 * [PlayerRepository] backed by the Room database.
 *
 * @param now the clock, in epoch milliseconds; replaced in tests.
 * @param newId makes a fresh player id; replaced in tests.
 */
class RoomPlayerRepository(
    private val database: TeamBayesDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : PlayerRepository {

    private val dao: PlayerDao = database.playerDao()

    override val players: Flow<List<StoredPlayer>> =
        dao.observeAll().map { rows -> rows.map { it.toStoredPlayer() } }

    override suspend fun addPlayersInOrder(namesBestToWorst: List<String>) {
        val names = namesBestToWorst.map(::cleanName)
        val mus = Prior.fromRankOrder(names.size)
        insertAll(names.zip(mus) { name, mu -> NewPlayer(newId(), name, mu) })
    }

    override suspend fun addPlayer(name: String, startingMu: Double): String {
        require(startingMu.isFinite()) { "startingMu must be finite, was $startingMu" }
        val player = NewPlayer(newId(), cleanName(name), startingMu)
        insertAll(listOf(player))
        return player.id
    }

    /** A player about to be added. */
    private class NewPlayer(val id: String, val name: String, val startingMu: Double)

    /** Adds all [players] or none: one transaction. */
    private suspend fun insertAll(players: List<NewPlayer>) {
        if (players.isEmpty()) return
        database.withWriteTransaction {
            // SQLite's NOCASE only folds A-Z, so also compare in Kotlin to catch "Émile" and "émile".
            val taken = dao.getAll().map { it.name.lowercase() }.toMutableSet()
            val createdAt = now()
            val rows = players.map { player ->
                if (!taken.add(player.name.lowercase())) throw DuplicateNameException(player.name)
                PlayerEntity(
                    id = player.id,
                    name = player.name,
                    active = true,
                    startingMu = player.startingMu,
                    createdAt = createdAt,
                )
            }
            dao.insertAll(rows)
        }
    }

    private fun cleanName(name: String): String =
        normaliseName(name).also { require(it.isNotEmpty()) { "A player's name can't be blank" } }
}
