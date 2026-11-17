package io.github.valnrys.teambayes.data

import io.github.valnrys.teambayes.engine.Outcome

/** Small builders so each test shows only what it cares about. */
internal fun player(id: String, mu: Double = 25.0) =
    StoredPlayer(id = id, name = id, active = true, startingMu = mu, createdAt = 0)

internal fun finalGame(
    id: String,
    teamA: List<String>,
    teamB: List<String>,
    outcome: Outcome,
    playedAt: Long = 0,
    createdAt: Long = 0,
) = FinalGame(
    id = id,
    playedAt = playedAt,
    createdAt = createdAt,
    selection = Selection.GENERATED,
    outcome = outcome,
    scoreA = 0,
    scoreB = 0,
    teamA = teamA.map { PlayerGoals(it, 0) },
    teamB = teamB.map { PlayerGoals(it, 0) },
)
