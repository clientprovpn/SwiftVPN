package ir.swiftvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import ir.swiftvpn.ui.GlassTokens
import ir.swiftvpn.ui.LocalIsAppDark

/**
 * The single haze source for the whole app, provided by MainActivity around the
 * root Scaffold. Null means "no blur pipeline here" (previews, tests) and every
 * glass modifier quietly degrades to plain translucency.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** Shared blur recipe: gentle radius so list scrolling stays cheap on GPUs. */
@Composable
private fun glassHazeStyle(): HazeStyle {
    val dark = LocalIsAppDark.current
    val tintColor =
        if (dark) Color.White.copy(alpha = 0.055f)
        else Color.White.copy(alpha = 0.52f)
    return HazeStyle(
        backgroundColor = if (dark) Color(0xFF14151B) else Color(0xFFF7F8FB),
        tint = HazeTint(tintColor),
        blurRadius = 14.dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(tintColor),
    )
}

/**
 * A liquid-glass pane: rounded, backdrop-blurred (Android 12+ via haze),
 * tinted, with the light-catching gradient edge.
 *
 * The clip comes FIRST so the ripple of any click applied later follows the
 * rounded shape, and so the blur never samples outside the pane.
 */
fun Modifier.glassPanel(shape: Shape): Modifier = composed {
    val haze = LocalHazeState.current
    val fill = GlassTokens.cardFill()
    val edge = GlassTokens.cardBorderBrush()
    val blurred = if (haze != null) {
        Modifier.hazeEffect(state = haze, style = glassHazeStyle())
    } else {
        Modifier
    }
    this
        .clip(shape)
        .then(blurred)
        .background(fill)
        .border(1.dp, edge, shape)
}

/** Rounded-corner convenience overload used by every card in the app. */
fun Modifier.glassPanel(corner: Dp): Modifier = glassPanel(androidx.compose.foundation.shape.RoundedCornerShape(corner))
