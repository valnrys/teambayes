package io.github.valnrys.teambayes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.valnrys.teambayes.R

// Fonts are bundled in res/font (SIL OFL, see NOTICE). Nothing is downloaded at run time.

/** Headings. Instrument Serif has a single weight, so only regular and italic exist. */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Body text and labels. One variable file; each weight sets the `wght` axis (minSdk 26 supports this). */
@OptIn(ExperimentalTextApi::class)
val DmSans = FontFamily(
    listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold,
    ).map { w ->
        Font(
            R.font.dm_sans,
            weight = w,
            variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)),
        )
    },
)

/** Numerals: scores, percentages, deltas. */
val DmMono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)

/**
 * Text styles that Material 3 has no slot for.
 *
 * - [numbers]: DM Mono, for any score, percentage or delta. Use `numbersLarge` for hero figures.
 * - [sectionLabel]: small, letter-spaced, muted. Compose has no "uppercase" style property,
 *   so use the [SectionLabel] composable, which uppercases the text and sets the muted colour.
 */
object TeamBayesTypography {
    val numbers = TextStyle(
        fontFamily = DmMono,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )
    val numbersLarge = numbers.copy(fontSize = 32.sp, lineHeight = 38.sp)

    val sectionLabel = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
        color = Color.Unspecified, // SectionLabel fills this with onSurfaceVariant
    )
}

/** An uppercase, letter-spaced, muted label above a group of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        style = TeamBayesTypography.sectionLabel,
    )
}

private fun serif(size: TextUnit, line: TextUnit) = TextStyle(
    fontFamily = InstrumentSerif,
    fontWeight = FontWeight.Normal,
    fontSize = size,
    lineHeight = line,
)

private fun sans(weight: FontWeight, size: TextUnit, line: TextUnit, spacing: TextUnit = 0.sp) = TextStyle(
    fontFamily = DmSans,
    fontWeight = weight,
    fontSize = size,
    lineHeight = line,
    letterSpacing = spacing,
)

/** Serif display, headline and title; DM Sans body and label. The web app's headings are about 22 sp. */
internal val TeamBayesMaterialTypography = Typography(
    displayLarge = serif(48.sp, 54.sp),
    displayMedium = serif(40.sp, 46.sp),
    displaySmall = serif(32.sp, 38.sp),
    headlineLarge = serif(30.sp, 36.sp),
    headlineMedium = serif(26.sp, 32.sp),
    headlineSmall = serif(22.sp, 28.sp),
    titleLarge = serif(22.sp, 28.sp),
    titleMedium = serif(18.sp, 24.sp),
    titleSmall = serif(16.sp, 22.sp),
    bodyLarge = sans(FontWeight.Normal, 16.sp, 24.sp),
    bodyMedium = sans(FontWeight.Normal, 14.sp, 20.sp),
    bodySmall = sans(FontWeight.Normal, 12.sp, 16.sp),
    labelLarge = sans(FontWeight.SemiBold, 14.sp, 20.sp),
    labelMedium = sans(FontWeight.Medium, 12.sp, 16.sp, 0.02.em),
    labelSmall = sans(FontWeight.Medium, 11.sp, 16.sp, 0.04.em),
)
