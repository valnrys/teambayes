package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.data.db.AppearanceEntity
import io.github.valnrys.teambayes.data.db.GameEntity
import io.github.valnrys.teambayes.data.db.GameWithAppearances
import io.github.valnrys.teambayes.data.db.PlayerEntity
import io.github.valnrys.teambayes.engine.Outcome

// Plain functions between database rows and the app's own types. Nothing here touches
// Android, so they are covered by ordinary JVM tests.

/** The text stored in `games.status`. */
internal object GameStatus {
    const val PENDING = "pending"
    const val FINAL = "final"
}

internal fun PlayerEntity.toStoredPlayer() =
    StoredPlayer(id = id, name = name, active = active, startingMu = startingMu, createdAt = createdAt)

/** The text stored in `games.winner`. */
internal fun Outcome.toCode(): String = when (this) {
    Outcome.A_WINS -> "a"
    Outcome.B_WINS -> "b"
    Outcome.DRAW -> "draw"
}

internal fun outcomeFromCode(code: String): Outcome = when (code) {
    "a" -> Outcome.A_WINS
    "b" -> Outcome.B_WINS
    "draw" -> Outcome.DRAW
    else -> error("Unknown winner code '$code' in the database")
}

/** The text stored in `games.selection`. */
internal fun Selection.toCode(): String = name.lowercase()

internal fun selectionFromCode(code: String): Selection =
    Selection.entries.firstOrNull { it.toCode() == code }
        ?: error("Unknown selection code '$code' in the database")

/** The text stored in `appearances.team`. */
internal fun Team.toCode(): String = name.lowercase()

private fun teamFromCode(code: String): Team =
    Team.entries.firstOrNull { it.toCode() == code } ?: error("Unknown team code '$code' in the database")

/** Splits a game's appearances by team, in a steady order (by player id). */
private fun GameWithAppearances.appearancesOf(team: Team): List<AppearanceEntity> =
    appearances.filter { teamFromCode(it.team) == team }.sortedBy { it.playerId }

/** @throws IllegalStateException if the row is not a pending game. */
internal fun GameWithAppearances.toPendingGame(): PendingGame {
    check(game.status == GameStatus.PENDING) { "Game ${game.id} is not pending" }
    val override = if (game.scoreA != null && game.scoreB != null) Score(game.scoreA, game.scoreB) else null
    return PendingGame(
        id = game.id,
        playedAt = game.playedAt,
        createdAt = game.createdAt,
        selection = selectionFromCode(game.selection),
        teamA = appearancesOf(Team.A).map { PlayerGoals(it.playerId, it.goals) },
        teamB = appearancesOf(Team.B).map { PlayerGoals(it.playerId, it.goals) },
        scoreOverride = override,
    )
}

/** @throws IllegalStateException if the row is not a finalised game. */
internal fun GameWithAppearances.toFinalGame(): FinalGame {
    check(game.status == GameStatus.FINAL) { "Game ${game.id} is not final" }
    return FinalGame(
        id = game.id,
        playedAt = game.playedAt,
        createdAt = game.createdAt,
        selection = selectionFromCode(game.selection),
        outcome = outcomeFromCode(checkNotNull(game.winner) { "Final game ${game.id} has no winner" }),
        scoreA = checkNotNull(game.scoreA) { "Final game ${game.id} has no score" },
        scoreB = checkNotNull(game.scoreB) { "Final game ${game.id} has no score" },
        teamA = appearancesOf(Team.A).map { PlayerGoals(it.playerId, it.goals) },
        teamB = appearancesOf(Team.B).map { PlayerGoals(it.playerId, it.goals) },
    )
}

internal fun newGameRow(
    id: String,
    playedAt: Long,
    selection: Selection,
    createdAt: Long,
) = GameEntity(
    id = id,
    playedAt = playedAt,
    status = GameStatus.PENDING,
    pendingSlot = GameEntity.PENDING_SLOT,
    winner = null,
    scoreA = null,
    scoreB = null,
    selection = selection.toCode(),
    createdAt = createdAt,
)
