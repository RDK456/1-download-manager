package com.downloadhub.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class BrowserFamily { CHROMIUM, GECKO }

/** A browser Windows has registered, and which build of the extension it takes. */
data class InstalledBrowser(val name: String, val exe: File, val family: BrowserFamily)

/**
 * Finds the installed browsers and hands each the extension as directly as it allows.
 *
 * No browser lets another program install an extension silently - that is a deliberate
 * defence against malware - so "directly" means: a Chromium browser is opened on its
 * Extensions page with the folder beside it (two clicks: Developer mode, drag the folder
 * in), and a Firefox-based one is handed the .xpi, which brings up its own install prompt.
 * Pairing then happens by itself.
 */
object BrowserInstall {
    private val ROOTS = listOf(
        "HKLM\\SOFTWARE\\Clients\\StartMenuInternet",
        "HKCU\\SOFTWARE\\Clients\\StartMenuInternet",
        "HKLM\\SOFTWARE\\WOW6432Node\\Clients\\StartMenuInternet"
    )

    private val CHROMIUM = setOf("chrome", "msedge", "brave", "vivaldi", "opera", "launcher", "chromium", "thorium", "yandex", "arc")
    private val GECKO = setOf("firefox", "zen", "librewolf", "waterfox", "floorp", "mullvadbrowser", "palemoon")

    /** The browsers Windows lists as installed, one per program, that take one of the two builds. */
    fun find(): List<InstalledBrowser> = ROOTS.flatMap { root ->
        val output = runCatching {
            val process = ProcessBuilder("reg", "query", root, "/s").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }.getOrDefault("")
        parse(output)
    }.distinctBy { it.exe.absolutePath.lowercase() }.filter { it.exe.isFile }

    /** Reads `reg query /s` output: each browser key's name, and its shell\open\command program. */
    internal fun parse(regOutput: String): List<InstalledBrowser> {
        val names = mutableMapOf<String, String>()
        val commands = mutableMapOf<String, String>()
        var key = ""
        regOutput.lines().forEach { raw ->
            val line = raw.trimEnd()
            if (line.startsWith("HKEY_", ignoreCase = true)) {
                key = line.trim()
                return@forEach
            }
            val value = Regex("""^\s+\(Default\)\s+REG_(?:EXPAND_)?SZ\s+(.*)$""").find(line)?.groupValues?.get(1) ?: return@forEach
            if (key.endsWith("\\shell\\open\\command", ignoreCase = true)) {
                commands[key.substringBeforeLast("\\shell\\open\\command")] = value
            } else {
                names[key] = value
            }
        }
        return commands.mapNotNull { (browserKey, command) ->
            val exe = command.trim().let { if (it.startsWith("\"")) it.drop(1).substringBefore('"') else it.substringBefore(' ') }
            val family = familyOf(exe) ?: return@mapNotNull null
            InstalledBrowser(names[browserKey]?.ifBlank { null } ?: browserKey.substringAfterLast('\\'), File(exe), family)
        }
    }

    internal fun familyOf(exe: String): BrowserFamily? {
        val name = exe.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.').lowercase()
        return when (name) {
            in CHROMIUM -> BrowserFamily.CHROMIUM
            in GECKO -> BrowserFamily.GECKO
            else -> null
        }
    }

    /**
     * Opens [browser] where the extension is added, with the extension ready for it.
     * [folderFor] gives the extracted build for a family ("chromium" or "firefox").
     */
    fun open(browser: InstalledBrowser, folderFor: (String) -> File?): Boolean = runCatching {
        when (browser.family) {
            BrowserFamily.CHROMIUM -> {
                val folder = folderFor("chromium") ?: return@runCatching false
                // The folder in Explorer, ready to drag onto the Extensions page.
                ProcessBuilder("explorer.exe", folder.absolutePath).start()
                ProcessBuilder(browser.exe.absolutePath, "chrome://extensions/").start()
            }
            BrowserFamily.GECKO -> {
                val folder = folderFor("firefox") ?: return@runCatching false
                val xpi = packXpi(folder, File(folder.parentFile, "1-download-manager.xpi"))
                // Handed an .xpi, a Firefox-based browser shows its own install prompt.
                ProcessBuilder(browser.exe.absolutePath, xpi.absolutePath).start()
            }
        }
        true
    }.getOrDefault(false)

    /** Zips an extension folder into an .xpi, manifest at the root as Firefox requires. */
    internal fun packXpi(folder: File, target: File): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            folder.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }
}
