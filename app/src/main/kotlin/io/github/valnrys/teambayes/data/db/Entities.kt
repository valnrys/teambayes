package io.github.valnrys.teambayes.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

// The three tables. Room turns these classes into SQL and writes the result to
// app/schemas/. Text codes ('pending', 'a', ...) are converted to proper Kotlin types in
// Mapping.kt, so nothing outside the data package sees them.

/**
 * A player. `name` has `COLLATE NOCASE`, so its unique index ignores A-Z case. (SQLite
 * folds only ASCII; the repository also compares names in Kotlin for other letters.)
 */
@Entity(
    tableName = "players",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlayerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val active: Boolean,
    @ColumnInfo(name = "starting_mu") val startingMu: Double,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A game.
 *
 * - `played_at` and `created_at` are epoch **milliseconds**, the same unit the engine's
 *   `Game` uses, so no conversion is needed. The app stores every game on a given day at noon,
 *   so `played_at` only separates days and `created_at` orders the games within a day.
 * - `status` is `pending` or `final`; `winner` is `a`, `b` or `draw`, and null until final.
 * - `score_a` / `score_b` are null for a pending game, unless the organiser typed a score
 *   in "adjust score" mode: then they hold that override. Finalising always fills them.
 * - `selection` is `generated`, `edited` or `manual`.
 * - `pending_slot` enforces "at most one pending game" in the schema. It is `1` while the
 *   game is pending and null once final. SQLite treats nulls as different from each other,
 *   so the unique index allows any number of final games but only one with a `1`. Room
 *   can't declare a partial index (`WHERE status = 'pending'`), so this is the simple
 *   equivalent.
 */
@Entity(
    tableName = "games",
    indices = [
        Index(value = ["pending_slot"], unique = true),
        Index(value = ["status", "played_at"]),
    ],
)
data class GameEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "played_at") val playedAt: Long,
    val status: String,
    @ColumnInfo(name = "pending_slot") val pendingSlot: Int?,
    val winner: String?,
    @ColumnInfo(name = "score_a") val scoreA: Int?,
    @ColumnInfo(name = "score_b") val scoreB: Int?,
    val selection: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        /** The value of `pending_slot` for a pending game. */
        const val PENDING_SLOT = 1
    }
}

/**
 * One player's part in one game. Deleting a game deletes its appearances. A player can't
 * be deleted while they have appearances (the default `NO ACTION`), which protects history.
 * The primary key starts with `game_id`, so it also serves lookups by game; `player_id`
 * gets its own index for "this player's games".
 */
@Entity(
    tableName = "appearances",
    primaryKeys = ["game_id", "player_id"],
    foreignKeys = [
        ForeignKey(
            entity = GameEntity::class,
            parentColumns = ["id"],
            childColumns = ["game_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["id"],
            childColumns = ["player_id"],
        ),
    ],
    indices = [Index(value = ["player_id"])],
)
data class AppearanceEntity(
    @ColumnInfo(name = "game_id") val gameId: String,
    @ColumnInfo(name = "player_id") val playerId: String,
    /** `a` or `b`. */
    val team: String,
    @ColumnInfo(defaultValue = "0") val goals: Int,
)

/** A game together with all its appearances, loaded in one go. */
data class GameWithAppearances(
    @Embedded val game: GameEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["game_id"])
    val appearances: List<AppearanceEntity>,
)
