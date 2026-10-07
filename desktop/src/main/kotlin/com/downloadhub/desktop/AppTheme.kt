package com.downloadhub.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.downloadhub.core.blend
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.downloadhub.core.ThemeColors
import com.downloadhub.core.ThemeMode
import com.downloadhub.core.ThemePalette
import com.downloadhub.core.resolveThemeColors

/**
 * The colours this window is painted with, as the theme resolves them.
 *
 * A Material [ColorScheme] does not reach most of this window. Rows, the sidebar, the
 * column headings, the status strip and the menus are flat fills, chosen once and written
 * as literals - which is why the desktop app looked the same however the Material theme
 * was set, and why a theme picker on its own would have changed nothing the user could
 * see. This is the object those fills read from.
 */
data class DesktopPalette(val colors: ThemeColors) {
    private fun c(value: Long): Color = Color(value)

    val accent: Color get() = c(colors.accent)
    val onAccent: Color get() = c(colors.onAccent)
    val accentContainer: Color get() = c(colors.accentContainer)
    val onAccentContainer: Color get() = c(colors.onAccentContainer)

    val onSurface: Color get() = c(colors.onSurface)
    val muted: Color get() = c(colors.muted)
    val faint: Color get() = c(colors.faint)

    val surface: Color get() = c(colors.surface)
    val raised: Color get() = c(colors.raised)
    val band: Color get() = c(colors.band)
    val background: Color get() = c(colors.background)

    val outline: Color get() = c(colors.outline)
    val outlineVariant: Color get() = c(colors.outlineVariant)
    val selection: Color get() = c(colors.selection)

    val error: Color get() = c(colors.error)
    val onError: Color get() = c(colors.onError)

    val menuPanel: Color get() = c(colors.menuPanel)
    val menuEdge: Color get() = c(colors.menuEdge)
}

/**
 * The current theme.
 *
 * A composition local rather than an object with mutable state, so a theme change
 * recomposes the fills that read it. A global that was written and never observed would
 * repaint nothing at all - the menu would change colour and the rows would not, which is
 * worse than not offering the choice.
 */
val LocalDesktopPalette = compositionLocalOf {
    DesktopPalette(resolveThemeColors(ThemePalette.SIGNAL, ThemeMode.DARK))
}

/**
 * How every dialog in this app is presented.
 *
 * Compose on the desktop draws no backdrop behind a dialog of its own accord. The scrim
 * exists so a dialog reads as being in front of the window rather than painted onto it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
val APP_DIALOG_PROPERTIES = androidx.compose.ui.window.DialogProperties(
    scrimColor = Color(0xCC000000)
)

/** The same, for a dialog that sets its own width - Settings, with its section list. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
val APP_WIDE_DIALOG_PROPERTIES = androidx.compose.ui.window.DialogProperties(
    usePlatformDefaultWidth = false,
    scrimColor = Color(0xCC000000)
)

object AppTheme {

    /**
     * The palette the flat-drawn parts read.
     *
     * A property rather than the composition local, because most of what it paints is not
     * a composable: a `Row`'s background argument, a `MenuItem`'s text colour, a value
     * handed to a helper. Those cannot read a composition local, and giving them a
     * `@Composable` accessor would mean threading the palette through every helper for no
     * gain.
     *
     * It is written during composition by [ProvideDesktopTheme] and read by everything
     * else. A global read is the right trade for a value that changes when the user picks
     * a theme and not otherwise.
     */
    @Volatile
    private var current = DesktopPalette(resolveThemeColors(ThemePalette.SIGNAL, ThemeMode.DARK))

    /** Shorthand for the flat fills: `AppTheme.Palette.surface`. */
    val Palette: DesktopPalette get() = current

    /**
     * "Done" green, the same in every palette: the accent is orange in Sunset and red in
     * Rose, and a finished download painted in either looked like a warning.
     */
    val success: Color
        get() = if (current.colors.isDark) Color(0xFF4ADE80) else Color(0xFF1E8E4E)

    internal fun install(palette: DesktopPalette) {
        current = palette
    }

    /**
     * The Material scheme for a resolved set of colours.
     *
     * Built from the same [ThemeColors] the flat fills use, so the Material components -
     * text fields, buttons, switches - and the hand-drawn parts cannot end up looking like
     * two apps in one window. That was the original failure: every dialog got its own
     * container colour, and being forgotten was a white box in a dark window.
     */
    fun schemeFor(colors: ThemeColors): ColorScheme {
        val p = DesktopPalette(colors)
        val dark = colors.isDark
        return if (dark) {
            darkColorScheme(
                primary = p.accent,
                onPrimary = p.onAccent,
                primaryContainer = p.accentContainer,
                onPrimaryContainer = p.onAccentContainer,
                secondary = p.accent,
                onSecondary = p.onAccent,
                secondaryContainer = p.accentContainer,
                onSecondaryContainer = p.onAccentContainer,
                background = p.background,
                onBackground = p.onSurface,
                surface = p.surface,
                onSurface = p.onSurface,
                surfaceVariant = p.raised,
                onSurfaceVariant = p.muted,
                surfaceContainer = p.raised,
                surfaceContainerHigh = p.raised,
                surfaceContainerHighest = p.raised,
                outline = p.outline,
                outlineVariant = p.outlineVariant,
                error = p.error,
                onError = p.onError
            )
        } else {
            lightColorScheme(
                primary = p.accent,
                onPrimary = p.onAccent,
                primaryContainer = p.accentContainer,
                onPrimaryContainer = p.onAccentContainer,
                secondary = p.accent,
                onSecondary = p.onAccent,
                secondaryContainer = p.accentContainer,
                onSecondaryContainer = p.onAccentContainer,
                background = p.background,
                onBackground = p.onSurface,
                surface = p.surface,
                onSurface = p.onSurface,
                surfaceVariant = p.raised,
                onSurfaceVariant = p.muted,
                surfaceContainer = p.raised,
                surfaceContainerHigh = p.raised,
                surfaceContainerHighest = p.raised,
                outline = p.outline,
                outlineVariant = p.outlineVariant,
                error = p.error,
                onError = p.onError
            )
        }
    }
}

/**
 * Provides the theme to everything below, in both the forms the window uses.
 *
 * The Material scheme for the components that take one, and the palette for the flat fills
 * that do not. Both come from the same resolved colours, so a field and a row beside it
 * are the same theme.
 */
@Composable
fun ProvideDesktopTheme(
    palette: ThemePalette,
    mode: ThemeMode,
    /**
     * False for a theme drawn inside another - a swatch in the theme picker. The global
     * palette is the app's; a swatch writing it left the whole window painted in the last
     * swatch's colours (AMOLED green) after Settings had been opened.
     */
    installGlobally: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = remember(palette, mode) { resolveThemeColors(palette, mode) }
    val desktopPalette = remember(colors) { DesktopPalette(colors) }
    // Written before the children are composed, so the first frame they draw is already
    // the right colour. Writing it afterwards left one frame of the old theme on screen.
    if (installGlobally) AppTheme.install(desktopPalette)
    CompositionLocalProvider(LocalDesktopPalette provides desktopPalette) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = AppTheme.schemeFor(colors),
            // Square-ish, like cut paper: small radii instead of Material's bubbles.
            shapes = androidx.compose.material3.Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                small = androidx.compose.foundation.shape.RoundedCornerShape(5.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ),
            typography = AppTypography,
            content = content
        )
    }
}

/**
 * The app's voice: Space Grotesk for words, a grotesk with quirks (the open "a", the
 * ink-trap "t") so it does not read as one more Segoe app. Material hands
 * `bodyLarge` to every `Text` that does not ask for a style, so setting the family on
 * the typography reaches the whole window, sizes and all left as each call set them.
 */
@Suppress("DEPRECATION")
val Grotesk = FontFamily(
    Font("fonts/space_grotesk_regular.ttf", FontWeight.Normal),
    Font("fonts/space_grotesk_medium.ttf", FontWeight.Medium),
    Font("fonts/space_grotesk_semibold.ttf", FontWeight.SemiBold),
    Font("fonts/space_grotesk_bold.ttf", FontWeight.Bold)
)

/**
 * And JetBrains Mono for numbers: sizes, speeds, percentages, times. Every digit the
 * same width, so a ticking speed does not jitter, and it reads like a meter.
 */
@Suppress("DEPRECATION")
val Mono = FontFamily(
    Font("fonts/jetbrains_mono_regular.ttf", FontWeight.Normal),
    Font("fonts/jetbrains_mono_medium.ttf", FontWeight.Medium),
    Font("fonts/jetbrains_mono_bold.ttf", FontWeight.Bold)
)

private val AppTypography = androidx.compose.material3.Typography().run {
    fun TextStyle.grotesk(weight: FontWeight? = null) = copy(fontFamily = Grotesk, fontWeight = weight ?: fontWeight)
    copy(
        displayLarge = displayLarge.grotesk(FontWeight.Bold),
        displayMedium = displayMedium.grotesk(FontWeight.Bold),
        displaySmall = displaySmall.grotesk(FontWeight.Bold),
        headlineLarge = headlineLarge.grotesk(FontWeight.Bold),
        headlineMedium = headlineMedium.grotesk(FontWeight.Bold),
        headlineSmall = headlineSmall.grotesk(FontWeight.Bold),
        titleLarge = titleLarge.grotesk(FontWeight.Bold),
        titleMedium = titleMedium.grotesk(FontWeight.SemiBold),
        titleSmall = titleSmall.grotesk(FontWeight.SemiBold),
        bodyLarge = bodyLarge.grotesk(),
        bodyMedium = bodyMedium.grotesk(),
        bodySmall = bodySmall.grotesk(),
        labelLarge = labelLarge.grotesk(FontWeight.SemiBold),
        labelMedium = labelMedium.grotesk(FontWeight.Medium),
        labelSmall = labelSmall.grotesk(FontWeight.Medium)
    )
}

/**
 * The app's signature progress bar: a row of blocks, like an LED level meter, rather than
 * a smooth line. It is also what the downloader does - a file fetched in segments - so
 * the bar shows the work as pieces. While a download is moving, the block at its leading
 * edge pulses.
 */
@Composable
internal fun SignalBar(progress: Float, color: Color, live: Boolean, modifier: Modifier = Modifier, segments: Int = 24) {
    val track = AppTheme.Palette.onSurface.copy(alpha = 0.09f)
    val pulse = if (live) {
        rememberInfiniteTransition(label = "signal").animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "head"
        ).value
    } else 1f
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val block = (size.width - gap * (segments - 1)) / segments
        val lit = progress.coerceIn(0f, 1f) * segments
        val head = lit.toInt().coerceAtMost(segments - 1)
        for (i in 0 until segments) {
            val x = i * (block + gap)
            drawRect(track, Offset(x, 0f), Size(block, size.height))
            val fill = (lit - i).coerceIn(0f, 1f)
            when {
                live && i == head -> drawRect(color.copy(alpha = pulse), Offset(x, 0f), Size(block, size.height))
                fill > 0f -> drawRect(color, Offset(x, 0f), Size(block * fill, size.height))
            }
        }
    }
}

/**
 * Recent speed as LED columns, newest on the right, each scaled to the fastest second in
 * view. An unlit column still shows as dim cells, so the meter has a shape at rest.
 */
@Composable
internal fun SpeedTrace(samples: List<Long>, modifier: Modifier = Modifier) {
    val lit = AppTheme.Palette.accent
    val dim = AppTheme.Palette.onSurface.copy(alpha = 0.08f)
    Canvas(modifier) {
        val peak = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val gap = 1.5.dp.toPx()
        val column = (size.width - gap * (samples.size - 1)) / samples.size
        val cells = 4
        val cellGap = 1.dp.toPx()
        val cell = (size.height - cellGap * (cells - 1)) / cells
        samples.forEachIndexed { i, sample ->
            val on = if (sample <= 0L) 0 else (sample.toFloat() / peak * cells).toInt().coerceIn(1, cells)
            for (c in 0 until cells) {
                val y = size.height - (c + 1) * cell - c * cellGap
                drawRect(
                    if (c < on) lit else dim,
                    Offset(i * (column + gap), y),
                    Size(column, cell)
                )
            }
        }
    }
}

/**
 * A physical key: a raised face on a darker edge, which travels down onto the edge while
 * it is held. The app's buttons are keys on a panel, not flat labels that tint on hover -
 * the single biggest thing that makes the window read as a piece of equipment.
 */
internal fun Modifier.keycap(
    enabled: Boolean = true,
    accent: Boolean = false,
    shape: Shape = RoundedCornerShape(6.dp),
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth = 3.dp
    val travel by animateDpAsState(if (pressed && enabled) depth else 0.dp, tween(50), label = "key")
    val p = AppTheme.Palette
    val face = when {
        accent -> p.accent
        else -> p.raised
    }
    val edge = if (accent) Color(blend(p.colors.accent, 0xFF000000, 0.35f)) else Color(blend(p.colors.raised, 0xFF000000, if (p.colors.isDark) 0.55f else 0.22f))
    this
        .padding(bottom = depth)
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(0f, depth.toPx()) { drawOutline(outline, edge) }
        }
        .graphicsLayer { translationY = travel.toPx() }
        .clip(shape)
        .background(face)
        .border(1.dp, p.onSurface.copy(alpha = if (p.colors.isDark) 0.10f else 0.14f), shape)
        .alpha(if (enabled) 1f else 0.6f)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * Four screw heads in the corners, as on a panel fixed to a chassis. Drawn over the
 * content at a small inset; purely decoration, so it never takes layout space.
 */
internal fun Modifier.screws(inset: Dp = 7.dp, radius: Dp = 3.dp): Modifier = drawWithContent {
    drawContent()
    val ink = AppTheme.Palette.onSurface
    val r = radius.toPx()
    val i = inset.toPx()
    listOf(Offset(i, i), Offset(size.width - i, i), Offset(i, size.height - i), Offset(size.width - i, size.height - i)).forEach { c ->
        drawCircle(ink.copy(alpha = 0.16f), r, c)
        drawCircle(ink.copy(alpha = 0.30f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        drawLine(ink.copy(alpha = 0.45f), Offset(c.x - r * 0.6f, c.y + r * 0.6f), Offset(c.x + r * 0.6f, c.y - r * 0.6f), 1.2f)
    }
}

/**
 * A small LCD: recessed dark glass that stays dark in every theme, as a real display
 * does, with the unlit segments ([ghost], usually "8"s) faintly visible behind the lit
 * [value] and a few scanlines across it. The one place the brightest accent glows.
 */
@Composable
internal fun Lcd(value: String, ghost: String, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp) {
    val glow = Color(AppTheme.Palette.colors.palette.accentDark)
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF0B0C09))
            .border(1.dp, Color(0xFF000000), shape)
            .drawWithContent {
                drawContent()
                // Flat glass: no gradient or scanlines by default, as on Android.
            }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text("8".repeat(ghost.length), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fontSize, color = glow.copy(alpha = 0.10f), maxLines = 1, softWrap = false)
        Text(value.padStart(ghost.length), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fontSize, color = glow, maxLines = 1, softWrap = false)
    }
}

/** Doto, a dot-matrix face (OFL), for the wordmark and display titles only - never small text. */
@Suppress("DEPRECATION")
val Dot = FontFamily(Font("fonts/doto_black.ttf", FontWeight.Black))

/**
 * A light LCD plate, the kind on a calculator or a sampler: grey-green glass with dark
 * dot-matrix text. The window's wordmark sits on one.
 */
@Composable
internal fun LcdPlate(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 16.sp) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFFC9CEC0))
            .border(1.dp, Color(0xFF8E9486), shape)
            .padding(horizontal = 8.dp, vertical = 1.dp)
    ) {
        Text(text, fontFamily = Dot, fontWeight = FontWeight.Black, fontSize = fontSize, color = Color(0xFF1F2419), maxLines = 1, softWrap = false)
    }
}

/** One status LED: lit green when [on], an unlit dot otherwise, with a printed label. */
@Composable
internal fun StatusLed(label: String, on: Boolean) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (on) Color(0xFF22A447) else AppTheme.Palette.onSurface.copy(alpha = 0.22f))
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(label, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = AppTheme.Palette.onSurface, maxLines = 1)
    }
}
