package io.github.valnrys.teambayes.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Database access for players. Used through [io.github.valnrys.teambayes.data.PlayerRepository]. */
@Dao
interface PlayerDao {
    @Query("SELECT * FROM players ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<PlayerEntity>>

    @Query("SELECT * FROM players")
    suspend fun getAll(): List<PlayerEntity>

    @Query("SELECT id FROM players WHERE id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /** Fails with a constraint error if a name is already taken. */
    @Insert
    suspend fun insertAll(players: List<PlayerEntity>)
}

/** Database access for games and their appearances. Used through [io.github.valnrys.teambayes.data.GameRepository]. */
@Dao
interface GameDao {
    @Transaction
    @Query("SELECT * FROM games WHERE status = 'pending' LIMIT 1")
    fun observePending(): Flow<GameWithAppearances?>

    @Transaction
    @Query("SELECT * FROM games WHERE status = 'final' ORDER BY played_at, created_at, id")
    fun observeFinal(): Flow<List<GameWithAppearances>>

    @Query("SELECT * FROM games WHERE status = 'pending' LIMIT 1")
    suspend fun getPending(): GameEntity?

    @Insert
    suspend fun insertGame(game: GameEntity)

    @Insert
    suspend fun insertAppearances(appearances: List<AppearanceEntity>)

    /** Returns how many rows changed: 0 if that player isn't in that game. */
    @Query("UPDATE appearances SET goals = :goals WHERE game_id = :gameId AND player_id = :playerId")
    suspend fun setGoals(gameId: String, playerId: String, goals: Int): Int

    @Query("UPDATE games SET score_a = :scoreA, score_b = :scoreB WHERE id = :gameId")
    suspend fun setScore(gameId: String, scoreA: Int?, scoreB: Int?)

    /** Turns a pending game final, and frees the one-pending slot. */
    @Query(
        "UPDATE games SET status = 'final', pending_slot = NULL, winner = :winner, " +
            "score_a = :scoreA, score_b = :scoreB WHERE id = :gameId AND status = 'pending'",
    )
    suspend fun finalise(gameId: String, winner: String, scoreA: Int, scoreB: Int)

    /**
     * Appearances are deleted too, by the foreign key's cascade. Only a *pending* game can
     * be deleted this way, so a mistake can never erase finished history.
     */
    @Query("DELETE FROM games WHERE id = :gameId AND status = 'pending'")
    suspend fun deletePendingGame(gameId: String)
}
