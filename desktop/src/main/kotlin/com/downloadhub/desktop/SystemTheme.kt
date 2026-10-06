package com.downloadhub.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.downloadhub.core.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Windows' own light-or-dark setting, for the Auto theme: the "apps use light theme" value
 * Settings > Personalisation > Colours writes. Dark when it cannot be read.
 */
object SystemTheme {
    const val AUTO = "AUTO"

    fun isLight(): Boolean = runCatching {
        val process = ProcessBuilder(
            "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x(\d+)""").find(text)?.groupValues?.get(1)?.toInt(16) == 1
    }.getOrDefault(false)
}

/**
 * The mode to draw in: the stored one, or for Auto whatever Windows is set to - checked
 * every few seconds, so switching Windows to light or dark carries the app with it.
 */
@Composable
fun rememberResolvedThemeMode(stored: String): ThemeMode {
    if (!stored.equals(SystemTheme.AUTO, ignoreCase = true)) return ThemeMode.fromValue(stored)
    val light by produceState(initialValue = false) {
        while (true) {
            value = withContext(Dispatchers.IO) { SystemTheme.isLight() }
            delay(5_000)
        }
    }
    return if (light) ThemeMode.LIGHT else ThemeMode.DARK
}
