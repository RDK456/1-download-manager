package com.downloadhub.app.data.model

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Two-tone colour schemes: a tinted surface with one strong accent, so the app
 * reads as "white + green", "white + blue" and so on. Every theme ships a light
 * and a dark variant; AMOLED is pure black.
 */
enum class AppTheme(
    val label: String,
    val accent: Color,
    val accentDark: Color,
    val surfaceHint: Color
) {
    MINT("Mint", Color(0xFF10B981), Color(0xFF34D399), Color(0xFFE7F8F1)),
    FOREST("Forest", Color(0xFF15803D), Color(0xFF4ADE80), Color(0xFFE8F5EC)),
    OCEAN("Ocean", Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFFE6F4FB)),
    ROYAL("Royal", Color(0xFF4F46E5), Color(0xFF818CF8), Color(0xFFECEBFE)),
    VIOLET("Violet", Color(0xFF7C3AED), Color(0xFFA78BFA), Color(0xFFF1EAFE)),
    SUNSET("Sunset", Color(0xFFEA580C), Color(0xFFFB923C), Color(0xFFFDEEE2)),
    ROSE("Rose", Color(0xFFE11D48), Color(0xFFFB7185), Color(0xFFFDE9EE)),
    SLATE("Slate", Color(0xFF475569), Color(0xFF94A3B8), Color(0xFFEEF1F5)),
    AMOLED("AMOLED", Color(0xFF22C55E), Color(0xFF4ADE80), Color(0xFF000000));

    fun colorScheme(dark: Boolean): ColorScheme = if (this == AMOLED) {
        amoledScheme()
    } else if (dark) {
        darkScheme()
    } else {
        lightScheme()
    }

    private fun lightScheme(): ColorScheme = lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = surfaceHint,
        onPrimaryContainer = shade(accent, 0.75f),
        secondary = shade(accent, 0.72f),
        onSecondary = Color.White,
        // A checked Switch paints its track with secondaryContainer and its
        // thumb with onSecondaryContainer, so these two must differ in
        // brightness or the toggle looks like an empty pill.
        secondaryContainer = tint(accent, 0.25f),
        onSecondaryContainer = Color.White,
        tertiary = shade(accent, 0.55f),
        onTertiary = Color.White,
        background = Color(0xFFFBFCFC),
        onBackground = Color(0xFF11181A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF11181A),
        surfaceVariant = Color(0xFFEDF2F3),
        onSurfaceVariant = Color(0xFF445457),
        outline = Color(0xFFB7C3C5),
        outlineVariant = Color(0xFFDCE3E4),
        error = Color(0xFFBA1A1A),
        onError = Color.White
    )

    private fun darkScheme(): ColorScheme = darkColorScheme(
        primary = accentDark,
        onPrimary = Color(0xFF06210F),
        primaryContainer = shade(accentDark, 0.62f),
        onPrimaryContainer = Color(0xFFE6FFF2),
        secondary = shade(accentDark, 0.72f),
        onSecondary = Color(0xFF06180C),
        // Dark themes keep a visibly grey surface so that AMOLED (pure black)
        // reads as a deliberate choice rather than a slightly darker shade.
        secondaryContainer = shade(accentDark, 0.55f),
        onSecondaryContainer = Color(0xFFEFFCF4),
        tertiary = tint(accentDark, 0.6f),
        onTertiary = Color(0xFF06210F),
        background = Color(0xFF12171A),
        onBackground = Color(0xFFE6EDEE),
        surface = Color(0xFF1A2124),
        onSurface = Color(0xFFE6EDEE),
        surfaceVariant = Color(0xFF262F33),
        onSurfaceVariant = Color(0xFFB4C0C2),
        outline = Color(0xFF46545A),
        outlineVariant = Color(0xFF2C3639),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005)
    )

    private fun amoledScheme(): ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF04220D),
        primaryContainer = shade(accent, 0.7f),
        onPrimaryContainer = Color(0xFFDFFFF0),
        secondary = shade(accent, 0.75f),
        onSecondary = Color(0xFF04220D),
        secondaryContainer = shade(accent, 0.55f),
        onSecondaryContainer = Color(0xFFEFFCF4),
        tertiary = tint(accent, 0.6f),
        onTertiary = Color(0xFF04220D),
        // True black, with just enough lift on containers that switches, chips
        // and cards remain visible instead of vanishing into the background.
        background = Color(0xFF000000),
        onBackground = Color(0xFFEDF3F3),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFEDF3F3),
        surfaceVariant = Color(0xFF0D0F0F),
        onSurfaceVariant = Color(0xFFA8B4B5),
        outline = Color(0xFF3A3A3A),
        outlineVariant = Color(0xFF1B1B1B),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005)
    )

    /** Perceived brightness of this theme's accent, used by tests and previews. */
    fun accentLuminance(): Float = 0.299f * accent.red + 0.587f * accent.green + 0.114f * accent.blue

    companion object {
        fun fromValue(value: String?): AppTheme =
            entries.firstOrNull { it.name == value } ?: MINT
    }
}

/** Multiplies the colour channels, used to build deeper container tones. */
private fun shade(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = 1f
)

/** Blends the colour towards white, used for subtle tinted surfaces. */
private fun tint(color: Color, factor: Float): Color = Color(
    red = color.red + (1f - color.red) * factor,
    green = color.green + (1f - color.green) * factor,
    blue = color.blue + (1f - color.blue) * factor,
    alpha = 1f
)
