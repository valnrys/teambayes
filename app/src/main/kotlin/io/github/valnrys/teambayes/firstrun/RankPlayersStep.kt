package io.github.valnrys.teambayes.firstrun

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.matchday.GhostButton
import io.github.valnrys.teambayes.matchday.ButtonRow
import io.github.valnrys.teambayes.matchday.MinTouchTarget
import io.github.valnrys.teambayes.matchday.PrimaryButton
import io.github.valnrys.teambayes.matchday.ProblemText
import io.github.valnrys.teambayes.matchday.StepHeading
import io.github.valnrys.teambayes.matchday.StepLayout
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import io.github.valnrys.teambayes.ui.theme.TeamBayesTheme
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Lets tests find the ranking list. */
internal const val RANK_LIST_TAG = "first-run-rank-list"

/** How many items sit above the players in the list: just the heading and explanation. */
private const val ITEMS_ABOVE_PLAYERS = 1

/**
 * Step 2, "Put them in rough order": drag the best player to the top.
 *
 * **Dragging.** The [Reorderable](https://github.com/Calvin-LL/Reorderable) library does
 * the dragging. It tells us "item at position 5 moved to position 3" and we pass that to
 * [FirstRunActions.move]. Because the heading is also an item in the list, the library's
 * positions are [ITEMS_ABOVE_PLAYERS] more than the player's position in the ranking.
 *
 * **Save.** **Save players ✓** is pinned below the list. While a save runs, dragging, the
 * move actions and Back are all switched off, so the list can't change under it.
 */
@Composable
internal fun RankPlayersStep(
    state: FirstRunUiState,
    actions: FirstRunActions,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        actions.move(from.index - ITEMS_ABOVE_PLAYERS, to.index - ITEMS_ABOVE_PLAYERS)
    }
    StepLayout(
        contentPadding = contentPadding,
        body = {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(RANK_LIST_TAG),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "header") {
                    StepHeading(stringResource(R.string.first_run_rank_heading), Modifier.padding(top = 16.dp))
                    Text(
                        text = stringResource(R.string.first_run_rank_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(state.names, key = { _, name -> name }) { index, name ->
                    ReorderableItem(reorderableState, key = name) { isDragging ->
                        RankRow(
                            rank = index + 1,
                            name = name,
                            isDragging = isDragging,
                            dragEnabled = !state.busy,
                            // The same function dragging uses, so every way of moving agrees.
                            onMoveUp = { actions.move(index, index - 1) }.takeIf { index > 0 && !state.busy },
                            onMoveDown = { actions.move(index, index + 1) }.takeIf { index < state.names.lastIndex && !state.busy },
                            scope = this,
                        )
                    }
                }
            }
        },
        footer = {
            ProblemText(state.saveProblem)
            ButtonRow {
                GhostButton(
                    text = stringResource(R.string.back),
                    onClick = actions::back,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(if (state.busy) R.string.saving else R.string.first_run_save),
                    onClick = { actions.save() },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

/**
 * One player in the ranking: a number, the name and a drag handle.
 *
 * **What TalkBack hears.** The row is one stop reading "3. Ottoline". Dragging can't be
 * done by a TalkBack user, so the row offers the custom actions "Move up" and "Move down"
 * (found in TalkBack's actions menu), which call [onMoveUp] and [onMoveDown]. An action is
 * left out when it can't apply: nothing moves above the top. The handle is hidden from
 * TalkBack, because it would only be a second stop that does nothing useful.
 *
 * **Dragging.** A long-press on the handle picks the row up, so a brush while scrolling
 * doesn't move anyone.
 *
 * @param dragEnabled `false` while saving or saved.
 * @param onMoveUp moves this player up one place, or `null` at the top.
 * @param onMoveDown moves this player down one place, or `null` at the bottom.
 * @param scope the library's item scope, which provides the drag-handle modifier.
 */
@Composable
private fun RankRow(
    rank: Int,
    name: String,
    isDragging: Boolean,
    dragEnabled: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    scope: ReorderableCollectionItemScope,
) {
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    // The row lifts off the page while it's being dragged.
    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "drag elevation")
    Surface(
        shape = TeamBayesShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = elevation,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    onMoveUp?.let { move -> CustomAccessibilityAction(moveUpLabel) { move(); true } },
                    onMoveDown?.let { move -> CustomAccessibilityAction(moveDownLabel) { move(); true } },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.first_run_rank_row, rank, name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            with(scope) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .longPressDraggableHandle(enabled = dragEnabled)
                        // After the drag modifier, so touch still works but TalkBack never sees the handle.
                        .clearAndSetSemantics {},
                ) {
                    Text("≡", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Rank players")
@Composable
private fun RankPlayersPreview() {
    TeamBayesTheme {
        FirstRunScreen(
            state = FirstRunUiState(
                step = FirstRunStep.RANK,
                names = listOf("Ottoline", "Bramwell", "Zephyra", "Kazimir"),
            ),
            actions = NoFirstRunActions,
        )
    }
}
