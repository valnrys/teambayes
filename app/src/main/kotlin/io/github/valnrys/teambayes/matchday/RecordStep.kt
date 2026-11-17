package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.Team
import io.github.valnrys.teambayes.engine.Outcome
import io.github.valnrys.teambayes.ui.theme.SectionLabel
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import io.github.valnrys.teambayes.ui.theme.TeamBayesTypography
import io.github.valnrys.teambayes.ui.theme.teamBayesColors

/** Lets tests find the record step's scrolling body. */
internal const val RECORD_LIST_TAG = "record-list"

/** Below this width the two goal columns stack, so the 48 dp steppers and names still fit. */
private val TwoColumnMinWidth = 480.dp

/**
 * "Record result": tally the goals, check the score, finalise. Laid out as in the original
 * web version: the three result tiles, the two score boxes, "Adjust score", then the goals.
 *
 * Nothing here is a manual choice of winner. The tiles and score boxes show the result
 * worked out from the goals (see [deriveResult]); only "Adjust score" lets the score be
 * typed, for own goals or an unknown scorer.
 *
 * Every tap is stored at once, so a phone that dies mid-game loses nothing.
 *
 * Divergence from the original web version: on a phone the Bibs and Non-bibs goal lists
 * stack instead of sitting side by side. Two columns of 48 dp "−" and "+" buttons leave
 * too little room for a name at phone width; on a wider screen they sit side by side.
 */
@Composable
internal fun RecordStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    val recording = state.recording ?: return
    val result = recording.result
    StepLayout(
        contentPadding = contentPadding,
        body = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag(RECORD_LIST_TAG)
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StepHeading(stringResource(R.string.record_heading))
                ResultTiles(result)
                ScoreBoxes(recording, onType = actions::setTypedScore)
                FilterChip(
                    selected = recording.adjusted,
                    onClick = actions::toggleAdjustScore,
                    enabled = !recording.finalising,
                    label = {
                        Text(
                            stringResource(if (recording.adjusted) R.string.adjust_score_on else R.string.adjust_score_off),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouchTarget),
                )
                SectionLabel(stringResource(R.string.goals_heading))
                GoalLists(state.namesById, recording, onChange = actions::changeGoals)
            }
        },
        footer = {
            if (!recording.canFinalise) HintText(stringResource(R.string.finalise_hint))
            PrimaryButton(
                text = stringResource(if (recording.finalising) R.string.finalising else R.string.finalise),
                onClick = actions::finalise,
                enabled = recording.canFinalise && !recording.finalising,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * "Win Bibs", "Draw" and "Win Non-bibs", with the current result lit up. They only show
 * the result; they can't be tapped, as in the original web version.
 *
 * **What TalkBack hears.** The three tiles are one stop with one sentence, such as
 * "Result: Bibs winning, 2–1", rather than three words to read and compare. It's a polite
 * live region, so the new result is spoken whenever a goal changes it. This is the only
 * live region on the step, so announcements never compete.
 */
@Composable
private fun ResultTiles(result: MatchResult) {
    val winner = result.winner
    val spoken = stringResource(
        when (winner) {
            Outcome.A_WINS -> R.string.result_spoken_bibs
            Outcome.B_WINS -> R.string.result_spoken_non_bibs
            Outcome.DRAW -> R.string.result_spoken_draw
        },
        result.scoreA,
        result.scoreB,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            R.string.win_bibs to Outcome.A_WINS,
            R.string.draw to Outcome.DRAW,
            R.string.win_non_bibs to Outcome.B_WINS,
        ).forEach { (label, outcome) ->
            val lit = outcome == winner
            val accent = if (outcome == Outcome.DRAW) MaterialTheme.teamBayesColors.amber else MaterialTheme.colorScheme.primary
            Surface(
                shape = TeamBayesShapes.button,
                color = if (lit) accent.copy(alpha = 0.16f) else Color.Transparent,
                contentColor = if (lit) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, if (lit) accent else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget),
            ) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * "Bibs score" and "Non-bibs score". They show the tally and can't be typed in, until
 * "Adjust score" is on.
 */
@Composable
private fun ScoreBoxes(recording: Recording, onType: (Team, String) -> Unit) {
    val result = recording.result
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple(Team.A, R.string.bibs_score, if (recording.adjusted) recording.typedA else result.scoreA.toString()),
            Triple(Team.B, R.string.non_bibs_score, if (recording.adjusted) recording.typedB else result.scoreB.toString()),
        ).forEach { (team, label, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { onType(team, it) },
                label = { Text(stringResource(label)) },
                enabled = recording.adjusted && !recording.finalising,
                singleLine = true,
                textStyle = TeamBayesTypography.numbers,
                // Next from the Bibs box goes to the Non-bibs box; Done there closes the keyboard.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = if (team == Team.A) ImeAction.Next else ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Both teams' goal lists: side by side on a wide screen, one above the other on a phone. */
@Composable
private fun GoalLists(names: Map<String, String>, recording: Recording, onChange: (String, Int) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val teams = listOf(R.string.bibs to recording.teamA, R.string.non_bibs to recording.teamB)
        val column: @Composable (Modifier, Int, List<String>) -> Unit = { modifier, label, ids ->
            GoalColumn(stringResource(label), ids, names, recording, onChange, modifier)
        }
        if (maxWidth >= TwoColumnMinWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                teams.forEach { (label, ids) -> column(Modifier.weight(1f), label, ids) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                teams.forEach { (label, ids) -> column(Modifier.fillMaxWidth(), label, ids) }
            }
        }
    }
}

/** One team's header and a goal row per player, in name order. */
@Composable
private fun GoalColumn(
    header: String,
    playerIds: List<String>,
    names: Map<String, String>,
    recording: Recording,
    onChange: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            header,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        playerIds.sortedBy { names[it]?.lowercase() }.forEach { id ->
            GoalRow(
                name = names[id] ?: stringResource(R.string.empty_column),
                goals = recording.goals[id] ?: 0,
                enabled = !recording.finalising,
                onChange = { change -> onChange(id, change) },
            )
        }
    }
}

/**
 * A player's name with a "−" count "+" stepper. Each button is 48 dp, the smallest
 * comfortable touch target, and named for TalkBack: "Add a goal for Ottoline". The
 * count is read with the name, "Ottoline, 2 goals".
 *
 * At 0 goals "−" is disabled but stays on screen, so TalkBack can still reach it (it's
 * read as disabled) and focus never jumps when the count drops to 0.
 */
@Composable
private fun GoalRow(name: String, goals: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val addLabel = stringResource(R.string.add_goal, name)
    val removeLabel = stringResource(R.string.remove_goal, name)
    val spoken = pluralStringResource(R.plurals.goal_count, goals, name, goals)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = spoken },
        )
        StepperButton(symbol = "−", label = removeLabel, enabled = enabled && goals > 0) { onChange(-1) }
        Text(
            text = goals.toString(),
            style = TeamBayesTypography.numbers,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 24.dp)
                .clearAndSetSemantics {},
        )
        StepperButton(symbol = "+", label = addLabel, enabled = enabled) { onChange(1) }
    }
}

/** A square 48 dp "−" or "+" whose TalkBack name is [label]. */
@Composable
private fun StepperButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = TeamBayesShapes.button,
        modifier = Modifier
            .size(MinTouchTarget)
            .semantics { contentDescription = label },
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Preview(showBackground = true, name = "Record")
@Composable
private fun RecordPreview() {
    val players = SamplePlayers.all.take(8)
    val ids = players.map { it.id }
    TeamBayesTheme {
        MatchDayScreen(
            state = MatchDayUiState(
                players = players,
                step = Step.RECORD,
                recording = Recording("game", ids.take(4), ids.drop(4), goals = mapOf(ids[0] to 2, ids[5] to 1)),
            ),
            actions = NoActions,
        )
    }
}
