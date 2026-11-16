package io.github.valnrys.teambayes.tonight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.Split
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import kotlin.math.roundToInt

/** The smallest comfortable touch target, in dp, as recommended by Material and Android accessibility guidance. */
private val MinTouchTarget = 48.dp

/**
 * The "Tonight" screen with its [TonightViewModel] already wired up.
 *
 * This is the only function in the file that knows about the ViewModel. Everything it
 * draws is in [TonightScreen], which is easy to preview and test because it only takes
 * plain values.
 */
@Composable
fun TonightRoute(viewModel: TonightViewModel = viewModel()) {
    // collectAsStateWithLifecycle stops listening while the app is in the background.
    val state by viewModel.state.collectAsStateWithLifecycle()
    TonightScreen(
        state = state,
        onToggle = viewModel::toggle,
        onBalance = viewModel::balance,
        onReroll = viewModel::reroll,
    )
}

/**
 * Pick who is playing, balance them into two teams, and flip through other options.
 *
 * Stateless: it draws [state] and reports taps through the lambdas. It owns nothing.
 *
 * @param state what to draw.
 * @param onToggle called with a player's id when their row is tapped.
 * @param onBalance called when Balance is tapped.
 * @param onReroll called when Try another is tapped.
 */
@Composable
fun TonightScreen(
    state: TonightUiState,
    onToggle: (playerId: String) -> Unit,
    onBalance: () -> Unit,
    onReroll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The app draws edge to edge, so Scaffold tells us how much the system bars cover.
    // Using it as the list's contentPadding keeps rows clear of the bars while still
    // letting them scroll underneath.
    // safeDrawing also keeps content clear of the camera cutout in landscape.
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.tonight_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .semantics { heading() },
                )
            }
            item {
                BalanceBar(
                    playingCount = state.playingCount,
                    isBalancing = state.result == TonightResult.Balancing,
                    onBalance = onBalance,
                )
            }
            item {
                ResultSection(state = state, onReroll = onReroll)
            }
            item {
                Text(
                    text = stringResource(R.string.players_heading),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { heading() },
                )
            }
            // `key` lets Compose track each row by player, not by position.
            items(state.players, key = { it.id }) { player ->
                PlayerRow(
                    name = player.name,
                    ticked = player.id in state.ticked,
                    onToggle = { onToggle(player.id) },
                )
            }
        }
    }
}

/** The "8 playing" count with the Balance button beside it. */
@Composable
private fun BalanceBar(playingCount: Int, isBalancing: Boolean, onBalance: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = pluralStringResource(R.plurals.playing_count, playingCount, playingCount),
            style = MaterialTheme.typography.titleMedium,
        )
        // No contentDescription: the visible word is the accessible name, so Voice Access
        // users can say what they see.
        Button(onClick = onBalance, enabled = !isBalancing) {
            Text(stringResource(R.string.balance))
        }
    }
}

/**
 * One player: a checkbox and a name.
 *
 * The whole row is the touch target, not only the little box. [toggleable] makes the row
 * a single checkbox for TalkBack, which reads it as "Ottoline, checkbox, checked". The
 * Checkbox inside only draws the state (its own `onCheckedChange` is null), so a tap
 * is never handled twice.
 */
@Composable
private fun PlayerRow(name: String, ticked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(value = ticked, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Whatever the balancer last produced: nothing, a wait message, a problem, or the teams. */
@Composable
private fun ResultSection(state: TonightUiState, onReroll: () -> Unit) {
    when (val result = state.result) {
        TonightResult.Idle -> Unit
        TonightResult.Balancing -> Text(
            text = stringResource(R.string.balancing),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is TonightResult.Problem -> Text(
            text = stringResource(result.message),
            color = MaterialTheme.colorScheme.error,
            // Speak the message as soon as it appears, without the user hunting for it.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is TonightResult.Teams -> TeamsSection(state.players, result, onReroll)
    }
}

/** Two team cards, the win chances, and the button to flip to the next option. */
@Composable
private fun TeamsSection(players: List<TonightPlayer>, teams: TonightResult.Teams, onReroll: () -> Unit) {
    val namesById = remember(players) { players.associate { it.id to it.name } }
    // Round A, then take B as the rest, so the two always add up to exactly 100.
    val winA = (teams.split.winProbabilityA * 100).roundToInt()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamCard(stringResource(R.string.team_a), teams.split.teamA.map { namesById.getValue(it) })
        TeamCard(stringResource(R.string.team_b), teams.split.teamB.map { namesById.getValue(it) })
        Text(
            text = stringResource(R.string.win_chance, winA, 100 - winA),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.option_of, teams.index + 1, teams.total),
                style = MaterialTheme.typography.bodyMedium,
                // Announce the new option when teams appear or are re-rolled.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            // With a single option there is nothing else to show.
            OutlinedButton(onClick = onReroll, enabled = teams.total > 1) {
                Text(stringResource(R.string.try_another))
            }
        }
    }
}

/** A titled card listing one team's players. */
@Composable
private fun TeamCard(title: String, names: List<String>) {
    // mergeDescendants makes the card one TalkBack stop that reads the title and all names.
    Card(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            names.forEach { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Previews: one per state, so each can be checked in Android Studio without running the app.
// ---------------------------------------------------------------------------------------

private val previewPlayers = SamplePlayers.tonight
private val previewTicked = previewPlayers.take(8).map { it.id }.toSet()

private val previewSplit = Split(
    teamA = listOf("p01", "p04", "p05", "p08"),
    teamB = listOf("p02", "p03", "p06", "p07"),
    drawProbability = 0.3,
    winProbabilityA = 0.52,
)
private val previewSplits = List(5) { previewSplit }

@Composable
private fun PreviewOf(result: TonightResult, darkTheme: Boolean = false) {
    TeamBayesTheme(darkTheme = darkTheme) {
        TonightScreen(
            state = TonightUiState(previewPlayers, previewTicked, result),
            onToggle = {},
            onBalance = {},
            onReroll = {},
        )
    }
}

@Preview(showBackground = true, name = "Idle")
@Composable
private fun IdlePreview() = PreviewOf(TonightResult.Idle)

@Preview(showBackground = true, name = "Balancing")
@Composable
private fun BalancingPreview() = PreviewOf(TonightResult.Balancing)

@Preview(showBackground = true, name = "Teams")
@Composable
private fun TeamsPreview() = PreviewOf(TonightResult.Teams(previewSplits, index = 1))

@Preview(showBackground = true, name = "Teams, dark")
@Composable
private fun TeamsDarkPreview() = PreviewOf(TonightResult.Teams(previewSplits, index = 1), darkTheme = true)

@Preview(showBackground = true, name = "Problem")
@Composable
private fun ProblemPreview() = PreviewOf(TonightResult.Problem(R.string.problem_too_few))
