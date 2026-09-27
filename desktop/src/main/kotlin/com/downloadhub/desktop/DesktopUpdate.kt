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
    val assets: List<GithubAsset> = emptyList()
) {
    val version: String get() = tag.removePrefix("v")
    val displayName: String get() = name.ifBlank { "Version $version" }

    /**
     * The Windows installer from this release.
     *
     * A release also carries the Android APK; offering that to a desktop user would
     * install the wrong thing, so the .msi is matched explicitly.
     */
    fun installer(): GithubAsset? = assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
}

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L
)

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
    private val endpoint: String = "https://api.github.com/repos/$REPOSITORY/releases/latest",
    private val userAgent: String = "1-download-manager/$APP_VERSION (Windows)"
) {
    suspend fun latest(): GithubRelease? = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }
                .decodeFromString<GithubRelease>(body)
                .takeIf { it.version.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val REPOSITORY = "RDK456/1-download-manager"
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
 * Downloads an installer and hands it to Windows Installer.
 *
 * Launching the .msi directly is what makes upgrades work: the installer knows the
 * product code and upgrade code from the MSI itself, so it replaces the installed
 * copy in place and keeps the settings, which live in the user profile.
 */
class UpdateInstaller(private val directory: File = File(System.getProperty("java.io.tmpdir"), "dlm-update")) {

    /**
     * Streams the installer to disk, reporting percent.
     *
     * Written to a .part file and only renamed once the whole body has arrived, so
     * an interrupted download can never leave a truncated installer that Windows
     * would try to run.
     */
    suspend fun download(
        url: String,
        onProgress: (Int, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!directory.isDirectory) directory.mkdirs()
            val target = File(directory, "1DownloadManager-setup.msi")
            val part = File(directory, "1DownloadManager-setup.msi.part")
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
     * Starts the installer.
     *
     * Windows Installer prompts for elevation itself, so the app does not try to.
     * A failure here is reported rather than swallowed: a user who sees nothing
     * happen will assume the update failed.
     */
    fun launch(msi: File): Result<Unit> = runCatching {
        if (!msi.isFile) error("The installer file is missing")
        ProcessBuilder(msi.absolutePath)
            .redirectErrorStream(true)
            .start()
        Unit
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 300L
    }
}
