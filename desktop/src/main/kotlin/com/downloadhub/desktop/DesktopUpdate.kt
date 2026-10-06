package com.downloadhub.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A GitHub release, as far as the updater cares. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("name") val name: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    /** The release notes, so the dialog can say what is actually changing. */
    val body: String = "",
    val assets: List<GithubAsset> = emptyList()
) {
    val version: String get() = tag.removePrefix("v")
    val displayName: String get() = name.ifBlank { "Version $version" }

    /**
     * The notes, trimmed to something a dialog can hold.
     *
     * Release notes are written as Markdown, and a dialog is not a Markdown
     * renderer. Only the markers are removed - headings, bullets, bold and code -
     * because showing them literally is worse than showing the words alone: the
     * first version of this left `**Resizing.**` and a row of backticks sitting in
     * the middle of the dialog.
     */
    val readableNotes: String
        get() = body
            .lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("###").removePrefix("##").removePrefix("#")
                    .trim()
                    .removePrefix("- ").removePrefix("* ")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
            }
            .filter { it.isNotBlank() }
            .take(14)
            .joinToString("\n")
            .trim()

    /**
     * The Windows installer from this release.
     *
     * A release also carries the Android APK; offering that to a desktop user would
     * install the wrong thing, so the .msi is matched explicitly.
     */
    fun installer(): GithubAsset? = assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }

    /**
     * The portable zip from this release.
     *
     * Worth offering alongside the installer, because Windows Installer does fail on
     * some machines - a security agent that will not let the installer set file
     * security reports "Error: 5", and there is nothing to do about that from inside
     * the installer. The zip needs no installer, no elevation and no Windows
     * Installer at all, and runs from anywhere.
     */
    fun portableZip(): GithubAsset? =
        assets.firstOrNull { it.name.endsWith("-portable.zip", ignoreCase = true) }
            // Older releases named it differently; never the extension or delta zips.
            ?: assets.firstOrNull {
                it.name.endsWith(".zip", ignoreCase = true) &&
                    !it.name.contains("extension", ignoreCase = true) &&
                    !it.name.endsWith("-delta.zip", ignoreCase = true)
            }
}

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L
)

/**
 * What kind of update was fetched, which decides what can be done with it.
 *
 * This distinction is the whole reason the updater worked at all on a machine where
 * Windows Installer is blocked. The portable zip is not an installer and cannot be
 * run as one: handing it to ShellExecute opens a "how do you want to open this?"
 * prompt, or nothing at all, which is a dead end at the very last step.
 */
enum class UpdateKind {
    /** An .msi, which Windows Installer takes from here. */
    INSTALLER,

    /** A portable zip, which has to be unpacked and started. */
    PORTABLE,

    /** Only the changed files, staged in a folder, swapped in on restart. */
    DELTA
}

/** A fetched update, and what it is. */
data class DownloadedUpdate(val kind: UpdateKind, val file: File) {
    /** Installs on restart: the installer, or a delta, which installs the same way. */
    val isInstaller: Boolean get() = kind == UpdateKind.INSTALLER || kind == UpdateKind.DELTA
}

/** What the update check found. */
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class UpToDate(val version: String) : UpdateCheck
    data class Available(val release: GithubRelease, val asset: GithubAsset) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Finds a newer Windows build on GitHub.
 *
 * The endpoint is the public releases API, read-only, and the same one the release
 * script publishes to, so a check and the thing it offers can never disagree.
 */
class DesktopUpdateChecker(
    private val endpoint: String = "https://api.github.com/repos/$REPOSITORY/releases",
    private val userAgent: String = "1-download-manager/$APP_VERSION (Windows)"
) {
    /**
     * The newest published release, newest first.
     *
     * The list endpoint rather than `/releases/latest`, because `/latest` means "the
     * newest release of any kind" - and this project ships Android and Windows from
     * one repository. A phone-only 1.5.0 published after a Windows 1.4.12 made
     * `/latest` return something with no .msi in it, and the updater's answer was
     * "no Windows installer yet", forever, with no way forward until the next Windows
     * release. Looking at the list lets it step back to the newest one Windows can
     * actually install.
     */
    suspend fun releases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        val connection = (URL("$endpoint?per_page=$PAGE_SIZE").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", userAgent)
            }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }
                .decodeFromString<List<GithubRelease>>(body)
                .filter { it.version.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** The newest release Windows can actually install. */
    suspend fun latestForWindows(): GithubRelease? = pickInstallable(releases())

    /**
     * The newest release carrying a Windows installer.
     *
     * Separated from [releases] so the choice is testable: this is a decision about
     * which build to offer, and it used to be made implicitly by the API endpoint.
     * Drafts and prereleases are skipped, because neither is something to install.
     */
    fun pickInstallable(candidates: List<GithubRelease>): GithubRelease? =
        candidates
            .filter { !it.draft && !it.prerelease }
            .filter { it.installer() != null }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

    companion object {
        const val REPOSITORY = "RDK456/1-download-manager"
        private const val PAGE_SIZE = 30
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 10_000
    }
}

/**
 * Compares dotted versions numerically.
 *
 * Prerelease suffixes are ignored, so `1.4.0` is not treated as newer than
 * `1.3.9` just because of a string compare.
 */
fun isNewerVersion(candidate: String, current: String): Boolean =
    compareVersions(candidate, current) > 0

fun compareVersions(left: String, right: String): Int {
    fun parts(value: String) = value
        .trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    val a = parts(left)
    val b = parts(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val result = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
        if (result != 0) return result
    }
    return 0
}

/**
 * Downloads an update installer and hands it to Windows Installer.
 *
 * The staging directory is the app's own cache folder, not `java.io.tmpdir`. TEMP is
 * not reliably a local writable folder - it is often a network share or a second
 * volume - and an update that cannot be written fails with a message about a path the
 * user has never seen. The same folder keeps the installer out of the way of a
 * security product scanning the system temp directory.
 */
class UpdateInstaller(private val directory: File = AppPaths.updateDir) {

    /**
     * Streams the installer to disk, reporting percent.
     *
     * Written to a .part file and only renamed once the whole body has arrived, so
     * an interrupted download can never leave a truncated installer that Windows
     * would try to run.
     */
    suspend fun download(
        url: String,
        onProgress: (Int, Long, Long) -> Unit,
        fileName: String = "1DownloadManager-setup.msi"
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!directory.isDirectory) directory.mkdirs()
            // Named for what is being fetched, so a zip and an installer downloaded to
            // the same folder are not indistinguishable.
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "1DownloadManager-setup.msi" }
            val target = File(directory, safeName)
            val part = File(directory, "$safeName.part")
            part.delete()

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "1-download-manager")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("Download failed with HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong.coerceAtLeast(0L)
                var downloaded = 0L
                var lastPublished = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastPublished >= PROGRESS_INTERVAL_MILLIS) {
                                lastPublished = now
                                val percent = if (total > 0) {
                                    ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                onProgress(percent, downloaded, total)
                            }
                        }
                    }
                }
                if (part.length() <= 0L) error("The downloaded installer was empty")
                if (total > 0 && part.length() != total) error("The download was incomplete")
                target.delete()
                if (!part.renameTo(target)) error("Could not save the installer")
                target
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * Starts the installer through Windows Installer.
     *
     * An .msi is not an executable. ProcessBuilder uses CreateProcess, which fails
     * with error 193 ("%1 is not a valid Win32 application") on one - exactly what
     * users saw on "Install now". It has to go through msiexec, which also knows
     * the product and upgrade codes from the MSI itself, so it replaces the
     * installed copy in place and keeps the settings in the user profile.
     *
     * Windows Installer prompts for elevation itself, so the app does not try to.
     * A failure here is reported rather than swallowed: a user who sees nothing
     * happen will assume the update failed.
     */
    /**
     * Installs the update with no wizard, then starts the new version.
     *
     * The install cannot run while this copy is open - its files are in use - so a small
     * script does it after the app exits: it waits for this process, runs msiexec with no
     * UI (/qn; the MSI is a per-user install, so there is no elevation prompt to hide),
     * writes the result for the next start to report, and starts [relaunch] again. The
     * caller quits straight after this returns.
     *
     * [relaunch] is null when running from Gradle, where there is no installed exe.
     */
    fun launchSilently(msi: File, relaunch: File?): Result<Unit> = runCatching {
        if (!msi.isFile) error("The installer file is missing")
        if (!directory.isDirectory) directory.mkdirs()
        val log = File(directory, "install.log")
        resultFile.delete()
        val script = File(directory, "apply-update.ps1")
        fun quoted(file: File) = "'" + file.absolutePath.replace("'", "''") + "'"
        script.writeText(
            """
            |${'$'}ErrorActionPreference = 'Continue'
            |Wait-Process -Id ${ProcessHandle.current().pid()} -ErrorAction SilentlyContinue
            |${'$'}p = Start-Process msiexec.exe -ArgumentList '/i', ('"' + ${quoted(msi)} + '"'), '/qn', '/norestart', '/l*v', ('"' + ${quoted(log)} + '"') -Wait -PassThru
            |Set-Content -Path ${quoted(resultFile)} -Value ("" + ${'$'}p.ExitCode + '|' + ${quoted(msi)} + '|' + ${quoted(log)})
            |${relaunch?.let { "Start-Process -FilePath ${quoted(it)}" } ?: ""}
            |Remove-Item -LiteralPath ${'$'}MyInvocation.MyCommand.Path -ErrorAction SilentlyContinue
            """.trimMargin()
        )
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-File", script.absolutePath
        ).redirectErrorStream(true).start()
        Unit
    }

    /** Written by the install script: "<exit code>|<msi>|<log>". */
    val resultFile: File get() = File(directory, "last-update.txt")

    /**
     * What the last silent install did, read once and then forgotten. Null when no install
     * ran since the last start. msiexec's 0 is success and 3010 is success needing a reboot.
     */
    fun takeLastResult(): InstallResult? {
        val text = runCatching { resultFile.readText().trim() }.getOrNull() ?: return null
        resultFile.delete()
        val parts = text.split('|')
        val code = parts.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        return InstallResult(code, parts.getOrNull(2)?.trim().orEmpty())
    }

    data class InstallResult(val exitCode: Int, val logPath: String) {
        val succeeded: Boolean get() = exitCode == 0 || exitCode == 3010
    }

    companion object {
        private const val PROGRESS_INTERVAL_MILLIS = 300L

        /** This process's own exe when it is the installed launcher; null under Gradle. */
        fun installedLauncher(): File? = ProcessHandle.current().info().command().orElse(null)
            ?.let(::File)
            ?.takeIf { it.isFile && !it.name.equals("java.exe", true) && !it.name.equals("javaw.exe", true) }
    }
}

/**
 * Unpacks a portable build and finds the launcher inside it.
 *
 * Its own thing rather than part of [UpdateInstaller], because it has nothing to do
 * with downloading: the downloader writes to a staging folder, this reads an archive
 * and writes somewhere else entirely.
 *
 * It exists because the portable path had no last step. The zip downloaded fine and
 * then the only thing on offer was "Install now", which handed a .zip to the shell -
 * so on a machine where Windows Installer is blocked, which is the only reason the
 * zip is offered at all, the update could not be completed.
 */
object PortableBuild {

    const val LAUNCHER_NAME = "1DownloadManager.exe"

    /**
     * Unpacks [zip] into [into] and returns its launcher.
     *
     * Beside the old copy rather than over it: Windows will not let a running .exe be
     * replaced, and the launcher executing right now is the one that would have to be
     * overwritten. A separate folder also leaves the previous version intact, so a new
     * build that will not start is one click from being undone.
     */
    fun extract(zip: File, into: File): Result<File> = runCatching {
        if (!zip.isFile) error("The downloaded archive is missing")
        val root = into.absoluteFile
        if (root.exists()) root.deleteRecursively()
        if (!root.mkdirs()) error("Could not create ${root.absolutePath}")

        val rootPath = root.canonicalPath
        var files = 0
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                // An entry's name is data from the internet, not a path the app may
                // write to. Anything resolving outside the destination is refused.
                val target = File(root, entry.name)
                val resolved = target.canonicalPath
                if (resolved != rootPath && !resolved.startsWith(rootPath + File.separator)) {
                    error("The archive contains a path outside the destination: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyTo(output) }
                    files++
                }
                input.closeEntry()
            }
        }
        if (files == 0) error("The archive was empty")

        findLauncher(root) ?: error("The archive did not contain $LAUNCHER_NAME")
    }

    /**
     * The app's launcher inside an unpacked portable build.
     *
     * The zip may or may not have a top-level folder in it, so this looks rather than
     * assuming a layout.
     */
    fun findLauncher(root: File): File? =
        root.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals(LAUNCHER_NAME, ignoreCase = true) }
}
