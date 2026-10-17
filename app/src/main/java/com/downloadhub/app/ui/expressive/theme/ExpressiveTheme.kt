package com.downloadhub.app.ui.expressive.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.ui.theme.MatchSystemBarsToTheme

/**
 * The root theme of the redesigned UI.
 *
 * Colours still come from the twelve palettes in `:core` through [AppTheme], so a user's
 * chosen theme and mode carry over untouched. The library ships only a light expressive
 * scheme, so dark and AMOLED are built from the palettes too, then topped up with the
 * secondary, tertiary and surface-container roles by [withExpressiveTones].
 *
 * Motion is the Expressive scheme. Every animation in this UI reads it from
 * `MaterialTheme.motionScheme`; nothing sets its own spring or duration.
 */
@Composable
fun ExpressiveTheme(
    themeMode: ThemeMode,
    appTheme: AppTheme = AppTheme.SIGNAL,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    // AMOLED keeps the chosen accent and only replaces the surfaces with true black.
    val scheme = appTheme
        .colorScheme(dark, pureBlack = themeMode == ThemeMode.AMOLED)
        .withExpressiveTones()
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = ExpressiveTypography
    ) {
        MatchSystemBarsToTheme()
        content()
    }
}
