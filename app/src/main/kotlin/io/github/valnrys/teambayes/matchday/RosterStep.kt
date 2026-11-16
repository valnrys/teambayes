package io.github.valnrys.teambayes.matchday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme

/** Roster rows are a little taller than the 48 dp minimum, as in the original web version's checklist. */
private val RosterRowHeight = 52.dp

/**
 * Step 1, "Who's playing?": tick players, search, add someone new, then generate teams
 * or make them by hand.
 *
 * A [LazyColumn] only builds the rows on screen, so a long roster stays smooth. The
 * header, fields and buttons are single `item`s in the same list, so the whole step
 * scrolls as one page.
 */
@Composable
internal fun RosterStep(state: MatchDayUiState, actions: MatchDayActions, contentPadding: PaddingValues) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { RosterHeader(selectedCount = state.selectedIds.size) }
        item {
            OutlinedTextField(
                value = state.search,
                onValueChange = actions::setSearch,
                // A real label, not a placeholder: it stays visible (floating above the
                // text) once you start typing, and TalkBack reads it as the field's name.
                label = { Text(stringResource(R.string.search_players)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // `key` lets Compose track each row by player, not by position, so a tick stays
        // on the right person while the search narrows the list.
        items(state.visiblePlayers, key = { it.id }) { player ->
            PlayerRow(
                name = player.name,
                checked = player.id in state.selectedIds,
                onToggle = { actions.toggleSelected(player.id) },
            )
        }
        if (state.visiblePlayers.isEmpty()) {
            item { HintText(stringResource(R.string.no_players_match)) }
        }
        item { NewPlayerField(state, actions) }
        item { ProblemText(state.problem) }
        item {
            ButtonRow {
                GhostButton(
                    text = stringResource(R.string.make_teams_manually),
                    onClick = actions::makeTeamsManually,
                    enabled = state.canMakeTeams,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = if (state.generating) {
                        stringResource(R.string.generating)
                    } else {
                        stringResource(R.string.generate_teams, state.selectedIds.size)
                    },
                    onClick = actions::generate,
                    enabled = state.canMakeTeams && !state.generating,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Room below the buttons, so they don't sit hard against the navigation bar.
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** "WHO'S PLAYING?" with the "{n} selected" pill beside it. */
@Composable
private fun RosterHeader(selectedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepHeading(stringResource(R.string.roster_heading))
        Surface(
            shape = TeamBayesShapes.chip,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Text(
                text = pluralStringResource(R.plurals.selected_count, selectedCount, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    // Say the new count after each tick, once TalkBack has read the row.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * One player: a checkbox and a name, the whole row one big target.
 *
 * [toggleable] makes the row a single checkbox for TalkBack ("Ottoline, checkbox,
 * checked"). The [Checkbox] inside only draws the tick (its `onCheckedChange` is null),
 * so a tap is never handled twice.
 */
@Composable
private fun PlayerRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RosterRowHeight)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * "+ New player": type a name, then Add or the keyboard's Done. A name already on the
 * roster shows "Player 'Sam' already exists" under the field, marked as an error so
 * TalkBack reads it with the field.
 */
@Composable
private fun NewPlayerField(state: MatchDayUiState, actions: MatchDayActions) {
    val duplicate = state.duplicateName
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = state.newPlayerName,
            onValueChange = actions::setNewPlayerName,
            label = { Text(stringResource(R.string.new_player)) },
            singleLine = true,
            isError = duplicate != null,
            supportingText = duplicate?.let { { Text(stringResource(R.string.player_already_exists, it)) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { actions.addPlayer() }),
            modifier = Modifier.weight(1f),
        )
        GhostButton(
            text = stringResource(R.string.add),
            onClick = actions::addPlayer,
            enabled = state.newPlayerName.isNotBlank(),
            // Line the button up with the text box, below the label's floating space.
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Preview(showBackground = true, name = "Roster")
@Composable
private fun RosterPreview() {
    val players = SamplePlayers.all.sortedBy { it.name }
    TeamBayesTheme {
        MatchDayScreen(
            state = MatchDayUiState(players, selectedIds = players.take(9).map { it.id }.toSet(), duplicateName = "Tamsin"),
            actions = NoActions,
        )
    }
}
