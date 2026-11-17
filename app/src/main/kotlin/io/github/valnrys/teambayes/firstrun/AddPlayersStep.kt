package io.github.valnrys.teambayes.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.BuildConfig
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.matchday.GhostButton
import io.github.valnrys.teambayes.matchday.HintText
import io.github.valnrys.teambayes.matchday.MinTouchTarget
import io.github.valnrys.teambayes.matchday.PrimaryButton
import io.github.valnrys.teambayes.matchday.StepHeading
import io.github.valnrys.teambayes.matchday.StepLayout
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme

/** Lets tests find the name field. */
internal const val NAME_FIELD_TAG = "first-run-name-field"

/**
 * Step 1, "Add your players": a short explanation, a name field with Add, and the names
 * so far with a remove button each.
 *
 * Everything above the footer scrolls together in one [LazyColumn], so even a long list
 * or a large font size can be scrolled to the end. **Next →** is pinned below it (and
 * hides while the keyboard is open; see [StepLayout]).
 */
@Composable
internal fun AddPlayersStep(state: FirstRunUiState, actions: FirstRunActions, contentPadding: PaddingValues) {
    StepLayout(
        contentPadding = contentPadding,
        body = {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                item(key = "header") {
                    StepHeading(stringResource(R.string.first_run_add_heading), Modifier.padding(top = 16.dp))
                    Text(
                        text = stringResource(R.string.first_run_add_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // A stable key keeps the field the same item as the list below it grows, so it
                // keeps focus and the keyboard stays open after each Add.
                item(key = "name-field") { NameField(state, actions) }
                // Debug builds only. BuildConfig.DEBUG is a compile-time constant, false in
                // release builds, so the button never reaches the released app; a release
                // user can't load made-up players into their real list by accident.
                if (BuildConfig.DEBUG) {
                    item(key = "samples") {
                        TextButton(onClick = actions::loadSamplePlayers) {
                            Text(stringResource(R.string.first_run_load_samples))
                        }
                    }
                }
                if (state.names.isEmpty()) {
                    item(key = "empty") { HintText(stringResource(R.string.first_run_empty)) }
                }
                // Names are unique ignoring case, so each one is its own stable key.
                items(state.names, key = { it }) { name ->
                    NameRow(name = name, onRemove = { actions.removePlayer(name) })
                }
            }
        },
        footer = {
            if (state.belowMatchDayMinimum) {
                HintText(
                    stringResource(
                        if (state.canContinue) R.string.first_run_match_day_hint else R.string.first_run_few_players_hint,
                    ),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.first_run_next),
                onClick = actions::next,
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * The name field and its Add button. A name already in the list shows "Player 'Sam'
 * already exists" under the field, marked as an error so TalkBack reads it with the field.
 */
@Composable
private fun NameField(state: FirstRunUiState, actions: FirstRunActions) {
    val duplicate = state.duplicateName
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = actions::setDraft,
            label = { Text(stringResource(R.string.first_run_name_label)) },
            singleLine = true,
            isError = duplicate != null,
            supportingText = duplicate?.let { { Text(stringResource(R.string.player_already_exists, it)) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            // With text, Done adds the player. With a blank field it just closes the keyboard,
            // which brings the pinned Next button back (see StepLayout).
            keyboardActions = KeyboardActions(
                onDone = { if (state.draft.isBlank()) keyboard?.hide() else actions.addPlayer() },
            ),
            modifier = Modifier
                .weight(1f)
                .testTag(NAME_FIELD_TAG),
        )
        GhostButton(
            text = stringResource(R.string.add),
            onClick = actions::addPlayer,
            enabled = state.draft.isNotBlank(),
            // Line the button up with the text box, below the label's floating space.
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One added player: the name, and a remove button named "Remove {name}" for TalkBack. */
@Composable
private fun NameRow(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val description = stringResource(R.string.first_run_remove, name)
        IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = description }) {
            // A plain ✕ glyph, as the app does for its lock icons; hidden from TalkBack, which uses the button's name.
            Text("✕", modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Preview(showBackground = true, name = "Add players")
@Composable
private fun AddPlayersPreview() {
    TeamBayesTheme {
        FirstRunScreen(
            state = FirstRunUiState(names = listOf("Ottoline", "Bramwell", "Zephyra"), duplicateName = "Ottoline"),
            actions = NoFirstRunActions,
        )
    }
}
