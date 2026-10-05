package com.downloadhub.desktop

import java.io.File

/**
 * Makes the bundled browser extensions available to the user.
 *
 * Chrome and Edge only accept an extension as an unpacked folder the user selects by
 * hand, or from a store. There is no way to install one silently, and offering a
 * store listing would mean publishing it somewhere first, so the honest path is: the
 * app copies its bundled copies into the user's profile and opens the folder in
 * Explorer, leaving them with a folder to pick.
 *
 * Two builds ship, because one build cannot serve both browser families. Firefox has
 * no `chrome.*` API at all and rejects a Manifest V3 service worker, so it needs
 * `browser.*` and `background.scripts` instead. Chrome, Edge and Opera share the
 * Chromium build.
 */
class ExtensionInstaller(private val installDir: File = File(AppPaths.home, "browser-extension")) {

    /** The extension files, per browser family. */
    private val builds = mapOf(
        "chromium" to listOf("manifest.json", "background.js", "content.js", "popup.html", "popup.js"),
        "firefox" to listOf("manifest.json", "background.js", "content.js", "popup.html", "popup.js")
    )

    private fun filesFor(build: String): List<String> = builds[build].orEmpty()

    /** Copies every extension out of the app. Safe to call on every launch. */
    fun install(): Boolean {
        // A previously installed single build left files at the root; those would
        // otherwise be picked up as a broken third variant.
        filesFor("chromium").forEach { name -> File(installDir, name).delete() }
        return builds.keys.all { installBuild(it) }
    }

    /** Copies one variant out, only overwriting a copy that is already complete. */
    private fun installBuild(build: String): Boolean {
        val names = filesFor(build)
        val target = File(installDir, build)
        // Complete and the same version as the one bundled: nothing to do. A different
        // manifest means the app was updated, and an old extension would keep running
        // without the new features until the files are replaced.
        val bundledManifest = ExtensionInstaller::class.java.classLoader
            ?.getResourceAsStream("browser-extension/$build/manifest.json")?.use { it.readBytes() }
        val installedManifest = File(target, "manifest.json").takeIf { it.isFile }?.readBytes()
        if (target.isDirectory && names.all { File(target, it).isFile } &&
            bundledManifest != null && bundledManifest.contentEquals(installedManifest)
        ) return true
        return runCatching {
            target.mkdirs()
            var copied = 0
            names.forEach { name ->
                val partial = File(target, "$name.part")
                val stream = ExtensionInstaller::class.java.classLoader
                    ?.getResourceAsStream("browser-extension/$build/$name")
                if (stream == null) {
                    partial.delete()
                    return@runCatching false
                }
                stream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                // A truncated script fails silently in the browser and the user blames
                // the app, so only a complete file is put in place.
                if (partial.length() <= 0L) {
                    partial.delete()
                    return@runCatching false
                }
                val destination = File(target, name)
                destination.delete()
                if (!partial.renameTo(destination)) {
                    partial.delete()
                    return@runCatching false
                }
                copied++
            }
            // A stale manifest is worse than none: the browser would refuse it.
            copied == names.size
        }.getOrDefault(false)
    }

    val available: Boolean get() = builds.keys.all { build ->
        val names = filesFor(build)
        File(installDir, build).isDirectory && names.all { File(File(installDir, build), it).isFile }
    }

    /** Reveals the folder for a browser family in Explorer. */
    fun reveal(build: String = "chromium"): Boolean = runCatching {
        if (!installBuild(build)) return@runCatching false
        val explorer = File("C:/Windows/explorer.exe")
        if (explorer.isFile) {
            ProcessBuilder(explorer.absolutePath, File(installDir, build).absolutePath)
                .redirectErrorStream(true)
                .start()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    /** The up-to-date extracted build for a browser family, or null if it could not be written. */
    fun folder(build: String): File? = if (installBuild(build)) File(installDir, build) else null

    /** The path to show the user, since they have to select it themselves. */
    fun pathForDisplay(): String = installDir.absolutePath
}
