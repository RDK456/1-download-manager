package com.downloadhub.desktop

import java.io.File
import org.junit.Assert.assertEquals
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

    /** yt-dlp and ffmpeg, in a jar of their own so that updates leave them out. */
    private fun toolsJar(): File? = buildDir()
        ?.resolve("compose/binaries/main/app/1DownloadManager/app")
        ?.listFiles { f -> f.name.startsWith("bundled-tools") && f.name.endsWith(".jar") }
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

        // Staged where the bundled-tools jar is packed from, not into the app's own
        // resources: in the app jar they made every update carry them again.
        val lib = buildDir()!!.resolve("ytbin")
        assertTrue("yt-dlp is not staged", File(lib, "yt-dlp.exe").isFile)
        // ffmpeg ships now, because every quality YouTube offers is two streams that have
        // to be muxed and there is no merge without it. See the file-count test below for
        // why it ships as one file rather than as the archive's contents.
        assertTrue("ffmpeg is not staged", File(lib, "ffmpeg.exe").isFile)

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
        val tools = toolsJar()
        assertTrue("the installer must ship the bundled-tools jar beside the app jar", tools != null)
        val toolEntries = java.util.zip.ZipFile(tools!!).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("yt-dlp must be inside the tools jar the installer ships", "lib/yt-dlp.exe" in toolEntries)
        assertFalse(
            "yt-dlp must not be in the app jar too: that is what made every update carry it",
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
     * The test that has to survive this change, and the reason for it.
     *
     * ffmpeg was bundled once and removed, and the cause was never size. The earlier build
     * shipped the archive's *extracted* tree - ffmpeg, ffprobe, presets, headers,
     * documentation, roughly a thousand files - and a thousand files is what a security
     * agent fights with, so the install died with "Failed to launch JVM" and nothing in the
     * log said why. Halving the download did not fix it.
     *
     * So ffmpeg ships again, as one executable beside the one yt-dlp it ships beside. This
     * asserts the distinction, because it is the whole difficulty: a future tidy-up that
     * "just extracts the whole ffmpeg folder" would pass every other test here and break
     * installs on exactly the machines that already broke once.
     */
    @Test
    fun ffmpegShipsAsOneFileRatherThanAnExtractedTree() {
        val tools = toolsJar()
        org.junit.Assume.assumeTrue("no packaged app image to inspect", tools != null)

        class Shipped(val name: String, val length: Long)
        val ffmpegFiles = java.util.zip.ZipFile(tools!!).use { zip ->
            zip.entries().toList()
                .filter { !it.isDirectory && it.name.substringAfterLast('/').startsWith("ffmpeg") }
                .map { Shipped(it.name.substringAfterLast('/'), it.size) }
        }
        assertEquals(
            "exactly one ffmpeg artefact must be staged. The extracted tree is ~1000 " +
                "files, and a thousand files is what broke the install with " +
                "'Failed to launch JVM' on a machine whose security agent locks runtime " +
                "files while they are written. Found: " +
                ffmpegFiles.joinToString { it.name },
            1,
            ffmpegFiles.size
        )
        assertEquals(
            "the staged artefact must be the executable, not the archive it came from",
            "ffmpeg.exe",
            ffmpegFiles.single().name
        )
        // And it must be the real thing, not a stub that would fail at the first mux.
        assertTrue(
            "the shipped ffmpeg.exe is only ${ffmpegFiles.single().length} bytes",
            ffmpegFiles.single().length > 50_000_000L
        )
    }

    /**
     * Keeps a large *dependency* from creeping back in.
     *
     * This is not about ffmpeg, which is now expected and accounted for. It is about the
     * 36 MB material-icons jar that used to ship, and about any other library large enough
     * to be worth noticing: ffmpeg is measured and subtracted, so what is left is the app's
     * own code and its dependencies, which should stay small.
     */
    @Test
    fun theAppJarStaysSmallEnoughToUnpackCleanly() {
        val jar = desktopJar()
        org.junit.Assume.assumeTrue("no packaged app image to inspect", jar != null)
        val ffmpeg = java.util.zip.ZipFile(jar!!).use { zip ->
            zip.entries().toList().firstOrNull { it.name == "lib/ffmpeg.exe" }?.size ?: 0L
        }
        val withoutFfmpeg = (jar.length() - ffmpeg) / (1024.0 * 1024.0)
        assertTrue(
            "the app jar is ${"%.0f".format(withoutFfmpeg)} MB without ffmpeg; anything " +
                "near the old 36 MB material-icons jar means a large dependency crept " +
                "back in",
            withoutFfmpeg < 30.0
        )
    }

    /**
     * The one line everything else depends on.
     *
     * ffmpeg being inside the jar is worth nothing on its own; it has to come back out onto
     * disk as an executable yt-dlp can run. That extraction is a stream copy out of a zip,
     * it is easy to break, and it breaks silently: a missing resource makes `install()`
     * return without throwing, the app comes up fine, and every YouTube download fails later
     * at the merge with a message about a tool the user was never told was missing.
     *
     * So this actually extracts it, into a temporary folder, and checks the result is a real
     * executable rather than a file that exists.
     */
    @Test
    fun theShippedFfmpegUnpacksToDiskOnFirstRun() {
        val dir = File.createTempFile("dlm-unpack-test", "").let {
            it.delete()
            File(it, "tools").apply { parentFile.mkdirs(); mkdirs() }
        }
        try {
            val tools = YtDlpTools(dir)
            tools.install()
            assertTrue(
                "the shipped ffmpeg.exe did not come back out of the jar",
                tools.ffmpeg.isFile
            )
            assertTrue(
                "the unpacked ffmpeg.exe is only ${tools.ffmpeg.length()} bytes, which " +
                    "means the resource was missing or empty rather than the executable",
                tools.ffmpeg.length() > 50_000_000L
            )
            // First two bytes of a PE executable. A zip of source, or an error page saved
            // under the right name, would not start with these.
            tools.ffmpeg.inputStream().use { input ->
                val magic = ByteArray(2)
                assertEquals(2, input.read(magic))
                assertEquals(
                    "the unpacked file is not a Windows executable",
                    'M'.code.toByte(),
                    magic[0]
                )
                assertEquals('Z'.code.toByte(), magic[1])
            }
        } finally {
            dir.parentFile.deleteRecursively()
        }
    }
}
