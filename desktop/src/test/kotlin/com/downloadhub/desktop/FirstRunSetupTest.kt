package com.downloadhub.desktop

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run unpacking is easy to break silently.
 *
 * yt-dlp and the browser extension travel inside the app's own jar. A refactor once
 * dropped the line that copied them out, and nothing failed: no compile error, no
 * test failure, no crash. The app simply came up with no YouTube support and no way
 * to install the extension, which is the worst possible failure mode for a feature
 * that looks present in the UI.
 *
 * These tests read the real build output, so the resources being shipped and the
 * code that unpacks them are checked together.
 */
class FirstRunSetupTest {

    private fun buildDir(): File? = listOf(
        File("build"),
        File("../desktop/build")
    ).firstOrNull { it.isDirectory }

    private fun desktopJar(): File? = buildDir()
        ?.resolve("compose/binaries/main/app/1DownloadManager/app")
        ?.listFiles { f -> f.name.startsWith("desktop-") && f.name.endsWith(".jar") }
        ?.firstOrNull()

    @Test
    fun theAppUnpacksItsToolsAndExtensionOnStartup() {
        val source = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt")
        assertTrue("controller source not found at $source", source.isFile)
        val text = source.readText()

        assertTrue(
            "the controller must unpack the bundled yt-dlp on startup, or YouTube " +
                "downloads silently do nothing in an installed copy",
            text.contains("tools.install()")
        )
        assertTrue(
            "the controller must unpack the browser extension on startup, or the " +
                "user is told to load a folder that does not exist",
            text.contains("extension.install()")
        )
    }

    @Test
    fun theControllerStartsThemFromInitialisation() {
        val source = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt")
        val text = source.readText()
        val initBlock = text.substringAfter("    init {").substringBefore("\n    }")
        assertTrue(
            "the unpacking must run from init, not from a function the user has to " +
                "trigger; the app has no startup screen to call one from",
            initBlock.contains("tools.install()") && initBlock.contains("extension.install()")
        )
    }

    @Test
    fun theStagedResourcesArePresentInTheBuild() {
        val resources = buildDir()?.resolve("resources/main")
        // Nothing to check before the module has been built.
        org.junit.Assume.assumeTrue("no build output to inspect", resources?.isDirectory == true)

        val lib = File(resources, "lib")
        assertTrue("yt-dlp is not staged", File(lib, "yt-dlp.exe").isFile)
        // ffmpeg must NOT be staged: it is 100 MB, and bundling it made the install
        // a thousand files, which is what broke the JVM launch on a machine with an
        // aggressive security agent. It is downloaded on demand instead.
        assertFalse(
            "ffmpeg must be fetched on demand, not bundled",
            File(lib, "ffmpeg.exe").exists()
        )

        // Both browser builds have to ship, because Firefox cannot load the Chromium
        // one at all and a Firefox user told to load it gets an extension that
        // silently does nothing.
        val extension = File(resources, "browser-extension")
        listOf("chromium", "firefox").forEach { build ->
            listOf("manifest.json", "background.js", "content.js", "popup.html", "popup.js")
                .forEach { name ->
                    assertTrue(
                        "$build/$name is not staged for the app to unpack",
                        File(File(extension, build), name).isFile
                    )
                }
        }
    }

    /**
     * Guards the reason the two builds exist.
     *
     * This is the sort of thing that is quietly "cleaned up" later by someone who
     * sees two copies of the same extension and assumes one is a leftover.
     */
    @Test
    fun theFirefoxBuildCannotBeTheChromiumManifest() {
        val chromium = File("browser-extension/chromium/manifest.json")
        val firefox = File("browser-extension/firefox/manifest.json")
        org.junit.Assume.assumeTrue("extension sources are not present", chromium.isFile && firefox.isFile)

        val firefoxText = firefox.readText()
        assertTrue(
            "Firefox has no service worker support for MV3 yet; it needs background.scripts",
            firefoxText.contains("\"scripts\"")
        )
        assertTrue(
            "the Firefox build must declare an add-on id",
            firefoxText.contains("browser_specific_settings")
        )
        assertTrue(
            "the Firefox build must use the browser.* API, not chrome.*",
            File("browser-extension/firefox/background.js").readText()
                .contains("typeof browser")
        )
    }

    @Test
    fun theStagedResourcesEndUpInsideThePackagedJar() {
        val jar = desktopJar()
        org.junit.Assume.assumeTrue("no packaged app image to inspect", jar != null)
        val entries = java.util.zip.ZipFile(jar!!).use { zip ->
            zip.entries().toList().map { it.name }
        }
        assertTrue(
            "yt-dlp must be inside the jar the installer ships",
            entries.any { it == "lib/yt-dlp.exe" }
        )
        // Both variants, each in its own folder: Firefox cannot load the Chromium
        // build, so shipping only one of them helps half the users and nobody else.
        listOf("chromium", "firefox").forEach { build ->
            listOf("manifest.json", "background.js", "content.js", "popup.html", "popup.js")
                .forEach { name ->
                    assertTrue(
                        "browser-extension/$build/$name must be inside the jar the installer ships",
                        entries.any { it == "browser-extension/$build/$name" }
                    )
                }
        }
    }

    /**
     * Keeps the 100 MB from creeping back in.
     *
     * ffmpeg is fetched on demand now. Bundling it made the install a thousand files
     * and it failed with "Failed to launch JVM" on a machine whose security agent
     * locks runtime files while they are written, so a regression here is a
     * regression users will see as a broken install rather than a slow one.
     */
    @Test
    fun theAppJarStaysSmallEnoughToUnpackCleanly() {
        val jar = desktopJar()
        org.junit.Assume.assumeTrue("no packaged app image to inspect", jar != null)
        val megabytes = jar!!.length() / (1024.0 * 1024.0)
        assertTrue(
            "the app jar is ${"%.0f".format(megabytes)} MB; anything near the old " +
                "36 MB material-icons jar means a large dependency crept back in",
            megabytes < 30.0
        )
    }
}
