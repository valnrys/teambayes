package io.github.valnrys.teambayes.matchday

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Lets tests find the sheet's ranking list. */
internal const val PLACEMENT_LIST_TAG = "placement-list"

/** The list key of the new player's row. Player ids are UUIDs, so it can't clash. */
private const val NEW_PLAYER_KEY = "new-player"

/** How many rows to keep showing above the new player when the list scrolls to them. */
private const val ROWS_OF_CONTEXT = 2

/**
 * "Where do they fit?": place a player added on match day among the others, so they start
 * with a sensible rating instead of an average guess.
 *
 * The list is everyone on the roster, best first by current rating, with the new player
 * highlighted somewhere among them. Only the new player moves: drag them by the handle,
 * or use TalkBack's "Move up" and "Move down". **Save ✓** stores them with a starting mu
 * between their two neighbours (see [startingMuAt]).
 *
 * **Dragging.** The same [Reorderable](https://github.com/Calvin-LL/Reorderable) library
 * as first run's ranking. Every row is a `ReorderableItem`, so the others slide out of the
 * way, but only the new player's row has a handle. The list holds only the ranking, so
 * the library's positions are places in the ranking.
 *
 * **Why the sheet can't be swiped down.** Dragging a row down would fight the sheet's own
 * swipe-to-close. Cancel, Back and tapping outside still close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlacementSheet(state: MatchDayUiState, placing: NewPlayerPlacement, actions: MatchDayActions) {
    // Read through rememberUpdatedState: the sheet state below is remembered once, but must
    // see whether a save is running now, not when the sheet opened.
    val saving by rememberUpdatedState(placing.saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // While saving, the sheet stays open: closing it would look like a cancel that isn't one.
        confirmValueChange = { it != SheetValue.Hidden || !saving },
    )
    ModalBottomSheet(
        onDismissRequest = actions::cancelNewPlayer,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // The question stays put above the list, so it's on screen however far the
            // list has scrolled to show the new player.
            StepHeading(stringResource(R.string.placement_heading))
            Text(
                text = stringResource(R.string.placement_explainer, placing.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            PlacementList(
                ranking = state.ranking,
                placing = placing,
                onMove = actions::moveNewPlayer,
                // weight(fill = false): as tall as the list needs, but the buttons always fit.
                modifier = Modifier.weight(1f, fill = false),
            )
            Box(modifier = Modifier.padding(vertical = 12.dp)) {
                ButtonRow {
                    GhostButton(
                        text = stringResource(R.string.cancel),
                        onClick = actions::cancelNewPlayer,
                        enabled = !placing.saving,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = stringResource(if (placing.saving) R.string.saving else R.string.save),
                        onClick = actions::saveNewPlayer,
                        enabled = !placing.saving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The ranking, with the new player at [NewPlayerPlacement.position]. */
@Composable
private fun PlacementList(
    ranking: List<Player>,
    placing: NewPlayerPlacement,
    onMove: (position: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pulled into range in case the roster changed under the sheet.
    val position = placing.position.coerceIn(0, ranking.size)
    // Each row is a player, or null for the new player, who has no id until saved.
    val rows: List<Player?> = ranking.toMutableList<Player?>().apply { add(position, null) }
    // Open with the new player in view, a couple of rows down so their neighbours show too.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (position - ROWS_OF_CONTEXT).coerceAtLeast(0))
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        // Only the new player has a handle, so they're always the one being dragged.
        if (from.key == NEW_PLAYER_KEY) onMove(to.index)
    }
    // After "Move up" or "Move down", scroll if the new player has left the screen. Never
    // during a drag: the library scrolls the list itself then, and two scrolls would fight.
    LaunchedEffect(position) {
        if (reorderableState.isAnyItemDragging) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        // In view with a row either side, or at an end of the list.
        val fullyShown = visible.any { it.index == position } &&
            (position == 0 || visible.first().index < position) &&
            (position == rows.lastIndex || visible.last().index > position)
        if (!fullyShown) listState.animateScrollToItem((position - ROWS_OF_CONTEXT).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(PLACEMENT_LIST_TAG),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(rows, key = { _, player -> player?.id ?: NEW_PLAYER_KEY }) { index, player ->
            ReorderableItem(reorderableState, key = player?.id ?: NEW_PLAYER_KEY) { isDragging ->
                if (player == null) {
                    NewPlayerRow(
                        rank = index + 1,
                        name = placing.name,
                        isDragging = isDragging,
                        onMoveUp = { onMove(index - 1) }.takeIf { index > 0 && !placing.saving },
                        onMoveDown = { onMove(index + 1) }.takeIf { index < rows.lastIndex && !placing.saving },
                        scope = this,
                    )
                } else {
                    RankedRow(rank = index + 1, name = player.name)
                }
            }
        }
    }
}

/** Someone already on the roster: a number and a name. Not draggable. */
@Composable
private fun RankedRow(rank: Int, name: String) {
    Surface(
        shape = TeamBayesShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.placement_row, rank, name),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * The new player: highlighted, with a "New" badge and a drag handle.
 *
 * **What TalkBack hears.** One stop, "3. Aurelio, new player", with the custom actions
 * "Move up" and "Move down" (left out at the ends), as on first run's ranking. The handle
 * is hidden from TalkBack: it would be a second stop that does nothing a TalkBack user
 * can use.
 *
 * @param onMoveUp moves the new player up one place, or `null` when they can't move up.
 * @param onMoveDown moves them down one place, or `null` when they can't move down.
 * @param scope the library's item scope, which provides the drag-handle modifier.
 */
@Composable
private fun NewPlayerRow(
    rank: Int,
    name: String,
    isDragging: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    scope: ReorderableCollectionItemScope,
) {
    val description = stringResource(R.string.placement_new_row, rank, name)
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    // The row lifts off the page while it's being dragged.
    val elevation by animateDpAsState(if (isDragging) 6.dp else 1.dp, label = "drag elevation")
    Surface(
        shape = TeamBayesShapes.card,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = elevation,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.placement_row, rank, name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Badge(stringResource(R.string.placement_new), MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.weight(1f))
            with(scope) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(MinTouchTarget)
                        // Touch only: the row's semantics above already hide it from TalkBack.
                        .longPressDraggableHandle(),
                ) {
                    Text("≡", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
