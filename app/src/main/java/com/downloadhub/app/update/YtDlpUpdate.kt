package com.downloadhub.app.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * yt-dlp versions are date based (for example `2026.08.19`), and the Android
 * wrapper's STABLE channel reads the newest release of `yt-dlp/yt-dlp`. This
 * checker queries that same endpoint, so a "check" and an "update" can never
 * disagree about what the latest version is.
 */
class YtDlpUpdateChecker(
    private val endpoint: String = YTDLP_STABLE_RELEASES,
    private val userAgent: String = "1-download-manager/yt-dlp-check"
) {
    /** Newest stable yt-dlp version, or null when it cannot be determined. */
    suspend fun latestStableVersion(): String? = withContext(Dispatchers.IO) {
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
            val tag = JSONObject(body).optString("tag_name").trim()
            tag.removePrefix("v").ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}

const val YTDLP_STABLE_RELEASES = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"

/** Outcome of comparing the installed yt-dlp with the newest stable release. */
sealed interface YtDlpUpdateState {
    data object Idle : YtDlpUpdateState
    data object Checking : YtDlpUpdateState
    data class UpToDate(val installed: String) : YtDlpUpdateState
    data class Available(val installed: String, val latest: String) : YtDlpUpdateState
    data class Failed(val message: String) : YtDlpUpdateState

    /** True only when an update can actually be installed right now. */
    val hasUpdate: Boolean get() = this is Available
}
