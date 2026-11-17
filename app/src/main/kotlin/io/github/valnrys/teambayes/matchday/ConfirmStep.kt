package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import io.github.valnrys.teambayes.ui.theme.teamBayesColors

/**
 * "Confirm teams": a last look, with a badge saying how the teams were made.
 *
 * If the teams differ by more than one player the button turns amber and asks
 * "Teams uneven (6 v 4) — sure?". It's a nudge, not a lock: the button still works.
 *
 * Divergence from the original web version: there's no date field yet. Nothing is saved until the
 * next milestone, so a date would be thrown away; it arrives with storage.
 *
 * The teams scroll if they're long; Back and Confirm are pinned below them.
 */
@Composable
internal fun ConfirmStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    val chosen = state.chosen ?: return
    val warning = sizeWarning(chosen.teamA.size, chosen.teamB.size)
    StepLayout(
        contentPadding = contentPadding,
        body = { ConfirmSummary(state, chosen) },
        footer = {
            ButtonRow {
                GhostButton(stringResource(R.string.back), onClick = actions::back, modifier = Modifier.weight(1f))
                if (warning == null) {
                    PrimaryButton(stringResource(R.string.confirm), onClick = actions::confirm, modifier = Modifier.weight(1f))
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.teams_uneven, warning.bigger, warning.smaller),
                        onClick = actions::confirm,
                        containerColor = MaterialTheme.teamBayesColors.amber,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

/** The scrolling part of "Confirm teams": the title, the badge and both teams. */
@Composable
private fun ConfirmSummary(state: MatchDayUiState, chosen: ChosenTeams) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepHeading(stringResource(R.string.confirm_teams))
            Badge(stringResource(chosen.selection.label()), MaterialTheme.colorScheme.primary)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.bibs to chosen.teamA, R.string.non_bibs to chosen.teamB).forEach { (label, ids) ->
                val sideName = stringResource(label)
                TeamColumn(header = sideName, playerIds = ids, modifier = Modifier.weight(1f)) { id ->
                    PlayerChip(name = state.namesById.getValue(id), place = sideName)
                }
            }
        }
    }
}

/** The badge word for each way of making teams. */
private fun Selection.label(): Int = when (this) {
    Selection.GENERATED -> R.string.selection_generated
    Selection.EDITED -> R.string.selection_edited
    Selection.MANUAL -> R.string.selection_manual
}

@Preview(showBackground = true, name = "Confirm, uneven")
@Composable
private fun ConfirmPreview() {
    val players = SamplePlayers.all.take(10)
    val ids = players.map { it.id }
    TeamBayesTheme {
        MatchDayScreen(
            state = MatchDayUiState(
                players = players,
                step = Step.CONFIRM,
                chosen = ChosenTeams(ids.take(6), ids.drop(6), Selection.MANUAL),
            ),
            actions = NoActions,
        )
    }
}
