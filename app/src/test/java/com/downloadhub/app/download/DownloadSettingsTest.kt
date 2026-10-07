package com.downloadhub.app.download

import com.downloadhub.app.data.DownloadSettings
import com.downloadhub.app.data.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance
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
    fun amoledModeKeepsTheChosenColourTheme() {
        // Regression: the AMOLED mode used to swap the entire palette for
        // AppTheme.AMOLED, whose accent is mint green, so picking Sunset under
        // AMOLED still painted every other page green.
        AppTheme.entries.forEach { theme ->
            val black = theme.colorScheme(dark = true, pureBlack = true)
            assertEquals(
                "${theme.name} lost its accent under AMOLED",
                theme.accentDark,
                black.primary
            )
            assertEquals(
                androidx.compose.ui.graphics.Color.Black,
                black.background
            )
        }
    }

    @Test
    fun amoledModeDoesNotForceTheMintAccent() {
        AppTheme.entries
            .filter { it != AppTheme.MINT && it != AppTheme.AMOLED }
            .forEach { theme ->
                assertNotEquals(
                    "${theme.name} renders mint green under AMOLED",
                    AppTheme.MINT.accentDark,
                    theme.colorScheme(dark = true, pureBlack = true).primary
                )
            }
    }

    @Test
    fun amoledModeKeepsTogglesVisibleForEveryTheme() {
        // The pure-black scheme has to be built per theme, so its switch colours
        // need the same contrast guarantees as the light and dark ones.
        AppTheme.entries.forEach { theme ->
            val scheme = theme.colorScheme(dark = true, pureBlack = true)
            val checked = kotlin.math.abs(
                scheme.secondaryContainer.luminance() - scheme.onSecondaryContainer.luminance()
            )
            val unchecked = kotlin.math.abs(
                scheme.surfaceVariant.luminance() - scheme.onSurface.luminance()
            )
            assertTrue("${theme.name} checked toggle invisible", checked > 0.25f)
            assertTrue("${theme.name} unchecked toggle invisible", unchecked > 0.3f)
        }
    }

    @Test
    fun amoledIsPureBlack() {
        val scheme = AppTheme.AMOLED.colorScheme(dark = true)
        assertEquals(androidx.compose.ui.graphics.Color.Black, scheme.background)
        assertEquals(androidx.compose.ui.graphics.Color.Black, scheme.surface)
    }

    @Test
    fun amoledIsVisiblyDifferentFromTheRegularDarkThemes() {
        val amoled = AppTheme.AMOLED.colorScheme(dark = true)
        AppTheme.entries.filter { it != AppTheme.AMOLED }.forEach { theme ->
            val dark = theme.colorScheme(dark = true)
            assertNotEquals(
                "${theme.name} dark looks the same as AMOLED",
                amoled.background,
                dark.background
            )
            assertNotEquals(
                "${theme.name} dark surface looks the same as AMOLED",
                amoled.surface,
                dark.surface
            )
        }
    }

    @Test
    fun checkedTogglesHaveAVisibleThumb() {
        // A checked Switch paints the track with secondaryContainer and the thumb
        // with onSecondaryContainer, so they must not be the same brightness.
        AppTheme.entries.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val scheme = theme.colorScheme(dark)
                val track = scheme.secondaryContainer.luminance()
                val thumb = scheme.onSecondaryContainer.luminance()
                val difference = kotlin.math.abs(track - thumb)
                assertTrue(
                    "${theme.name} dark=$dark toggle thumb is invisible " +
                        "(track=$track thumb=$thumb)",
                    difference > 0.25f
                )
            }
        }
    }

    @Test
    fun uncheckedTogglesAreVisibleToo() {
        // A screenshot showed empty pale pills: the unchecked track
        // (surfaceVariant) and thumb (outline) were both dark greys in dark
        // palettes, so the knob disappeared. Explicit switch colours now derive
        // the unchecked thumb from onSurface.
        AppTheme.entries.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val scheme = theme.colorScheme(dark)
                val difference = kotlin.math.abs(
                    scheme.onSurface.luminance() - scheme.surfaceVariant.luminance()
                )
                assertTrue(
                    "${theme.name} dark=$dark unchecked toggle knob is invisible " +
                        "(track=${scheme.surfaceVariant.luminance()} " +
                        "knob=${scheme.onSurface.luminance()})",
                    difference > 0.3f
                )
            }
        }
    }

    @Test
    fun amoledContainersStayVisibleOnBlack() {
        val scheme = AppTheme.AMOLED.colorScheme(dark = true)
        // Compose's luminance() is linearised, so compare sRGB channels directly:
        // containers need a visible lift off pure black or controls disappear.
        assertTrue(
            "surfaceVariant must lift off black (was ${scheme.surfaceVariant.red})",
            scheme.surfaceVariant.red > 0.04f
        )
        assertTrue(
            "outline must be visible on black (was ${scheme.outline.red})",
            scheme.outline.red > 0.15f
        )
        assertTrue(
            "onSurfaceVariant must read on black",
            scheme.onSurfaceVariant.red > 0.4f
        )
    }

    @Test
    fun bodyTextIsLegibleOnEveryBackground() {
        AppTheme.entries.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val scheme = theme.colorScheme(dark)
                val textOnBackground = kotlin.math.abs(
                    scheme.onBackground.luminance() - scheme.background.luminance()
                )
                assertTrue(
                    "${theme.name} dark=$dark text is unreadable on its background",
                    textOnBackground > 0.5f
                )
            }
        }
    }

    @Test
    fun themeLookupFallsBackToAurora() {
        assertEquals(AppTheme.SIGNAL, AppTheme.fromValue(null))
        assertEquals(AppTheme.SIGNAL, AppTheme.fromValue("nonsense"))
        assertEquals(AppTheme.OCEAN, AppTheme.fromValue("OCEAN"))
    }

}
