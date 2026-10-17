package com.downloadhub.app.ui.expressive.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Fills in the roles the palette bridge leaves at Material's baseline.
 *
 * The bridge in `AppTheme` sets one accent and reuses its container for the secondary role,
 * and it never sets the surface container steps. Left alone, those fall back to Material's
 * purple-tinted defaults, which look wrong against a green or orange palette. Expressive
 * screens lean on exactly these roles (rows on `surfaceContainer`, banners on tertiary and
 * secondary containers), so they are derived here from the palette's own colours.
 */
internal fun ColorScheme.withExpressiveTones(): ColorScheme {
    val surface = surface.toArgb()
    val ink = onSurface.toArgb()
    fun step(t: Float) = Color(ToneMath.mix(surface, ink, t))
    val primary = primary.toArgb()
    val primaryContainer = primaryContainer.toArgb()
    val secondary = ToneMath.scaleSaturation(primary, 0.6f)
    val secondaryContainer = ToneMath.scaleSaturation(primaryContainer, 0.55f)
    val tertiary = ToneMath.rotateHue(primary, TERTIARY_TURN)
    val tertiaryContainer = ToneMath.rotateHue(primaryContainer, TERTIARY_TURN)
    val onPrimary = onPrimary.toArgb()
    val onPrimaryContainer = onPrimaryContainer.toArgb()
    return copy(
        secondary = Color(secondary),
        onSecondary = Color(ToneMath.readableOn(secondary, onPrimary)),
        secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(ToneMath.readableOn(secondaryContainer, onPrimaryContainer)),
        tertiary = Color(tertiary),
        onTertiary = Color(ToneMath.readableOn(tertiary, onPrimary)),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(ToneMath.readableOn(tertiaryContainer, onPrimaryContainer)),
        surfaceContainerLowest = background,
        surfaceContainerLow = step(0.03f),
        surfaceContainer = step(0.06f),
        surfaceContainerHigh = step(0.09f),
        surfaceContainerHighest = step(0.13f),
    )
}

/** How far the tertiary hue sits from the accent. 60 degrees reads as a neighbour, not a clash. */
internal const val TERTIARY_TURN = 60f
