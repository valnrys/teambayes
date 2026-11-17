package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.ui.theme.SectionLabel
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import io.github.valnrys.teambayes.ui.theme.TeamBayesTypography
import io.github.valnrys.teambayes.ui.theme.teamBayesColors
import kotlin.math.absoluteValue

/** Lets tests find the done step's movement list. */
internal const val MOVEMENT_LIST_TAG = "movement-list"

/**
 * The game is in: who won, the score, and how it moved each player's Skill, biggest rise
 * first. As in the original web version, with **Start another match day** pinned below.
 *
 * Divergence, for now: the original also offers "View full Rankings →". There's no
 * Rankings screen yet; the button arrives with it.
 */
@Composable
internal fun DoneStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    val finished = state.finished ?: return
    StepLayout(
        contentPadding = contentPadding,
        body = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(MOVEMENT_LIST_TAG),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "result") { ResultCard(finished.result) }
                item(key = "label") {
                    SectionLabel(stringResource(R.string.rating_movement), Modifier.padding(top = 12.dp))
                }
                items(finished.movements, key = { it.playerId }) { movement ->
                    MovementRow(
                        name = state.namesById[movement.playerId] ?: stringResource(R.string.empty_column),
                        change = movement.change,
                    )
                }
                if (finished.movements.isEmpty()) {
                    item(key = "none") { HintText(stringResource(R.string.no_movement)) }
                }
            }
        },
        footer = {
            PrimaryButton(
                stringResource(R.string.start_another),
                onClick = actions::startAnotherMatchDay,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** "Bibs won", "Non-bibs won" or "Draw", with the score below in DM Mono. */
@Composable
private fun ResultCard(result: MatchResult) {
    Surface(
        shape = TeamBayesShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StepHeading(stringResource(result.winner.label()))
            Text(stringResource(R.string.score_line, result.scoreA, result.scoreB), style = TeamBayesTypography.numbersLarge)
        }
    }
}

/**
 * A player and their Skill change: "▲ +14" in green, "▼ −9" in red, or "0" in the muted
 * colour. TalkBack hears words, not arrows: "Ottoline, Skill up 14".
 */
@Composable
private fun MovementRow(name: String, change: Int) {
    val colors = MaterialTheme.teamBayesColors
    val (text, color, spoken) = when {
        change > 0 -> Triple(
            stringResource(R.string.movement_up, change),
            colors.green,
            stringResource(R.string.movement_up_spoken, name, change),
        )
        change < 0 -> Triple(
            stringResource(R.string.movement_down, change.absoluteValue),
            colors.red,
            stringResource(R.string.movement_down_spoken, name, change.absoluteValue),
        )
        else -> Triple(
            stringResource(R.string.movement_none),
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.movement_none_spoken, name),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text, style = TeamBayesTypography.numbers, color = color)
    }
}

/** The done step's heading for each result, as in the original web version. */
private fun Outcome.label(): Int = when (this) {
    Outcome.A_WINS -> R.string.result_bibs_won
    Outcome.B_WINS -> R.string.result_non_bibs_won
    Outcome.DRAW -> R.string.result_draw
}

@Preview(showBackground = true, name = "Done")
@Composable
private fun DonePreview() {
    val players = SamplePlayers.all.take(4)
    TeamBayesTheme {
        MatchDayScreen(
            state = MatchDayUiState(
                players = players,
                step = Step.DONE,
                finished = FinishedGame(
                    gameId = "game",
                    result = MatchResult(3, 1, Outcome.A_WINS),
                    movements = listOf(14, 6, 0, -9).mapIndexed { i, change -> Movement(players[i].id, change) },
                ),
            ),
            actions = NoActions,
        )
    }
}
