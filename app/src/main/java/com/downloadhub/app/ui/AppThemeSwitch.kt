package com.downloadhub.app.ui

import androidx.compose.runtime.Composable
import com.downloadhub.app.BuildConfig
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.ui.expressive.theme.ExpressiveTheme
import com.downloadhub.app.ui.theme.DownloadHubTheme

/**
 * Picks the theme for the build. While the redesign is being moved across screen by screen,
 * screens not yet rebuilt still draw under the new theme, and this switch (one build flag,
 * `EXPRESSIVE_UI`) is the way back to the old look if the new one has to be pulled. Removed
 * with the old UI in the cutover.
 */
@Composable
fun AppThemeSwitch(themeMode: ThemeMode, appTheme: AppTheme, content: @Composable () -> Unit) {
    if (BuildConfig.EXPRESSIVE_UI) ExpressiveTheme(themeMode, appTheme, content)
    else DownloadHubTheme(themeMode, appTheme, content)
}
