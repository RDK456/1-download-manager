package com.downloadhub.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.ui.expressive.theme.ExpressiveTypography
import com.downloadhub.app.ui.expressive.theme.withExpressiveTones
import com.downloadhub.app.ui.theme.AppShapes
import com.downloadhub.app.ui.theme.AppTypography
import com.downloadhub.app.ui.theme.LocalLcdGlow
import com.downloadhub.app.ui.theme.MatchSystemBarsToTheme
import com.downloadhub.app.ui.theme.UiGeneration

/**
 * The theme for the look the user chose in Settings (see `UiGeneration`): the redesign or the
 * classic one.
 *
 * Both are the same composable call with different values, never two different calls. That is
 * deliberate: swapping one theme function for another would tear down everything inside it,
 * and the app would lose its place (which page, what was typed) the moment the setting
 * changed. With one call only the colours, type, shapes and motion change, and the screen
 * under them stays where it was.
 */
@Composable
fun AppThemeSwitch(themeMode: ThemeMode, appTheme: AppTheme, content: @Composable () -> Unit) {
    val expressive = UiGeneration.expressive
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    // AMOLED keeps the chosen accent and only replaces the surfaces with true black.
    val base = appTheme.colorScheme(dark, pureBlack = themeMode == ThemeMode.AMOLED)
    MaterialExpressiveTheme(
        colorScheme = if (expressive) base.withExpressiveTones() else base,
        motionScheme = if (expressive) MotionScheme.expressive() else MotionScheme.standard(),
        shapes = if (expressive) null else AppShapes,
        typography = if (expressive) ExpressiveTypography else AppTypography
    ) {
        MatchSystemBarsToTheme()
        CompositionLocalProvider(LocalLcdGlow provides appTheme.accentDark, content = content)
    }
}
