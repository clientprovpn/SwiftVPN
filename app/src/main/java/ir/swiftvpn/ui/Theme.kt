package ir.swiftvpn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ir.swiftvpn.engine.ThemeMode

// A cool indigo/violet accent on near-black. Three distinct surface levels give
// the layout real depth instead of flat cards on a flat background.
private val Accent = Color(0xFF7C82F0)
private val AccentDeep = Color(0xFF4A51C9)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B0D1F),
    primaryContainer = Color(0xFF2B2F63),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFF8E93B8),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFECEDF2),
    surface = Color(0xFF14151B),          // cards
    onSurface = Color(0xFFECEDF2),
    surfaceVariant = Color(0xFF1D1F27),   // nested panels
    onSurfaceVariant = Color(0xFF9BA1B2),
    surfaceContainerHigh = Color(0xFF23252F),
    outline = Color(0xFF32353F),
    outlineVariant = Color(0xFF23252D),
    error = Color(0xFFE8695E),
)

private val LightColors = lightColorScheme(
    primary = AccentDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E5FF),
    onPrimaryContainer = Color(0xFF141845),
    secondary = Color(0xFF5B6079),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF12131A),
    surface = Color.White,
    onSurface = Color(0xFF12131A),
    surfaceVariant = Color(0xFFEFF1F6),
    onSurfaceVariant = Color(0xFF5A5F70),
    surfaceContainerHigh = Color(0xFFE8EAF1),
    outline = Color(0xFFCFD2DC),
    outlineVariant = Color(0xFFE4E6EE),
    error = Color(0xFFC0392B),
)

/** True when the app's own theme (not necessarily the system's) is dark. */
val LocalIsAppDark = compositionLocalOf { true }

/**
 * Liquid-glass tokens. The app draws a soft gradient backdrop; cards and bars
 * float over it as translucent, blurred panes with a light-catching top edge.
 * Everything keys off [LocalIsAppDark] so the glass works in BOTH themes —
 * frosted white over the light gradient, smoked glass over the dark one.
 */
object GlassTokens {

    /** The gradient every screen sits on. Visible through every pane. */
    @Composable
    fun backdropBrush(): Brush = if (LocalIsAppDark.current) {
        Brush.verticalGradient(
            listOf(Color(0xFF141633), Color(0xFF0B0B0F), Color(0xFF171130)),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFFDFE4F5), Color(0xFFF7F8FB), Color(0xFFEDE7F9)),
        )
    }

    /** Fill of a glass card (haze adds real blur beneath it when available). */
    @Composable
    fun cardFill(): Color =
        if (LocalIsAppDark.current) Color.White.copy(alpha = 0.055f)
        else Color.White.copy(alpha = 0.52f)

    /** The liquid edge: brighter at the top, fading down the sides. */
    @Composable
    fun cardBorderBrush(): Brush {
        val hi = if (LocalIsAppDark.current) 0.32f else 0.85f
        val lo = if (LocalIsAppDark.current) 0.08f else 0.35f
        return Brush.verticalGradient(
            listOf(Color.White.copy(alpha = hi), Color.White.copy(alpha = lo)),
        )
    }

    /** Menus render in their own popup window where backdrop blur cannot
     *  reach, so the liquid look comes from translucency plus the glass edge
     *  rather than haze. Kept sheerer than dialogs, still readable. */
    @Composable
    fun menuColor(): Color =
        if (LocalIsAppDark.current) Color(0xD9161722) else Color(0xE8F7F8FC)

    /** Dialogs render in their own window where backdrop blur cannot reach;
     *  near-opaque translucency keeps them readable yet in-family. */
    @Composable
    fun dialogColor(): Color =
        if (LocalIsAppDark.current) Color(0xF214151B) else Color(0xF5F7F8FC)
}

/** Status accents shared by the UI, graphs and tile. */
object StatusColors {
    val connected = Color(0xFF34D399)
    val connecting = Color(0xFFFBBF24)
    val error = Color(0xFFF87171)
    val idle = Color(0xFF6B7280)
    val download = Color(0xFF38BDF8)
    val upload = Color(0xFFA78BFA)
}

/**
 * Slightly tightened type scale. The stock scale is a little loose for a
 * single-purpose utility app, and the numeric readouts want more weight.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.5).sp,
        ),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(letterSpacing = 0.4.sp),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/**
 * Resolves a [ThemeMode] into an actual light/dark decision.
 *
 * Exposed separately so MainActivity can keep the system status-bar icon
 * contrast in step with the surface the app is actually drawing.
 */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = mode == ThemeMode.DARK

@Composable
fun SwiftVpnTheme(
    mode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(mode)

    CompositionLocalProvider(LocalIsAppDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content,
        )
    }
}
