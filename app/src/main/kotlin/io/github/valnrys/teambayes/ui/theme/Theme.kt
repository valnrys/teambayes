package io.github.valnrys.teambayes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// A dark-first palette: near-black blue background, slightly lighter cards, sky-blue accent.
// The light scheme is derived from the dark one.
//
// Muted text uses #7B97B4 rather than the dimmer #5B7896, which is only 4.2:1 on the background
// and 3.6:1 on raised cards (below WCAG AA for text). The dimmer value is kept as
// TeamBayesColors.mutedFaint for non-text uses such as dividers and disabled icons.
//
// Card mapping (dark): surfaceContainerLow = card, surfaceContainerHigh = raised card.
// `outline` is for control borders and needs 3:1 against every surface; `outlineVariant` is the
// subtle one for dividers.

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6ECFA),
    onPrimaryContainer = Color(0xFF052C45),
    secondary = Color(0xFF4A6078),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E8F1),
    onSecondaryContainer = Color(0xFF14202E),
    tertiary = Color(0xFF8A5200),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFCE9C2),
    onTertiaryContainer = Color(0xFF2E1B00),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE0E0),
    onErrorContainer = Color(0xFF450A0A),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFE3EAF2),
    onSurfaceVariant = Color(0xFF4A6078),
    outline = Color(0xFF64788E),
    outlineVariant = Color(0xFFCBD5E1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF5),
    surfaceContainerHighest = Color(0xFFE1E8F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF0B0E13), // near-black text on the sky accent
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFD6F0FD),
    secondary = Color(0xFF7B97B4),
    onSecondary = Color(0xFF0B0E13),
    secondaryContainer = Color(0xFF1E2D40),
    onSecondaryContainer = Color(0xFFD5E2F0),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF0B0E13),
    tertiaryContainer = Color(0xFF4A3406),
    onTertiaryContainer = Color(0xFFFDE9B0),
    error = Color(0xFFF87171),
    onError = Color(0xFF0B0E13),
    errorContainer = Color(0xFF4C1414),
    onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF0B0E13),
    onBackground = Color(0xFFF0F6FF),
    surface = Color(0xFF0B0E13),
    onSurface = Color(0xFFF0F6FF),
    surfaceVariant = Color(0xFF182030),
    onSurfaceVariant = Color(0xFF7B97B4),
    outline = Color(0xFF5B7896),
    outlineVariant = Color(0xFF1E2D40),
    surfaceContainerLowest = Color(0xFF080A0E),
    surfaceContainerLow = Color(0xFF111720),
    surfaceContainer = Color(0xFF141C28),
    surfaceContainerHigh = Color(0xFF182030),
    surfaceContainerHighest = Color(0xFF1C2A3C),
)

/**
 * Colours Material 3 has no slot for, read with `MaterialTheme.teamBayesColors`.
 *
 * - [amber]: the EDITED badge and the uneven-teams warning.
 * - [green] and [red]: results (win, loss). A draw uses [amber], as in form dots.
 * - [teamNeutral]: a team with no result yet; calm grey-blue, not a win/loss colour.
 * - [mutedFaint]: the dim muted colour. Too faint for text, fine for decoration.
 *
 * The text colours (all but [mutedFaint]) reach at least 4.5:1 on `background` and on
 * `surfaceContainerLow` in their own scheme. [mutedFaint] is for non-text details only.
 */
@Immutable
data class TeamBayesColors(
    val amber: Color,
    val green: Color,
    val red: Color,
    val teamNeutral: Color,
    val mutedFaint: Color,
)

private val DarkTeamBayesColors = TeamBayesColors(
    amber = Color(0xFFFBBF24),
    green = Color(0xFF4ADE80),
    red = Color(0xFFF87171), // #EF4444 is only 4.3:1 on raised cards; this lighter red clears 5.9:1
    teamNeutral = Color(0xFF94A9BF),
    mutedFaint = Color(0xFF5B7896),
)

private val LightTeamBayesColors = TeamBayesColors(
    amber = Color(0xFF8A5200),
    green = Color(0xFF146C34),
    red = Color(0xFFB91C1C),
    teamNeutral = Color(0xFF52667D),
    mutedFaint = Color(0xFF8DA0B5),
)

private val LocalTeamBayesColors = staticCompositionLocalOf { DarkTeamBayesColors }

/** The extra semantic colours for the current theme. */
val MaterialTheme.teamBayesColors: TeamBayesColors
    @Composable @ReadOnlyComposable get() = LocalTeamBayesColors.current

/**
 * The Material 3 theme for the whole app.
 *
 * It follows the system light/dark setting. Dynamic colour (Android 12+ wallpaper
 * colours) is deliberately not used, so the app looks the same on every phone.
 */
@Composable
fun TeamBayesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTeamBayesColors provides if (darkTheme) DarkTeamBayesColors else LightTeamBayesColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = TeamBayesMaterialTypography,
            shapes = TeamBayesMaterialShapes,
            content = content,
        )
    }
}
