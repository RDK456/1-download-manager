package com.downloadhub.desktop

import java.io.File

/**
 * Makes Windows hand magnet links and .torrent files to this app.
 *
 * Without this, clicking a magnet link in a browser and double-clicking a .torrent in
 * Explorer have nowhere to go. The browser either starts the app with nothing to look
 * at, or hands the link to whatever else claims `magnet:`. That is the whole of "magnet
 * links from the browser do not open" and "clicking a .torrent does nothing" - the
 * app had no way to be told, however good the queue was afterwards.
 *
 * Registered here, at run time, per user, rather than by the installer, for two reasons:
 *
 * - **The portable zip has no installer.** It is the copy that works on the machines
 *   where Windows Installer cannot run, and a registration only an MSI can make would
 *   leave those users exactly where they were.
 * - **Per-user keys need no elevation.** `HKEY_CURRENT_USER\Software\Classes` is
 *   enough to own a protocol and an extension for this user, so this never asks for
 *   administrator rights and never shows a UAC prompt on startup.
 *
 * Only written when missing or different. Windows rewrites the `UserChoice` key under
 * `Software\Classes` when a user picks a different default handler, and fighting that
 * on every launch would be both rude and pointless.
 */
object FileAssociations {

    const val LAUNCHER_NAME = "1DownloadManager.exe"

    /** The scheme browsers hand over for a magnet, and the one the app claims. */
    const val MAGNET_SCHEME = "magnet"

    const val TORRENT_EXTENSION = ".torrent"
    const val TORRENT_CONTENT_TYPE = "application/x-bittorrent"

    private const val CLASSES = "HKCU\\Software\\Classes"
    private const val TORRENT_PROG_ID = "1DownloadManager.Torrent"

    /**
     * How `reg query` marks the start of a value's type.
     *
     * Everything from here on is `REG_SZ` (or whatever) followed by the value itself,
     * separated by spaces. Used because the output has no tab to split on.
     */
    private const val REG_TYPE_MARKER = "REG_"

    /**
     * The packaged launcher this copy is running as.
     *
     * Null unless the running program really is the installed app. Run from Gradle, or
     * from `java.exe`, this must return null: registering that would point every magnet
     * link on the machine at a development JVM and break other apps' handling.
     */
    fun launcherExe(): File? =
        // command() is an Optional in Java, not a nullable String.
        runCatching { ProcessHandle.current().info().command().orElse(null) }
            .getOrNull()
            ?.let(::File)
            ?.takeIf { it.isFile && it.name.equals(LAUNCHER_NAME, ignoreCase = true) }
            ?.absoluteFile

    /** Whether a magnet link and a .torrent file would both reach this copy. */
    fun isRegistered(exe: File? = launcherExe()): Boolean =
        exe != null && commandFor(MAGNET_SCHEME) == expectedCommand(exe) &&
            commandFor(TORRENT_PROG_ID) == expectedCommand(exe) &&
            extensionPointsAt(TORRENT_EXTENSION)

    /**
     * Claims both, and returns whether it worked.
     *
     * A no-op returning true when the keys are already right, so this is safe to call
     * on every start.
     */
    fun register(exe: File? = launcherExe()): Boolean {
        val launcher = exe?.takeIf { it.isFile } ?: return false
        if (isRegistered(launcher)) return true

        val command = expectedCommand(launcher)
        val icon = "\"${launcher.absolutePath}\",0"

        // The protocol itself.
        regSet("$CLASSES\\$MAGNET_SCHEME", null, "URL:1DownloadManager")
        // Windows only treats a scheme as a protocol when this value is present and empty.
        regSet("$CLASSES\\$MAGNET_SCHEME", "URL Protocol", "")
        regSet("$CLASSES\\$MAGNET_SCHEME\\DefaultIcon", null, icon)
        regSet("$CLASSES\\$MAGNET_SCHEME\\shell\\open\\command", null, command)

        // The extension, through a handler name of its own.
        regSet("$CLASSES\\$TORRENT_PROG_ID", null, "BitTorrent metainfo file")
        regSet("$CLASSES\\$TORRENT_PROG_ID", "Content Type", TORRENT_CONTENT_TYPE)
        regSet("$CLASSES\\$TORRENT_PROG_ID\\DefaultIcon", null, icon)
        regSet("$CLASSES\\$TORRENT_PROG_ID\\shell\\open\\command", null, command)
        regSet("$CLASSES\\$TORRENT_EXTENSION", null, TORRENT_PROG_ID)
        regSet("$CLASSES\\$TORRENT_EXTENSION\\Content Type", null, TORRENT_CONTENT_TYPE)

        // Advertised as supported types too, which is what makes a freshly installed
        // app show up in the "Open with" list for a .torrent straight away.
        regSet("$CLASSES\\Applications\\$LAUNCHER_NAME\\supportedTypes", MAGNET_SCHEME, "REG_SZ")
        regSet("$CLASSES\\Applications\\$LAUNCHER_NAME\\supportedTypes", TORRENT_EXTENSION, "REG_SZ")

        return isRegistered(launcher)
    }

    /** Gives both back, so the app stops claiming things it is not running for. */
    fun unregister(): Boolean {
        listOf(
            "$CLASSES\\$MAGNET_SCHEME",
            "$CLASSES\\$TORRENT_PROG_ID",
            "$CLASSES\\$TORRENT_EXTENSION",
            "$CLASSES\\Applications\\$LAUNCHER_NAME"
        ).forEach { regDelete(it) }
        return !isRegistered()
    }

    /** What a user's registry says the app's open command is, for the Settings screen. */
    fun registeredCommand(): String? = commandFor(MAGNET_SCHEME)

    /** Whether a magnet link is claimed by anything at all, whoever by. */
    fun magnetIsClaimed(): Boolean = runCatching {
        regQuery("$CLASSES\\$MAGNET_SCHEME", "URL Protocol") != null
    }.getOrDefault(false)

    private fun expectedCommand(exe: File): String = "\"${exe.absolutePath}\" \"%1\""

    private fun extensionPointsAt(extension: String): Boolean =
        regQuery("$CLASSES$extension", null)?.equals(TORRENT_PROG_ID, ignoreCase = true) == true

    private fun commandFor(handler: String): String? =
        regQuery("$CLASSES\\$handler\\shell\\open\\command", null)

    /**
     * Writes one registry value.
     *
     * Quotes inside [data] are escaped on the way out. `reg.exe` parses its own `/d`
     * argument, so a value that begins with a quote - which every Windows open command
     * does, because it has to be `"app.exe" "%1"` - makes it fail with "Invalid
     * syntax". The result was not a slightly wrong command: the key was never written
     * at all, so Windows had nothing to launch. Clicking a magnet did nothing while the
     * app looked like it had registered itself, which is exactly what was reported.
     */
    internal fun regSet(key: String, name: String?, data: String, type: String = "REG_SZ"): Boolean {
        val args = mutableListOf("reg.exe", "add", key)
        if (name == null) args += "/ve" else args += listOf("/v", name)
        args += listOf("/t", type, "/d", data.replace("\"", "\\\""), "/f")
        return runCommand(args)
    }

    /** Removes one value, leaving the rest of the key alone. */
    internal fun regDeleteValue(key: String, name: String): Boolean =
        runCommand(mutableListOf("reg.exe", "delete", key, "/v", name, "/f"))

    private fun regDelete(key: String): Boolean =
        runCommand(mutableListOf("reg.exe", "delete", key, "/f"))

    /**
     * Reads one value, exactly as stored.
     *
     * `reg query` prints `    <name>    REG_SZ    <value>` - separated by runs of
     * spaces, **not** by tabs. Splitting on a tab, which this used to do, finds no data
     * line at all and returns null for every key: so [isRegistered] was always false, the
     * app rewrote its own registration on every launch, and the comparison meant to
     * confirm the work never ran.
     *
     * The value is then taken from after the type token and kept verbatim, quotes and
     * all, because a Windows open command legitimately begins and ends with one.
     */
    internal fun regQuery(key: String, name: String?): String? {
        val args = mutableListOf("reg.exe", "query", key)
        if (name != null) args += listOf("/v", name)
        val output = runCatching {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrNull() ?: return null
        if (output.contains("ERROR", ignoreCase = true)) return null

        val marker = output.indexOf(REG_TYPE_MARKER)
        if (marker < 0) return null
        return output.substring(marker)
            .replaceFirst(Regex("""^$REG_TYPE_MARKER\w+\s*"""), "")
            .trimEnd()
    }

    internal fun runCommand(args: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)
}
