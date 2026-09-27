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
        onPrimaryContainer = shade(accent, 0.82f),
        secondary = shade(accent, 0.78f),
        onSecondary = Color.White,
        secondaryContainer = tint(accent, 0.14f),
        onSecondaryContainer = shade(accent, 0.78f),
        tertiary = shade(accent, 0.86f),
        background = Color(0xFFFBFCFC),
        onBackground = Color(0xFF11181A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF11181A),
        surfaceVariant = tint(accent, 0.08f),
        onSurfaceVariant = Color(0xFF4B5A5D),
        outline = tint(accent, 0.35f),
        outlineVariant = tint(accent, 0.16f)
    )

    private fun darkScheme(): ColorScheme = darkColorScheme(
        primary = accentDark,
        onPrimary = Color(0xFF06210F),
        primaryContainer = shade(accentDark, 0.68f),
        onPrimaryContainer = Color(0xFFE6FFF2),
        secondary = shade(accentDark, 0.72f),
        onSecondary = Color(0xFF06180C),
        secondaryContainer = shade(accentDark, 0.62f),
        onSecondaryContainer = Color(0xFFE6FFF2),
        tertiary = shade(accentDark, 0.8f),
        background = Color(0xFF0D1213),
        onBackground = Color(0xFFE3EAEB),
        surface = Color(0xFF141A1C),
        onSurface = Color(0xFFE3EAEB),
        surfaceVariant = tint(accentDark, 0.14f),
        onSurfaceVariant = Color(0xFFA9B6B8),
        outline = tint(accentDark, 0.4f),
        outlineVariant = tint(accentDark, 0.2f)
    )

    private fun amoledScheme(): ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF04220D),
        primaryContainer = shade(accent, 0.7f),
        onPrimaryContainer = Color(0xFFDFFFF0),
        secondary = shade(accent, 0.75f),
        onSecondary = Color(0xFF04220D),
        secondaryContainer = shade(accent, 0.6f),
        onSecondaryContainer = Color(0xFFDFFFF0),
        tertiary = shade(accent, 0.85f),
        background = Color(0xFF000000),
        onBackground = Color(0xFFEDF3F3),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFEDF3F3),
        surfaceVariant = Color(0xFF101716),
        onSurfaceVariant = Color(0xFF9FADAC),
        outline = Color(0xFF2A3A38),
        outlineVariant = Color(0xFF172220)
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
