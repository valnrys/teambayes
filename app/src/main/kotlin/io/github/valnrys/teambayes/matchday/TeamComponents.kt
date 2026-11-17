package io.github.valnrys.teambayes.matchday

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import io.github.valnrys.teambayes.R
import io.github.valnrys.teambayes.engine.Side
import io.github.valnrys.teambayes.ui.theme.SectionLabel
import io.github.valnrys.teambayes.ui.theme.TeamBayesShapes
import io.github.valnrys.teambayes.ui.theme.TeamBayesTypography
import kotlin.math.roundToInt

// Pieces shared by more than one match-day step: team columns, player chips, badges,
// the balance meter line and the Back / Continue row.

/** How faint a disabled control is drawn, Material's standard 38%. */
private const val DisabledAlpha = 0.38f

/** The smallest comfortable touch target, as Material and Android accessibility guidance recommend. */
internal val MinTouchTarget = 48.dp

/** A probability as a whole percentage, 0.523 → 52. */
internal fun percent(probability: Double): Int = (probability * 100).roundToInt()

/** The display name of a side: "Bibs" or "Non-bibs". */
@StringRes
internal fun Side.label(): Int = if (this == Side.A) R.string.bibs else R.string.non_bibs

/** The display name of a place on the manual board. */
@StringRes
internal fun Place.label(): Int = when (this) {
    Place.POOL -> R.string.pool
    Place.BIBS -> R.string.bibs
    Place.NON_BIBS -> R.string.non_bibs
}

/**
 * A step's title, such as "Who's playing?", in the serif heading face and title case, as
 * the original web version showed step titles. Small uppercase labels use [SectionLabel]
 * instead. Marked as a heading so TalkBack users can jump between steps.
 */
@Composable
internal fun StepHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A problem to show in plain words, such as "Those locks can't all be met".
 * A polite live region makes TalkBack read it out when it appears, without the user hunting for it.
 */
@Composable
internal fun ProblemText(@StringRes message: Int?) {
    if (message == null) return
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A smaller line of help text, such as "Tap a player chip to swap their team." */
@Composable
internal fun HintText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A small outlined pill with an uppercase word: the amber EDITED badge on a card, or the
 * GENERATED / EDITED / MANUAL badge on the confirm step.
 */
@Composable
internal fun Badge(text: String, color: Color) {
    Surface(
        shape = TeamBayesShapes.chip,
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(1.dp, color),
    ) {
        Text(
            // Locale.ROOT makes the casing explicit and independent of the phone's language, as a defensive habit.
            text = text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * One team as a column: a header such as "BIBS · 52%", then one [chip] per player.
 *
 * The chips are a *slot*: the caller passes a composable that draws one player. That lets
 * the same column hold tappable chips with locks (the chosen proposal), plain names (other
 * proposals and the confirm step) or manual-board chips, without a pile of flags here.
 *
 * @param header the column title, already formatted.
 * @param playerIds who to show, in order.
 * @param chip draws the player with the given id.
 */
@Composable
internal fun TeamColumn(
    header: String,
    playerIds: List<String>,
    modifier: Modifier = Modifier,
    chip: @Composable (playerId: String) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(text = header)
        playerIds.forEach { id -> chip(id) }
        if (playerIds.isEmpty()) {
            Text(stringResource(R.string.empty_column), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One player as a pill-shaped chip, and optionally a lock beside it.
 *
 * **What TalkBack hears.** Every chip is one stop named "{name}, {side}", such as
 * "Ottoline, Bibs", so the side is spoken even on the confirm summary and the unchosen
 * cards. The chip merges its contents into that one description and hides the inner
 * text, so the name isn't read twice. The visible name comes first, so Voice Access users
 * can still say what they see.
 *
 * **Tapping.** With [onMove], the chip is also a button, and `onClickLabel` replaces
 * TalkBack's generic "double-tap to activate" with what the tap does: "double-tap to move
 * to Non-bibs". Without [onMove] a tap falls through to whatever is underneath (on an
 * unchosen card, that chooses the card).
 *
 * **Locking.** With [locked] set, a 48 dp lock sits beside the chip, a separate target, so
 * moving and locking can't be confused. It's a toggle named "Lock Ottoline to Bibs";
 * TalkBack adds "on" or "off". [lockEnabled] greys it out while the balancer runs.
 *
 * @param name the player's name.
 * @param place where they are now, such as "Bibs".
 * @param moveTo where a tap sends them, such as "Non-bibs". Only used with [onMove].
 * @param onMove moves them, or `null` if this chip can't be tapped.
 * @param locked `true` if locked to this side, `false` if not, `null` for no lock control.
 * @param lockEnabled `false` to show the lock but ignore taps.
 * @param onToggleLock taps the lock.
 */
@Composable
internal fun PlayerChip(
    name: String,
    place: String,
    modifier: Modifier = Modifier,
    moveTo: String? = null,
    onMove: (() -> Unit)? = null,
    locked: Boolean? = null,
    lockEnabled: Boolean = true,
    onToggleLock: () -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val chipDescription = stringResource(R.string.chip_name, name, place)
        val moveLabel = moveTo?.let { stringResource(R.string.move_to, it) }
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = if (onMove != null) MinTouchTarget else 36.dp)
                .clip(TeamBayesShapes.chip)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (onMove != null) {
                        Modifier.clickable(onClickLabel = moveLabel, role = Role.Button, onClick = onMove)
                    } else {
                        Modifier
                    },
                )
                .semantics(mergeDescendants = true) { contentDescription = chipDescription }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // The chip's description already says the name; don't read it twice.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        if (locked != null) {
            LockToggle(name = name, place = place, locked = locked, enabled = lockEnabled, onToggle = onToggleLock)
        }
    }
}

/**
 * The 🔓/🔒 beside a chip, as in the original web version.
 *
 * Emoji draw in their own colours and can't be tinted, so a locked padlock also sits on
 * an accent-tinted circle: the state shows in colour as well as in the icon's shape.
 * (Material's lock icons could be tinted, but the open padlock lives in the large
 * extended-icons library, which isn't worth adding for one glyph.)
 */
@Composable
private fun LockToggle(name: String, place: String, locked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val description = stringResource(R.string.lock_to, name, place)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(MinTouchTarget)
            .toggleable(value = locked, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            // Material's standard look for a disabled control: 38% opacity.
            .alpha(if (enabled) 1f else DisabledAlpha),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (locked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
        ) {
            Text(
                text = stringResource(if (locked) R.string.lock_locked else R.string.lock_unlocked),
                // The emoji is decorative to TalkBack: the toggle's name and on/off state say it all.
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * The model's running commentary on hand-made teams: "Model: 64% Bibs · quality 83%".
 *
 * It names whichever side the model favours. It's commentary, never a gate: the
 * organiser can always overrule the model, so it never disables anything. A polite live
 * region means TalkBack reads the new numbers after each move, once it has finished what
 * it was saying.
 *
 * The percentages are worked out exactly as on the cards (round the Bibs, give the
 * Non-bibs the rest), so the line and the card never disagree by a point.
 *
 * Divergence from the original web version: quality is a percentage here, matching the
 * cards, rather than a decimal such as "0.83" beside cards showing "83%".
 *
 * While [meter] is `null` (nothing edited yet, or a side still empty) it draws an empty
 * line of the same height. The line is always there, so the pinned footer doesn't jump
 * when the first move brings the numbers in, and TalkBack hears the change from one live
 * region rather than a node appearing.
 */
@Composable
internal fun MeterLine(meter: Meter?) {
    val style = TeamBayesTypography.numbers.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
    val liveRegion = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    // One Text node whether or not there's a meter, so it stays the same live region.
    val text = if (meter == null) "" else {
        val bibsFavoured = meter.winProbabilityA >= 0.5
        val winA = percent(meter.winProbabilityA)
        val sidePercent = if (bibsFavoured) winA else 100 - winA
        val side = stringResource(if (bibsFavoured) R.string.bibs else R.string.non_bibs)
        stringResource(R.string.meter_line, sidePercent, side, percent(meter.quality))
    }
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = liveRegion,
    )
}

/**
 * A row of buttons sharing the width equally, as in the original web version. Each button
 * takes [RowScope.weight] so long labels wrap instead of pushing the other off screen.
 */
@Composable
internal fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A filled button in the app's shape and height. Material's default is a shorter pill. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = TeamBayesShapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = modifier.heightIn(min = TeamBayesShapes.buttonMinHeight),
    ) {
        Text(text)
    }
}

/** An outlined button in the app's shape and height, for the less important action of a pair. */
@Composable
internal fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = TeamBayesShapes.button,
        modifier = modifier.heightIn(min = TeamBayesShapes.buttonMinHeight),
    ) {
        Text(text)
    }
}

/** The "← Back" and "Continue →" row at the foot of the generate and manual steps. */
@Composable
internal fun BackContinueRow(canContinue: Boolean, onBack: () -> Unit, onContinue: () -> Unit) {
    ButtonRow {
        GhostButton(stringResource(R.string.back), onClick = onBack, modifier = Modifier.weight(1f))
        PrimaryButton(
            stringResource(R.string.continue_button),
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The frame every match-day step sits in: a [body] that takes all the spare height, and
 * a [footer] pinned to the bottom, so the main buttons stay on screen (except while the keyboard is open).
 *
 * The body scrolls on its own: each step gives it one scrolling list or column holding
 * everything except the footer, so even in landscape or at a large font size every part
 * of the step can be scrolled to. `clipToBounds` stops scrolled content drawing over
 * the footer.
 *
 * **While the keyboard is open the footer hides**, so typing never squeezes the body
 * into a sliver. [contentPadding] comes from the Scaffold's `WindowInsets.safeDrawing`,
 * which includes the keyboard, so the body then ends just above it. The buttons come
 * back when the keyboard closes.
 *
 * Body and footer are each a traversal group, so TalkBack reads the whole body before
 * moving to the footer, whatever their positions on screen.
 *
 * @param contentPadding the Scaffold's inner padding: system bars, cutout and keyboard.
 * @param body the step's scrolling content. It gets a fixed height, so a `LazyColumn`
 *   or `verticalScroll` inside it works.
 * @param footer the pinned controls, stacked with a little space between.
 */
@OptIn(ExperimentalLayoutApi::class) // isImeVisible
@Composable
internal fun StepLayout(
    contentPadding: PaddingValues,
    body: @Composable () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .semantics { isTraversalGroup = true },
        ) { body() }
        if (!WindowInsets.isImeVisible) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 12.dp)
                    .semantics { isTraversalGroup = true },
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = footer,
            )
        }
    }
}
