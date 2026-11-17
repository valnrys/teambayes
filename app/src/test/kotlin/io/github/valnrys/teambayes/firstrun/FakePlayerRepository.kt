package io.github.valnrys.teambayes.firstrun

import io.github.valnrys.teambayes.data.PlayerRepository
import io.github.valnrys.teambayes.data.StoredPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf

/**
 * A repository that keeps what it is given in memory, so tests can check what first run
 * saved without a database.
 *
 * @property failure if set, [addPlayersInOrder] throws it instead of saving.
 */
class FakePlayerRepository : PlayerRepository {
    /** Each call to [addPlayersInOrder], in order. */
    val saves = mutableListOf<List<String>>()
    var failure: Exception? = null

    /** If set, [addPlayersInOrder] waits for it before saving, to pause a save midway. */
    var gate: CompletableDeferred<Unit>? = null

    /** Names the "database" already holds, as seen through [players]. */
    var stored: List<String> = emptyList()

    override val players: Flow<List<StoredPlayer>>
        get() = flowOf(stored.map { StoredPlayer(it, it, true, 0.0, 0L) })

    override suspend fun addPlayersInOrder(namesBestToWorst: List<String>) {
        gate?.await()
        failure?.let { throw it }
        saves += namesBestToWorst
    }

    override suspend fun addPlayer(name: String, startingMu: Double): String = error("Not used by first run")
}
