package com.downloadhub.app.data.model

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.downloadhub.core.ThemeMode as CoreThemeMode
import com.downloadhub.core.ThemePalette as CoreThemePalette
import com.downloadhub.core.resolveThemeColors

/**
 * The nine themes, and what each one resolves to.
 *
 * The names, accents and light-surface tints used to be declared here, and the desktop app
 * had a single hardcoded green beside them. Nothing tied the two together, so "the same
 * themes on both" was something to keep remembering rather than something the build could
 * check - and the way it usually fails is quietly: one app gains a theme, or renames one,
 * and nobody notices until a user has chosen differently on two devices.
 *
 * So the palette now lives in `:core`, as plain numbers with no Compose type, and this
 * enum is a view over it. The names and accents are the core's by construction; what is
 * left here is the one thing core cannot do, which is turning resolved colours into a
 * Compose scheme.
 *
 * The Android app is the one that had the working themes, so the direction of the change
 * matters: this app now follows `:core`, and the desktop app has been brought up to it.
 */
enum class AppTheme(
    /** The core palette behind this theme. The source of every value below. */
    val palette: CoreThemePalette
) {
    MINT(CoreThemePalette.MINT),
    FOREST(CoreThemePalette.FOREST),
    OCEAN(CoreThemePalette.OCEAN),
    ROYAL(CoreThemePalette.ROYAL),
    VIOLET(CoreThemePalette.VIOLET),
    SUNSET(CoreThemePalette.SUNSET),
    ROSE(CoreThemePalette.ROSE),
    SLATE(CoreThemePalette.SLATE),
    AMOLED(CoreThemePalette.AMOLED);

    val label: String get() = palette.label

    /** The stored name, which is the core's. Stable: renaming one would reset a choice. */
    val value: String get() = palette.value

    val accent: Color get() = Color(palette.accent)
    val accentDark: Color get() = Color(palette.accentDark)
    val surfaceHint: Color get() = Color(palette.lightSurfaceHint)

    /**
     * Resolves the palette for a mode.
     *
     * [pureBlack] is the AMOLED *mode*. It only replaces the background and surface with
     * true black; the accent still comes from this theme. The AMOLED mode therefore
     * composes with any colour theme, and selecting it no longer discards the colour the
     * user chose - which is what conflating a mode with a palette caused before.
     */
    fun colorScheme(dark: Boolean, pureBlack: Boolean = false): ColorScheme =
        schemeOf(resolveThemeColors(palette, modeOf(dark, pureBlack)))

    private fun modeOf(dark: Boolean, pureBlack: Boolean): CoreThemeMode = when {
        pureBlack || this == AMOLED -> CoreThemeMode.AMOLED
        dark -> CoreThemeMode.DARK
        else -> CoreThemeMode.LIGHT
    }

    /** Perceived brightness of this theme's accent, used by tests and previews. */
    fun accentLuminance(): Float = palette.accentLuminance()

    companion object {
        fun fromValue(value: String?): AppTheme =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) } ?: MINT
    }
}

/**
 * Builds a Compose scheme from resolved colours.
 *
 * The same builder on every platform and every mode, because the alternative is what the
 * desktop app had: a second hand-written scheme beside the first, with its own tints
 * chosen against one green, so the two drifted and the flat-drawn parts of the desktop
 * window followed neither.
 */
private fun schemeOf(c: com.downloadhub.core.ThemeColors): ColorScheme {
    val accent = Color(c.accent)
    val onAccent = Color(c.onAccent)
    val container = Color(c.accentContainer)
    val onContainer = Color(c.onAccentContainer)
    val onSurface = Color(c.onSurface)
    val muted = Color(c.muted)
    val raised = Color(c.raised)
    val background = Color(c.background)
    val surface = Color(c.surface)
    val outline = Color(c.outline)
    val outlineVariant = Color(c.outlineVariant)
    val error = Color(c.error)
    val onError = Color(c.onError)

    return if (c.isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = raised,
            onSurfaceVariant = muted,
            outline = outline,
            outlineVariant = outlineVariant,
            error = error,
            onError = onError
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = container,
            onSecondaryContainer = onContainer,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = raised,
            onSurfaceVariant = muted,
            outline = outline,
            outlineVariant = outlineVariant,
            error = error,
            onError = onError
        )
    }
}
