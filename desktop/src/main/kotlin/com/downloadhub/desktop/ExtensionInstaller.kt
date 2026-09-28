package com.downloadhub.desktop

import java.io.File
import java.util.concurrent.Executors

/**
 * Makes the bundled browser extension available to the user.
 *
 * Chrome and Edge only accept an extension as an unpacked folder that the user
 * selects by hand, or from a store. There is no way to install one silently, and
 * offering a store listing would mean publishing it somewhere first, so the honest
 * path is: the app copies its bundled copy into the user's profile and opens that
 * folder in Explorer, leaving them with a folder to pick.
 */
class ExtensionInstaller(private val installDir: File = File(AppPaths.home, "browser-extension")) {

    private val files = listOf(
        "manifest.json",
        "background.js",
        "content.js",
        "popup.html",
        "popup.js"
    )

    /** Copies the extension out of the app. Safe to call on every launch. */
    fun install(): Boolean {
        if (installDir.isDirectory && files.all { File(installDir, it).isFile }) return true
        return runCatching {
            installDir.mkdirs()
            var copied = 0
            files.forEach { name ->
                val target = File(installDir, name)
                val stream = ExtensionInstaller::class.java.classLoader?.getResourceAsStream(
                    "browser-extension/$name"
                ) ?: locate(name)?.inputStream()
                stream ?: return@runCatching false
                val partial = File(installDir, "$name.part")
                stream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                if (partial.length() <= 0L) {
                    partial.delete()
                    return@runCatching false
                }
                target.delete()
                // A truncated script would fail silently in the browser, so only a
                // complete file is put in place.
                if (!partial.renameTo(target)) {
                    partial.delete()
                    return@runCatching false
                }
                copied++
            }
            // A stale manifest is worse than none: Chrome would refuse it and the
            // user would blame the app.
            copied == files.size
        }.getOrDefault(false)
    }

    val available: Boolean get() = files.all { File(installDir, it).isFile }

    /** Reveals the folder in Explorer, or copies the path as a fallback. */
    fun reveal(): Boolean = runCatching {
        install()
        val explorer = File("C:/Windows/explorer.exe")
        if (explorer.isFile) {
            ProcessBuilder(explorer.absolutePath, installDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            true
        } else {
            false
        }
    }.getOrDefault(false)

    /** The path to show the user, since they have to select it themselves. */
    fun pathForDisplay(): String = installDir.absolutePath

    /** Fallback for running from the project directory rather than a package. */
    private fun locate(name: String): File? {
        val candidates = listOf(
            File("browser-extension/$name"),
            File(File(System.getProperty("user.dir") ?: ".", "browser-extension"), name)
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0 }
    }
}
