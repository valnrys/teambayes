package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.data.Selection
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import io.github.valnrys.teambayes.ui.theme.teamBayesColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Confirm teams": a last look, with a badge saying how the teams were made.
 *
 * If the teams differ by more than one player the button turns amber and asks
 * "Teams uneven (6 v 4) — sure?". It's a nudge, not a lock: the button still works.
 *
 * The date defaults to today; tap it to pick another, for a game entered late.
 *
 * The teams scroll if they're long; Back and Confirm are pinned below them.
 */
@Composable
internal fun ConfirmStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    val chosen = state.chosen ?: return
    val warning = sizeWarning(chosen.teamA.size, chosen.teamB.size)
    StepLayout(
        contentPadding = contentPadding,
        body = { ConfirmSummary(state, chosen, onDateChange = actions::setPlayDate) },
        footer = {
            ButtonRow {
                GhostButton(
                    stringResource(R.string.back),
                    onClick = actions::back,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                )
                if (state.saving) {
                    PrimaryButton(stringResource(R.string.saving), onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                } else if (warning == null) {
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

/** The scrolling part of "Confirm teams": the title, the badge, the date and both teams. */
@Composable
private fun ConfirmSummary(state: MatchDayUiState, chosen: ChosenTeams, onDateChange: (LocalDate) -> Unit) {
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

        DateRow(date = state.playDate, enabled = !state.saving, onDateChange = onDateChange)

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

/**
 * "Date" and the day the game is played, such as "Tue, 17 Nov 2026", in the phone's own
 * language and order. Tapping the row opens Material's date picker.
 *
 * **Dates and the picker.** The picker speaks in milliseconds at midnight UTC, so the day
 * is turned into that and back using UTC, never the phone's time zone; otherwise the
 * picker could show the day before. What gets stored is a different moment again: local
 * noon on that day (see [toPlayedAt]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRow(date: LocalDate, enabled: Boolean, onDateChange: (LocalDate) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val shown = remember(date) { date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    val changeLabel = stringResource(R.string.change_date)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            // clickable also makes the row one TalkBack stop: "Date, Tue, 17 Nov 2026".
            .clickable(enabled = enabled, onClickLabel = changeLabel, role = Role.Button) { picking = true },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.play_date), style = MaterialTheme.typography.bodyLarge)
        Text(shown, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }

    if (picking) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMidnightMillis(),
            selectableDates = UpToToday,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDateChange(utcMidnightMillisToDate(it)) }
                        picking = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Today and earlier only. Games are replayed in date order, so a game dated in the future
 * would count after games that were really played before it.
 */
@OptIn(ExperimentalMaterial3Api::class)
private object UpToToday : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= LocalDate.now().toUtcMidnightMillis()

    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
}

/** The date picker's form of a day: milliseconds at midnight UTC. */
private fun LocalDate.toUtcMidnightMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMidnightMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

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
