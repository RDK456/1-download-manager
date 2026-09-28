package com.downloadhub.desktop

import java.io.File
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
        assertTrue("ffmpeg is not staged", File(lib, "ffmpeg.exe").isFile)

        val extension = File(resources, "browser-extension")
        listOf("manifest.json", "background.js", "content.js", "popup.html", "popup.js")
            .forEach { name ->
                assertTrue("$name is not staged for the app to unpack", File(extension, name).isFile)
            }
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
        assertTrue(
            "the browser extension must be inside the jar the installer ships",
            entries.any { it == "browser-extension/manifest.json" }
        )
    }
}
