package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.Side
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import io.github.valnrys.teambayes.ui.theme.TeamBayesTypography
import io.github.valnrys.teambayes.ui.theme.teamBayesColors

/**
 * Step 2, "Pick a split": the balancer's top three proposals as stacked cards.
 *
 * Tap a card to choose it. On the chosen card each player chip is a button that moves
 * them to the other team, which turns the card into an EDITED working copy with live
 * numbers. Every card's chips also have a lock, used by "↻ Re-generate".
 *
 * The cards scroll; the meter line, "↻ Re-generate", Back and Continue are pinned below
 * them, so you can watch the numbers change and move on without scrolling to the end.
 */
@Composable
internal fun PickSplitStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    val editing = state.workingCopy?.mode == Selection.EDITED
    StepLayout(
        contentPadding = contentPadding,
        body = { PickSplitBody(state, actions, editing) },
        footer = {
            // Always present, blank until the first move, so the footer never jumps.
            MeterLine(if (editing) state.liveMeter else null)
            GhostButton(
                text = stringResource(if (state.generating) R.string.regenerating else R.string.regenerate),
                onClick = actions::regenerate,
                enabled = !state.generating,
                modifier = Modifier.fillMaxWidth(),
            )
            BackContinueRow(state.canContinue, onBack = actions::back, onContinue = actions::continueToConfirm)
        },
    )
}

/** The scrolling part of "Pick a split": the title, notes and the three cards. */
@Composable
private fun PickSplitBody(state: MatchDayUiState, actions: MatchDayActions, editing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StepHeading(stringResource(R.string.pick_a_split))
        if (state.sampled) HintText(stringResource(R.string.sampled_note))
        HintText(stringResource(R.string.tap_chip_hint))
        ProblemText(state.problem)
        if (state.splits.isEmpty()) HintText(stringResource(R.string.no_splits))

        // selectableGroup tells TalkBack the cards are one set of choices: "Option 1, 1 of 3".
        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.splits.forEachIndexed { index, split ->
                val chosen = index == state.selectedSplit
                // Only the chosen card shows the hand edits; the others stay as proposed.
                val edited = if (chosen && editing) state.workingCopy else null
                SplitCard(
                    index = index,
                    teamA = edited?.teamA ?: split.teamA,
                    teamB = edited?.teamB ?: split.teamB,
                    // While a side is empty the meter is null and the card says "Quality —".
                    meter = if (edited != null) state.liveMeter else split.meter,
                    chosen = chosen,
                    edited = edited != null,
                    names = state.namesById,
                    locks = state.locks,
                    // While the balancer runs, these proposals are about to be replaced, so
                    // nothing on the cards responds until the new ones arrive.
                    enabled = !state.generating,
                    actions = actions,
                )
            }
        }
    }
}

/**
 * One proposal: "Option 1", its quality as a number and a thin bar, then the two teams.
 *
 * The card is `selectable` with [Role.RadioButton]: the three cards behave like one
 * radio group, and TalkBack reads "Option 1, Quality 83%, …, selected". The chosen card
 * gets the accent border.
 */
@Composable
private fun SplitCard(
    index: Int,
    teamA: List<String>,
    teamB: List<String>,
    meter: Meter?,
    chosen: Boolean,
    edited: Boolean,
    names: Map<String, String>,
    locks: Map<String, Side>,
    enabled: Boolean,
    actions: MatchDayActions,
) {
    OutlinedCard(
        shape = TeamBayesShapes.card,
        border = if (chosen) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clip(TeamBayesShapes.card)
            .selectable(
                selected = chosen,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { actions.selectSplit(index) },
            ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.option_number, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (edited) Badge(stringResource(R.string.edited), MaterialTheme.teamBayesColors.amber)
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = if (meter != null) {
                            stringResource(R.string.quality_percent, percent(meter.quality))
                        } else {
                            stringResource(R.string.quality_unknown)
                        },
                        style = TeamBayesTypography.numbers,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { meter?.quality?.toFloat() ?: 0f },
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
                gapSize = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    // The "Quality 83%" text already says this; don't read it twice.
                    .clearAndSetSemantics {},
            )
            // Round the Bibs' chance, then give the Non-bibs the rest, so the two always
            // add up to 100. (The original web version rounded each side, which could show 51% and 50%.)
            val winA = meter?.let { percent(it.winProbabilityA) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SideColumn(Side.A, teamA, winA, chosen, enabled, names, locks, actions, Modifier.weight(1f))
                SideColumn(Side.B, teamB, winA?.let { 100 - it }, chosen, enabled, names, locks, actions, Modifier.weight(1f))
            }
        }
    }
}

/** One team inside a card: "BIBS · 52%", then a chip per player, each with a lock. */
@Composable
private fun SideColumn(
    side: Side,
    playerIds: List<String>,
    winPercent: Int?,
    chosen: Boolean,
    enabled: Boolean,
    names: Map<String, String>,
    locks: Map<String, Side>,
    actions: MatchDayActions,
    modifier: Modifier,
) {
    val sideName = stringResource(side.label())
    val otherName = stringResource(if (side == Side.A) R.string.non_bibs else R.string.bibs)
    val header = if (winPercent != null) stringResource(R.string.team_with_percent, sideName, winPercent) else sideName
    TeamColumn(header = header, playerIds = playerIds, modifier = modifier) { id ->
        PlayerChip(
            name = names.getValue(id),
            place = sideName,
            moveTo = otherName,
            // Only the chosen card's chips move players; elsewhere a tap chooses the card.
            onMove = if (chosen && enabled) ({ actions.movePlayer(id) }) else null,
            locked = locks[id] == side,
            lockEnabled = enabled,
            onToggleLock = { actions.toggleLock(id, side) },
        )
    }
}

@Preview(showBackground = true, name = "Pick a split, edited")
@Composable
private fun PickSplitPreview() {
    val players = SamplePlayers.all.take(8)
    val ids = players.map { it.id }
    val ratings = players.associate { it.id to it.rating }
    fun option(a: List<String>) = (ids - a.toSet()).let { b -> SplitOption(a, b, checkNotNull(meterFor(ratings, a, b))) }
    val splits = listOf(
        option(listOf("p01", "p04", "p05", "p08")),
        option(listOf("p01", "p03", "p06", "p08")),
        option(listOf("p01", "p02", "p07", "p08")),
    )
    val edited = fromSplit(splits[0].teamA, splits[0].teamB).move("p04")
    TeamBayesTheme {
        MatchDayScreen(
            state = MatchDayUiState(
                players = players,
                selectedIds = ids.toSet(),
                step = Step.GENERATE,
                splits = splits,
                locks = mapOf("p02" to Side.B),
                workingCopy = edited,
                liveMeter = meterFor(ratings, edited.teamA, edited.teamB),
            ),
            actions = NoActions,
        )
    }
}
