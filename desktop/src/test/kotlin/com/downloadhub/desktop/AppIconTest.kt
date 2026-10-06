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
     * The launcher crops an adaptive icon to a circle of radius 33 around (54,54) on its
     * roundest mask, so anything outside it is clipped on one phone and not another.
     * Each glyph's published box (the arrow 135 to 826, the badge's disc 55 to 906, y
     * negative as Material publishes it) is mapped through its group and checked against
     * that circle, corner by corner for the arrow and as a disc for the badge.
     */
    @Test
    fun theArtworkStaysInsideTheLaunchersMask() {
        val groups = Regex("<group([^>]*pivotX[^>]*)>").findAll(vector("ic_launcher_foreground.xml")).map { it.groupValues[1] }.toList()
        assertTrue("expected the arrow and the badge, found ${groups.size} groups", groups.size == 2)
        fun place(group: String): (Double, Double) -> Pair<Double, Double> {
            fun attr(name: String) = Regex("android:$name=\"(-?[0-9.]+)\"").find(group)?.groupValues?.get(1)?.toDouble()
                ?: error("a group has no $name")
            val scale = attr("scaleX")
            assertTrue("a glyph is not scaled evenly", scale == attr("scaleY"))
            return { x, y ->
                ((x - attr("pivotX")) * scale + attr("pivotX") + attr("translateX")) to
                    ((y - attr("pivotY")) * scale + attr("pivotY") + attr("translateY"))
            }
        }
        fun fromCentre(p: Pair<Double, Double>) = Math.hypot(p.first - 54, p.second - 54)

        // The arrow's tray has rounded corners of about 94 units, so its true extent is a
        // little inside the corner points: allow that much.
        val arrow = place(groups[0])
        val roundness = 94 * (1 - 1 / Math.sqrt(2.0)) * Math.sqrt(2.0) * 0.058
        listOf(135.0 to -826.0, 826.0 to -826.0, 135.0 to -135.0, 826.0 to -135.0).forEach { (x, y) ->
            val reach = fromCentre(arrow(x, y)) - roundness
            assertTrue("the arrow reaches $reach from the centre; the mask keeps 33", reach <= 33.0)
        }
        val badge = place(groups[1])
        val centre = badge(480.5, -480.5)
        val radius = badge(906.0, -480.5).first - centre.first
        assertTrue("the badge reaches ${fromCentre(centre) + radius}; the mask keeps 33", fromCentre(centre) + radius <= 33.0)
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
     * One drawing everywhere: the launcher, the themed icon, the top bar, the .ico
     * generator and the runtime tray/window icon all carry the same two Material paths,
     * character for character. A copy that drifts is how the tray and the .ico stop
     * matching.
     */
    @Test
    fun everySurfaceDrawsTheSameGlyphs() {
        val glyphs = Regex("pathData=\"([^\"]+)\"").findAll(vector("ic_launcher_foreground.xml")).map { it.groupValues[1] }.toList()
        assertTrue("expected the arrow and the badge", glyphs.size == 2 && glyphs.all { it.length > 200 })
        mapOf(
            "the themed icon" to vector("ic_launcher_monochrome.xml"),
            "the top-bar mark" to vector("ic_app_mark.xml"),
            "the .ico generator" to generator(),
            "the runtime icon" to File("src/main/kotlin/com/downloadhub/desktop/AppArtwork.kt").readText()
        ).forEach { (surface, text) -> glyphs.forEach { assertTrue("$surface draws a different glyph", text.contains(it)) } }
        // And at the same places: the arrow at (50,58) x0.058, the badge at (68,38) x0.0247.
        listOf(generator(), File("src/main/kotlin/com/downloadhub/desktop/AppArtwork.kt").readText()).forEach { text ->
            assertTrue("a surface places the arrow differently", text.contains("50") && text.contains("58") && text.contains("0.058"))
            assertTrue("a surface places the badge differently", text.contains("68") && text.contains("38") && text.contains("0.0247"))
        }
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
