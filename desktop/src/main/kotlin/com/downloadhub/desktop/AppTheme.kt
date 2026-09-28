package com.downloadhub.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The one Material 3 colour scheme this app uses.
 *
 * The window itself is painted with flat colours, but Material 3 components take
 * theirs from the theme. Without a scheme, every `AlertDialog` - Add a download,
 * Settings, close, update - renders on Material's light surface: a white box in the
 * middle of a dark window, and near-white body text on that white surface.
 *
 * Declared once here rather than per dialog, because the alternative is what it was:
 * each dialog setting its own container colour, and being forgotten.
 */
/**
 * How every dialog in this app is presented.
 *
 * Compose on the desktop draws no backdrop behind a dialog of its own accord, so each
 * one sat as a flat rounded rectangle on an undimmed window. On a dark theme that
 * reads as a panel painted onto the window rather than something in front of it. A
 * scrim is what makes the layering visible.
 *
 * Declared once here because it belongs to every dialog and forgetting it in one of
 * them is exactly the sort of difference nobody notices until two are open side by
 * side.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
val APP_DIALOG_PROPERTIES = androidx.compose.ui.window.DialogProperties(
    scrimColor = Color(0xCC000000)
)

object AppTheme {

    private val surface = Color(0xFF161D20)
    private val raised = Color(0xFF1E2629)
    private val accent = Color(0xFF34D399)
    private val onSurface = Color(0xFFD6DEDF)
    private val muted = Color(0xFF8A9799)

    val darkScheme: ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF06301F),
        primaryContainer = Color(0xFF1D4A3A),
        onPrimaryContainer = accent,
        secondary = Color(0xFF7DD3FC),
        onSecondary = Color(0xFF04222E),
        background = Color(0xFF101618),
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = raised,
        onSurfaceVariant = muted,
        surfaceContainer = raised,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = Color(0xFF262F32),
        outline = Color(0xFF2C3639),
        outlineVariant = Color(0xFF232B2E),
        error = Color(0xFFF87171),
        onError = Color(0xFF3A0A0A)
    )

    /** Colours the flat-drawn parts of the UI share, so they stay in step. */
    object Palette {
        val accent: Color get() = AppTheme.darkScheme.primary
        val onSurface: Color get() = AppTheme.darkScheme.onSurface
        val muted: Color get() = AppTheme.darkScheme.onSurfaceVariant
        val surface: Color get() = AppTheme.darkScheme.surface
    }
}
