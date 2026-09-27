package com.downloadhub.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
    )
}

/**
 * Applies the chosen two-tone palette. [appTheme] supplies the colours, while
 * [themeMode] still decides light/dark (and AMOLED forces true black).
 */
@Composable
fun DownloadHubTheme(
    themeMode: ThemeMode,
    appTheme: AppTheme = AppTheme.MINT,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val effectiveTheme = if (themeMode == ThemeMode.AMOLED) AppTheme.AMOLED else appTheme
    MaterialTheme(
        colorScheme = effectiveTheme.colorScheme(dark),
        typography = AppTypography,
        content = {
            MatchSystemBarsToTheme()
            content()
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
