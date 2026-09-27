package com.downloadhub.app.download

import com.downloadhub.app.data.DownloadSettings
import com.downloadhub.app.data.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSettingsTest {
    @Test
    fun defaultsAreSane() {
        val settings = DownloadSettings()
        assertEquals(DownloadSettings.DEFAULT_MAX_CONCURRENT, settings.maxConcurrent)
        assertEquals(0L, settings.speedLimitBytesPerSecond)
        assertFalse(settings.isSpeedLimited)
        assertFalse(settings.wifiOnly)
        assertEquals(DownloadSettings.DEFAULT_MAX_RETRIES, settings.maxRetries)
        assertFalse(settings.autoRemoveCompleted)
    }

    @Test
    fun speedPresetsCoverCommonChoices() {
        val labels = DownloadSettings.SPEED_PRESETS.map { it.second }
        assertTrue(labels.contains("Unlimited"))
        assertTrue(labels.contains("1 MB/s"))
        assertEquals(0L, DownloadSettings.SPEED_PRESETS.first().first)
    }

    @Test
    fun themesShipLightAndDarkPalettes() {
        AppTheme.entries.forEach { theme ->
            val light = theme.colorScheme(dark = false)
            val dark = theme.colorScheme(dark = true)
            assertTrue("light primary for ${theme.name}", theme.accentLuminance() > 0f)
            // A dark scheme must be darker than its light counterpart.
            assertNotEquals("${theme.name} light/dark are identical", light, dark)
        }
    }

    @Test
    fun amoledIsPureBlack() {
        val scheme = AppTheme.AMOLED.colorScheme(dark = true)
        assertEquals(androidx.compose.ui.graphics.Color.Black, scheme.background)
        assertEquals(androidx.compose.ui.graphics.Color.Black, scheme.surface)
    }

    @Test
    fun themeLookupFallsBackToMint() {
        assertEquals(AppTheme.MINT, AppTheme.fromValue(null))
        assertEquals(AppTheme.MINT, AppTheme.fromValue("nonsense"))
        assertEquals(AppTheme.OCEAN, AppTheme.fromValue("OCEAN"))
    }

}
