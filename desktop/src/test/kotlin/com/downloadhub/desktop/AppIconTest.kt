package com.downloadhub.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps one icon across both platforms.
 *
 * The two are the same drawing, not two drawings that happen to look alike: the
 * Android launcher is a vector path, and the Windows icon is rendered from that
 * same path by desktop/dist-tools/Generate-AppIcon.ps1. That only stays
 * true while the path is actually shared, and nothing at build time enforces
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
    /**
     * The launcher crops an adaptive icon to a shape inscribed in the central 72x72, so
     * anything reaching the corners is clipped on a circular mask and not on a squircle.
     * The glyph's box is 135 to 826 on both axes (y negative, as Material publishes it);
     * the group's scale and pivot must land that inside 18..90.
     */
    @Test
    fun theArtworkStaysInsideTheLaunchersMask() {
        val foreground = vector("ic_launcher_foreground.xml")
        fun attr(name: String) = Regex("android:$name=\"(-?[0-9.]+)\"").find(foreground)?.groupValues?.get(1)?.toDouble()
            ?: error("the foreground group has no $name")
        val scale = attr("scaleX")
        assertTrue("the glyph is not scaled evenly", scale == attr("scaleY"))
        fun mapX(v: Double) = (v - attr("pivotX")) * scale + attr("pivotX") + attr("translateX")
        fun mapY(v: Double) = (v - attr("pivotY")) * scale + attr("pivotY") + attr("translateY")
        listOf(mapX(135.0), mapX(826.0), mapY(-826.0), mapY(-135.0)).forEach { edge ->
            assertTrue("the glyph reaches $edge but the mask keeps only 18..90", edge in 18.0..90.0)
        }
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
     * One glyph everywhere: the launcher, the themed icon, the top bar, the .ico generator
     * and the runtime tray/window icon all carry the same Material path, character for
     * character. A second copy that drifts is how the tray and the .ico stop matching.
     */
    @Test
    fun everySurfaceDrawsTheSameGlyph() {
        val glyph = Regex("pathData=\"([^\"]+)\"").find(vector("ic_launcher_foreground.xml"))?.groupValues?.get(1)
            ?: error("no path in the foreground")
        assertTrue("that is not the glyph", glyph.length > 200)
        mapOf(
            "the themed icon" to vector("ic_launcher_monochrome.xml"),
            "the top-bar mark" to vector("ic_app_mark.xml"),
            "the .ico generator" to generator(),
            "the runtime icon" to File("src/main/kotlin/com/downloadhub/desktop/AppArtwork.kt").readText()
        ).forEach { (surface, text) -> assertTrue("$surface draws a different glyph", text.contains(glyph)) }
    }

    /** The tray asks the shared drawing rather than keeping a copy of it. */
    @Test
    fun theTrayIconUsesTheSameArtwork() {
        val tray = File("src/main/kotlin/com/downloadhub/desktop/SystemTrayIcon.kt").readText()
        assertTrue(
            "the tray draws its own icon instead of using AppArtwork, so there are now " +
                "two icons to keep in step",
            tray.contains("AppArtwork.icon(32)")
        )
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
