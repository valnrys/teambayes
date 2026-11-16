package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme

/**
 * "Make teams manually": everyone starts in the Pool, and each tap moves a player one
 * place round Pool → Bibs → Non-bibs → Pool.
 *
 * The meter line appears as soon as both teams have someone, and only ever comments.
 * Continue unlocks once the pool is empty and both teams have someone, whatever the meter says.
 */
@Composable
internal fun ManualStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    val board = state.workingCopy ?: return
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StepHeading(stringResource(R.string.make_teams_manually))
        HintText(stringResource(R.string.manual_hint))
        MeterLine(state.liveMeter)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Place.POOL to board.pool,
                Place.BIBS to board.teamA,
                Place.NON_BIBS to board.teamB,
            ).forEach { (place, ids) ->
                val placeName = stringResource(place.label())
                TeamColumn(header = placeName, playerIds = ids, modifier = Modifier.weight(1f)) { id ->
                    PlayerChip(
                        name = state.namesById.getValue(id),
                        place = placeName,
                        moveTo = board.nextPlace(id)?.let { stringResource(it.label()) },
                        onMove = { actions.movePlayer(id) },
                    )
                }
            }
        }

        BackContinueRow(state.canContinue, onBack = actions::back, onContinue = actions::continueToConfirm)
    }
}

@Preview(showBackground = true, name = "Manual board")
@Composable
private fun ManualPreview() {
    val players = SamplePlayers.all.take(7)
    val board = fromRoster(players.map { it.id }).move("p01").move("p02").move("p02").move("p03")
    TeamBayesTheme {
        MatchDayScreen(
            state = MatchDayUiState(
                players = players,
                step = Step.MANUAL,
                workingCopy = board,
                liveMeter = meterFor(players.associate { it.id to it.rating }, board.teamA, board.teamB),
            ),
            actions = NoActions,
        )
    }
}
