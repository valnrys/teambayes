package io.github.valnrys.teambayes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Shapes and sizes from the web app: cards 16 dp, buttons 14 dp and 52 dp tall, pill chips. */
object TeamBayesShapes {
    val card = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(14.dp)
    val chip = RoundedCornerShape(999.dp)

    /** Pass to `Modifier.heightIn(min = ...)` on every button. */
    val buttonMinHeight = 52.dp
}

// Material components pick these slots by default: Card uses medium, chips use small.
// Buttons default to a pill, so give them TeamBayesShapes.button explicitly.
internal val TeamBayesMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = TeamBayesShapes.chip,
    medium = TeamBayesShapes.card,
    large = TeamBayesShapes.button,
    extraLarge = RoundedCornerShape(28.dp),
)
