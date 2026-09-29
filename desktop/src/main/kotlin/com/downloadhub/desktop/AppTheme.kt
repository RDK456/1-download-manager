package com.downloadhub.desktop

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
    DesktopPalette(resolveThemeColors(ThemePalette.MINT, ThemeMode.DARK))
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
    private var current = DesktopPalette(resolveThemeColors(ThemePalette.MINT, ThemeMode.DARK))

    /** Shorthand for the flat fills: `AppTheme.Palette.surface`. */
    val Palette: DesktopPalette get() = current

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
fun ProvideDesktopTheme(palette: ThemePalette, mode: ThemeMode, content: @Composable () -> Unit) {
    val colors = remember(palette, mode) { resolveThemeColors(palette, mode) }
    val desktopPalette = remember(colors) { DesktopPalette(colors) }
    // Written before the children are composed, so the first frame they draw is already
    // the right colour. Writing it afterwards left one frame of the old theme on screen.
    AppTheme.install(desktopPalette)
    CompositionLocalProvider(LocalDesktopPalette provides desktopPalette) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = AppTheme.schemeFor(colors),
            content = content
        )
    }
}
