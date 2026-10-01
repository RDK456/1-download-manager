package com.downloadhub.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The extension had to be loadable by hand and could not be given to anyone.
 *
 * Neither store accepts a manifest with no icons array, so the extension could
 * never have been submitted however it was packaged, and it had no download of its
 * own - the only copy was a folder inside the installation, at a path only the
 * machine that installed it knows.
 */
class BrowserExtensionPackagingTest {

    private fun folder(name: String) = File("browser-extension/$name")

    private fun manifest(name: String) = File(folder(name), "manifest.json")

    private fun build(dir: String) =
        listOf(File(dir), File("../desktop/$dir")).firstOrNull { it.isDirectory }

    @Test
    fun bothBuildsExist() {
        listOf("chromium", "firefox").forEach { build ->
            assertTrue("browser-extension/$build is missing", folder(build).isDirectory)
            assertTrue("browser-extension/$build/manifest.json is missing", manifest(build).isFile)
        }
    }

    /**
     * The icons are drawn by the app's own icon generator from the same
     * coordinates, so this is a third surface showing one drawing rather than a
     * third drawing that happens to look like it.
     */
    @Test
    fun theIconsAreTheAppsOwnDrawing() {
        val generator = File("dist-tools/Generate-AppIcon.ps1").readText()
        assertTrue(
            "the generator must be able to write the extension icons, or they are " +
                "drawn from somewhere else and can drift",
            generator.contains("PngOutDir")
        )
        listOf("chromium", "firefox").forEach { build ->
            listOf(16, 32, 48, 128).forEach { size ->
                val icon = File(folder(build), "icon$size.png")
                assertTrue("browser-extension/$build/icon$size.png is missing", icon.isFile)
                assertTrue(
                    "browser-extension/$build/icon$size.png is ${icon.length()} bytes, " +
                        "which is not an icon",
                    icon.length() > 100
                )
            }
        }
    }

    /** Both stores reject a manifest with no icons, and neither renders it well. */
    @Test
    fun bothManifestsDeclareTheIcons() {
        listOf("chromium", "firefox").forEach { build ->
            val text = manifest(build).readText()
            assertTrue(
                "browser-extension/$build/manifest.json declares no icons, and both " +
                    "stores reject an upload without them:\n$text",
                text.contains("\"icons\"") && text.contains("icon128.png")
            )
            assertTrue(
                "browser-extension/$build does not set default_icon, so the toolbar " +
                    "shows a blank square",
                text.contains("default_icon")
            )
        }
    }

    /**
     * MV3 still has no Firefox service worker, so the two builds are genuinely
     * different extensions. Shipping one zip for both would give half the users an
     * extension that silently does nothing.
     */
    @Test
    fun theTwoBuildsKeepTheirOwnBackgroundStyle() {
        val chrome = manifest("chromium").readText()
        val firefox = manifest("firefox").readText()
        assertTrue("the Chromium build must use a service worker", chrome.contains("service_worker"))
        assertTrue("the Firefox build must use background scripts", firefox.contains("\"scripts\""))
    }

    /** The zips exist, one per browser, with the manifest at the root as stores require. */
    @Test
    fun bothZipsAreBuiltWithTheManifestAtTheRoot() {
        val dir = build("build/extensions")
        org.junit.Assume.assumeTrue(
            "no packaged extension to inspect; run :desktop:packageExtension",
            dir != null
        )
        val zips = dir!!.listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
        listOf("chrome", "firefox").forEach { label ->
            val zip = zips.firstOrNull { it.name.contains(label) }
            assertTrue("no extension zip for $label", zip != null)
            val names = java.util.zip.ZipFile(zip!!).use { z ->
                z.entries().toList().map { it.name }
            }
            assertTrue(
                "the $label zip must have manifest.json at its root, which is where " +
                    "both stores look for it",
                names.contains("manifest.json")
            )
            assertTrue(
                "the $label zip is missing its icons",
                names.contains("icon128.png")
            )
        }
    }
}
