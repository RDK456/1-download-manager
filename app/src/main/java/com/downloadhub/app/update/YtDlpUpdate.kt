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

/**
 * Outcome of comparing the installed yt-dlp with the newest stable release.
 *
 * The update installs itself, so there is no "apply" action in the steady state.
 * The row is read-only unless a check failed (which offers a retry) or an install
 * did not take (which offers a retry), which is what keeps it greyed out the rest
 * of the time instead of offering a button that does nothing.
 */
sealed interface YtDlpUpdateState {
    data object Idle : YtDlpUpdateState
    data object Checking : YtDlpUpdateState

    /** A newer release is being installed right now. */
    data class Updating(val installed: String, val latest: String) : YtDlpUpdateState

    data class UpToDate(val installed: String) : YtDlpUpdateState

    /** An update exists but the install did not complete; retrying is worthwhile. */
    data class Available(val installed: String, val latest: String) : YtDlpUpdateState

    data class Failed(val message: String) : YtDlpUpdateState

    /** True while the state is still settling, so the row shows a spinner. */
    val isBusy: Boolean get() = this is Checking || this is Updating

    /**
     * True when the app will do something further without a fresh check. A healthy
     * install needs no action, so the row stays read-only.
     */
    val needsAction: Boolean get() = this is Available || this is Failed

    /** True only when an update exists and is not being installed already. */
    val hasUpdate: Boolean get() = this is Available
}
