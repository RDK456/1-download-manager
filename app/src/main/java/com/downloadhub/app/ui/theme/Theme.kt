package com.downloadhub.app.ui.theme

import androidx.compose.foundation.layout.size
import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.downloadhub.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode

/**
 * The app's voice: Space Grotesk for words, a grotesk with quirks (the open "a", the
 * ink-trap "t") so it does not read as one more Roboto app.
 */
val Grotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

/**
 * And JetBrains Mono for numbers: sizes, speeds, percentages, times. Every digit the
 * same width, so a ticking speed does not jitter, and it reads like a meter.
 */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

private val AppTypography = Typography().run {
    fun TextStyle.grotesk(weight: FontWeight? = null, tracking: Double? = null) =
        copy(fontFamily = Grotesk, fontWeight = weight ?: fontWeight, letterSpacing = tracking?.sp ?: letterSpacing)
    copy(
        displayLarge = displayLarge.grotesk(FontWeight.Bold, -1.5),
        displayMedium = displayMedium.grotesk(FontWeight.Bold, -1.0),
        displaySmall = displaySmall.grotesk(FontWeight.Bold, -0.5),
        headlineLarge = headlineLarge.grotesk(FontWeight.Bold, -0.5),
        headlineMedium = headlineMedium.grotesk(FontWeight.Bold, -0.4),
        headlineSmall = headlineSmall.grotesk(FontWeight.Bold, -0.3),
        titleLarge = titleLarge.grotesk(FontWeight.Bold, -0.3),
        titleMedium = titleMedium.grotesk(FontWeight.SemiBold),
        titleSmall = titleSmall.grotesk(FontWeight.SemiBold),
        bodyLarge = bodyLarge.grotesk(),
        bodyMedium = bodyMedium.grotesk(),
        bodySmall = bodySmall.grotesk(),
        labelLarge = labelLarge.grotesk(FontWeight.SemiBold, 0.2),
        labelMedium = labelMedium.grotesk(FontWeight.Medium, 0.4),
        labelSmall = labelSmall.grotesk(FontWeight.Medium, 0.6)
    )
}

/** Square-ish, like cut paper: small radii instead of Material's bubbles and pills. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/**
 * Applies the chosen two-tone palette. [appTheme] supplies the colours, while
 * [themeMode] still decides light/dark (and AMOLED forces true black).
 */
@Composable
fun DownloadHubTheme(
    themeMode: ThemeMode,
    appTheme: AppTheme = AppTheme.SIGNAL,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    // The AMOLED mode forces true-black backgrounds but keeps the accent of the
    // colour theme, so "AMOLED + Sunset" is black with orange accents. Swapping
    // the whole palette here would silently discard the chosen colour.
    val pureBlack = themeMode == ThemeMode.AMOLED
    MaterialTheme(
        colorScheme = appTheme.colorScheme(dark, pureBlack = pureBlack),
        typography = AppTypography,
        shapes = AppShapes,
        content = {
            MatchSystemBarsToTheme()
            CompositionLocalProvider(LocalLcdGlow provides appTheme.accentDark, content = content)
        }
    )
}

/**
 * Keeps the status and navigation bar icons legible. Without this the bars keep
 * whatever the previous theme left behind, which on AMOLED shows dark icons on a
 * black background and looks like the theme did not apply.
 */
@Composable
private fun MatchSystemBarsToTheme() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/**
 * The app's surface treatment, borrowed from print and hardware rather than Material: a
 * 1 dp ink edge and a hard, unblurred shadow offset down and to the right, as if the
 * panel were cut from card and laid on the page. Soft elevation shadows are what make
 * every app look alike; this one reads as made.
 */
@Composable
fun Modifier.inkPanel(shape: Shape = MaterialTheme.shapes.medium, offset: Dp = 3.dp): Modifier {
    val ink = MaterialTheme.colorScheme.onSurface
    val fill = MaterialTheme.colorScheme.surface
    return this
        .padding(end = offset, bottom = offset)
        .drawBehind {
            val o = offset.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(o, o) { drawOutline(outline, ink.copy(alpha = 0.85f)) }
        }
        .clip(shape)
        .background(fill, shape)
        .border(1.dp, ink.copy(alpha = 0.85f), shape)
}

/**
 * A physical key: a raised face on a darker edge, which travels down onto the edge while
 * it is held, like the keys on a synth or a calculator.
 */
fun Modifier.keycap(
    accent: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    depth: Dp = 5.dp,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val travel by animateDpAsState(if (pressed) depth else 0.dp, tween(50), label = "key")
    val scheme = MaterialTheme.colorScheme
    val face = if (accent) scheme.primary else scheme.surfaceVariant
    val edge = androidx.compose.ui.graphics.lerp(face, Color.Black, 0.4f)
    this
        .padding(bottom = depth)
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(0f, depth.toPx()) { drawOutline(outline, edge) }
        }
        .graphicsLayer { translationY = travel.toPx() }
        .clip(shape)
        .background(face)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/** Four screw heads in the corners, as on a panel fixed to a chassis. Decoration only. */
@Composable
fun Modifier.screws(inset: Dp = 8.dp, radius: Dp = 3.5.dp): Modifier {
    val ink = MaterialTheme.colorScheme.onSurface
    return drawWithContent {
        drawContent()
        val r = radius.toPx()
        val i = inset.toPx()
        listOf(Offset(i, i), Offset(size.width - i, i), Offset(i, size.height - i), Offset(size.width - i, size.height - i)).forEach { c ->
            drawCircle(ink.copy(alpha = 0.16f), r, c)
            drawCircle(ink.copy(alpha = 0.30f), r, c, style = Stroke(1f))
            drawLine(ink.copy(alpha = 0.45f), Offset(c.x - r * 0.6f, c.y + r * 0.6f), Offset(c.x + r * 0.6f, c.y - r * 0.6f), 1.5f)
        }
    }
}

/**
 * A small LCD: recessed dark glass that stays dark in every theme, as a real display
 * does, with the unlit segments ([ghost], usually "8"s) faintly visible behind the lit
 * [value] and scanlines across it. The one place the brightest accent glows.
 */
@Composable
fun Lcd(value: String, ghost: String, modifier: Modifier = Modifier, glow: Color = LocalLcdGlow.current, fontSize: TextUnit = 26.sp) {
    val shape = RoundedCornerShape(6.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Color(0xFF0B0C09))
            .border(1.dp, Color.Black, shape)
            .drawWithContent {
                drawContent()
                if (UiEffects.enabled) {
                    drawRect(Brush.verticalGradient(listOf(Color(0x77000000), Color.Transparent), endY = size.height * 0.45f))
                    var y = 0f
                    while (y < size.height) {
                        drawLine(Color(0x0AFFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
                        y += 3.dp.toPx()
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        // Every digit cell is 0.6 em wide in the mono face, so the biggest size at which the
        // whole display fits is known up front. On a narrow phone, or with the system text set
        // large, the figure steps down instead of being cut off at the edge.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fit = (maxWidth.value - 20f) / (ghost.length * 0.6f) / density.fontScale
        val fontSize = minOf(fontSize.value, fit.coerceAtLeast(8f)).sp
        Text("8".repeat(ghost.length), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = fontSize, color = glow.copy(alpha = 0.10f), maxLines = 1, softWrap = false)
        Text(
            value.padStart(ghost.length),
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = glow,
            maxLines = 1,
            softWrap = false,
            style = if (UiEffects.enabled) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(glow.copy(alpha = 0.6f), blurRadius = 12f)) else androidx.compose.ui.text.TextStyle.Default
        )
    }
}

/** The colour an LCD glows in: the theme's brightest accent, whatever the mode. */
val LocalLcdGlow = staticCompositionLocalOf { Color(0xFFC6F135) }

/**
 * Doto, a dot-matrix face (OFL), for screen titles only: the departure-board voice of a
 * piece of equipment. Never for body text or anything small - dots read only when large.
 */
val Dot = FontFamily(Font(R.font.doto_black, FontWeight.Black))

/**
 * Depth effects - gradients, glow, scanlines - as an opt-in. The default skin is flat:
 * solid fills and hard edges read cleaner and age better, and nobody should have to turn
 * decoration off to get a plain screen. Stored on the device, off until asked for.
 */
object UiEffects {
    var enabled by mutableStateOf(false)
        private set

    fun load(context: android.content.Context) {
        enabled = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getBoolean("depth_effects", false)
    }

    fun set(context: android.content.Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("depth_effects", on).apply()
    }
}

/**
 * How the app's frame is built. RAIL is the flat default: a hairline bottom rail and a
 * plain title. KEYS is the hardware layout: every tab its own key with an LED, the title on
 * a small LCD plate, and a row of status LEDs. Independent of the colour theme, so any
 * palette can wear either; picking Chassis switches to KEYS as a starting point.
 */
object UiStyle {
    var keys by mutableStateOf(false)
        private set

    fun load(context: android.content.Context) {
        keys = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getBoolean("style_keys", false)
    }

    fun set(context: android.content.Context, on: Boolean) {
        keys = on
        context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("style_keys", on).apply()
    }
}

/**
 * A light LCD plate, the kind on a calculator or a sampler: grey-green glass with dark
 * dot-matrix text. Used for the title in the KEYS style.
 */
@Composable
fun LcdPlate(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFFC9CEC0))
            .border(1.5.dp, Color(0xFF8E9486), shape)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // A longer title steps down a size, and one that still does not fit ends in an ellipsis
        // rather than running off the bar.
        Text(text, fontFamily = Dot, fontWeight = FontWeight.Black, fontSize = if (text.length > 11) 17.sp else 24.sp, color = Color(0xFF1F2419), maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** One status LED: lit green when [on], an unlit grey dot otherwise, with a printed label. */
@Composable
fun StatusLed(label: String, on: Boolean) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (on) Color(0xFF22A447) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f))
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(label, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
