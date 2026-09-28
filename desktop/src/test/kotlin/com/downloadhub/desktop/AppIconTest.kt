package com.downloadhub.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps one icon across both platforms.
 *
 * The two are the same drawing, not two drawings that happen to look alike: the
 * Android launcher is a set of vector paths, and the Windows icon is rasterised from
 * those same coordinates by desktop/dist-tools/Generate-AppIcon.ps1. That only stays
 * true while the coordinates are actually shared, and nothing at build time enforces
 * that, so it is enforced here.
 */
class AppIconTest {

    private fun vector(name: String) = File("../app/src/main/res/drawable/$name").readText()

    private fun generator() = File("dist-tools/Generate-AppIcon.ps1").readText()

    @Test
    fun theAndroidLauncherIsAnAdaptiveIconUsingTheseDrawables() {
        val adaptive = File("../app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(
            "the adaptive icon no longer references the shared foreground: $adaptive",
            adaptive.contains("@drawable/ic_launcher_foreground")
        )
        assertTrue(
            "the adaptive icon no longer references the shared background: $adaptive",
            adaptive.contains("@drawable/ic_launcher_background")
        )
        // A themed icon is its own drawable, not a tinted copy of the same one.
        val themed = File("../app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml").readText()
        assertTrue(
            "the themed icon lost its monochrome layer: $themed",
            themed.contains("ic_launcher_monochrome")
        )
    }

    /**
     * The launcher crops an adaptive icon to a shape inscribed in the central 72x72,
     * so anything reaching the corners is clipped on a circular mask and not on a
     * squircle - the same artwork then looks right on one phone and wrong on another.
     */
    @Test
    fun theArtworkStaysInsideTheLaunchersMask() {
        val foreground = vector("ic_launcher_foreground.xml")
        // Only the path data. Reading the whole file picks up the digits in colour
        // literals - #FFFFFFFF is full of 8s - and calls them coordinates.
        val pathData = Regex("pathData=\"([^\"]+)\"")
            .findAll(foreground)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("no path data found in the foreground", pathData.isNotEmpty())
        val positions = Regex("(\\d+(?:\\.\\d+)?)")
            .findAll(pathData.joinToString(" "))
            .map { it.groupValues[1].toDouble() }
            .filter { it > 5.0 && it < 100.0 }
            .toList()
        assertTrue("no coordinates found", positions.isNotEmpty())
        val min = positions.min()
        val max = positions.max()
        assertTrue(
            "the artwork reaches $min but the mask starts at 18",
            min >= 18.0
        )
        assertTrue(
            "the artwork reaches $max but the mask ends at 90",
            max <= 90.0
        )
    }

    /** The Windows build has to be given an icon, or the exe falls back to the Java cup. */
    @Test
    fun theWindowsBuildUsesTheIcon() {
        val build = File("build.gradle.kts").readText()
        assertTrue(
            "jpackage is not given an icon, so the exe, the Start Menu entry and the " +
                "taskbar all get its default Java cup",
            build.contains("iconFile.set(")
        )
        assertTrue("and it does not point at the generated icon", build.contains("app-icon.ico"))
    }

    @Test
    fun theIconFileHasEverySizeWindowsAsksFor() {
        val ico = File("dist-tools/app-icon.ico")
        assertTrue("desktop/dist-tools/app-icon.ico is missing", ico.isFile)
        val bytes = ico.readBytes()
        assertTrue("that file is too small to be an icon", bytes.size > 1000)
        assertTrue("reserved field should be 0", bytes[0].toInt() == 0 && bytes[1].toInt() == 0)
        assertTrue("type field should be 1 (icon)", bytes[2].toInt() == 1 && bytes[3].toInt() == 0)

        val count = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
        assertTrue("no entries in the icon", count > 0)
        val sizes = (0 until count).map { entry ->
            // A stored dimension of 0 means 256, which is how the format says it.
            val dimension = bytes[6 + entry * 16].toInt() and 0xFF
            if (dimension == 0) 256 else dimension
        }
        listOf(16, 32, 48, 256).forEach { required ->
            assertTrue(
                "the icon has no ${required}px entry (it has $sizes). The taskbar " +
                    "needs 16, a desktop shortcut 32, and Explorer at large sizes " +
                    "wants 256",
                sizes.contains(required)
            )
        }
    }

    /**
     * The same coordinates on both sides, which is the whole reason the desktop icon
     * is generated from the vector rather than drawn a second time.
     */
    @Test
    fun bothPlatformsUseTheSameCoordinates() {
        val foreground = vector("ic_launcher_foreground.xml")
        val script = generator()
        // Consecutive pairs from the folder and the arrow, which is what makes them
        // meaningful: an arbitrary pair such as 74,60 does not appear in either file
        // because those two points are not neighbours in the path.
        listOf("34,38", "45,29", "72,36", "36,60", "49,39", "66,48", "42,48")
            .forEach { pair ->
                assertTrue(
                    "the point $pair is in the generator but not the Android vector",
                    script.contains(pair)
                )
                assertTrue(
                    "the point $pair is in the Android vector but not the generator",
                    foreground.contains(pair)
                )
            }
    }

    /** The tray is the app icon too, minus the wordmark, which cannot be read at 16 px. */
    @Test
    fun theTrayIconUsesTheSameArtwork() {
        val tray = File("src/main/kotlin/com/downloadhub/desktop/SystemTrayIcon.kt").readText()
        assertTrue(
            "the tray icon has gone back to the old green arrow",
            !tray.contains("0x34, 0xD3, 0x99")
        )
        // It must ask the shared drawing rather than keeping a copy of it. A second
        // copy is how the tray and the .ico quietly stop matching.
        assertTrue(
            "the tray draws its own icon instead of using AppArtwork, so there are now " +
                "two icons to keep in step",
            tray.contains("AppArtwork.icon(32)")
        )
        val artwork = File("src/main/kotlin/com/downloadhub/desktop/AppArtwork.kt").readText()
        listOf("34f, 38f", "49f, 39f", "54f, 58f").forEach { point ->
            assertTrue(
                "the shared drawing does not draw the launcher point $point",
                artwork.contains(point)
            )
        }
    }

    /**
     * The title-bar icon has to be set by the app, not inherited from the exe.
     *
     * The exe does get the right icon through jpackage's iconFile, which is what made
     * this look unnecessary - but a report of "the icon did not change" cannot be
     * diagnosed when one of the two sources is outside the code. Setting it at runtime
     * means the answer does not depend on how the app was started.
     */
    @Test
    fun theWindowIconIsSetByTheAppItself() {
        val main = File("src/main/kotlin/com/downloadhub/desktop/Main.kt").readText()
        assertTrue(
            "the window icon is left to the exe's resources, which is jpackage's " +
                "default Java cup whenever the app is not started from its own exe",
            main.contains("AppArtwork.windowIcons()")
        )
        assertTrue(
            "and it is not actually applied to the AWT window",
            main.contains("window.iconImages =")
        )
    }
}
