package com.downloadhub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
        content = content
    )
}
