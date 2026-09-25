package com.downloadhub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.downloadhub.app.data.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1769E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF002F68),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2F1E5),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF9A4B08),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC4),
    onTertiaryContainer = Color(0xFF351000),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF172033),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFE2E8F2),
    onSurfaceVariant = Color(0xFF435269),
    outline = Color(0xFF748198),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC7FF),
    onPrimary = Color(0xFF002F68),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFF80D5C7),
    onSecondary = Color(0xFF003730),
    secondaryContainer = Color(0xFF005047),
    onSecondaryContainer = Color(0xFFB2F1E5),
    tertiary = Color(0xFFFFB68A),
    onTertiary = Color(0xFF552006),
    tertiaryContainer = Color(0xFF77330D),
    onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF10151F),
    onBackground = Color(0xFFE2E8F2),
    surface = Color(0xFF171D28),
    onSurface = Color(0xFFE2E8F2),
    surfaceVariant = Color(0xFF3B4556),
    onSurfaceVariant = Color(0xFFC0C8D8),
    outline = Color(0xFF8A93A6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF66D9FF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003D4A),
    onPrimaryContainer = Color(0xFFB8EDFF),
    secondary = Color(0xFF66E0B8),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF00382C),
    onSecondaryContainer = Color(0xFFB8F2DA),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF4A2600),
    onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF171717),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF707070),
    error = Color(0xFFFF8A80),
    onError = Color.Black
)

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
    )
}

@Composable
fun DownloadHubTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val colors = when (themeMode) {
        ThemeMode.AMOLED -> AmoledColors
        else -> if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
