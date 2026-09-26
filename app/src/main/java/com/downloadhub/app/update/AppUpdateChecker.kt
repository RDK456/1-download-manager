package com.downloadhub.app.update

import com.downloadhub.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads the newest published release straight from the public GitHub API.
 * No token is required, which keeps the updater usable on a fresh install.
 */
class AppUpdateChecker(
    private val repoSlug: String = "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}",
    private val userAgent: String = "1-download-manager/${BuildConfig.VERSION_NAME}"
) {
    suspend fun latestRelease(): ReleaseInfo = withContext(Dispatchers.IO) { fetchLatest() }

    private fun fetchLatest(): ReleaseInfo {
        val endpoint = URL("https://api.github.com/repos/$repoSlug/releases/latest")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (status !in 200..299) {
                error("GitHub returned HTTP $status")
            }
            return parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}

/** Parses a GitHub "release" payload. Kept separate so it can be unit tested. */
fun parseRelease(json: String): ReleaseInfo {
    val root = JSONObject(json)
    val assetsJson = root.optJSONArray("assets") ?: org.json.JSONArray()
    val assets = buildList {
        for (index in 0 until assetsJson.length()) {
            val asset = assetsJson.optJSONObject(index) ?: continue
            val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            add(
                ReleaseAsset(
                    name = asset.optString("name"),
                    downloadUrl = url,
                    size = asset.optLong("size", 0L),
                    contentType = asset.optString("content_type").takeIf { it.isNotBlank() }
                )
            )
        }
    }
    return ReleaseInfo(
        tag = root.optString("tag_name"),
        name = root.optString("name"),
        notes = root.optString("body"),
        pageUrl = root.optString("html_url"),
        publishedAt = root.optString("published_at"),
        assets = assets,
        prerelease = root.optBoolean("prerelease", false)
    )
}
