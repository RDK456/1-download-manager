package com.downloadhub.app.download

import com.downloadhub.app.ui.expressive.model.Probe
import com.downloadhub.core.HttpRequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * A quick look at a link before it is queued: how big it is, what the server says it is,
 * and whether it can be resumed. It is advisory, so every failure is just `null` and the
 * user can still add the link. Only http and https are looked at.
 */
object LinkProbe {
    private const val TIMEOUT_MS = 6_000
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun probe(url: String, request: HttpRequestOptions = HttpRequestOptions()): Probe? = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) return@withContext null
        runCatching {
            // HEAD first; many servers refuse it, so fall back to asking for one byte.
            var result = once(target, request, head = true)
            if (result.code == 403 || result.code == 405 || result.code == 501 || result.code == 400) {
                result = once(target, request, head = false)
            }
            result
        }.getOrNull()
    }

    private fun once(url: String, request: HttpRequestOptions, head: Boolean): Probe {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (head) "HEAD" else "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", BROWSER)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (!head) connection.setRequestProperty("Range", "bytes=0-0")
            request.applyTo(connection)
            val code = connection.responseCode
            val range = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val length = range ?: connection.contentLengthLong
            return Probe(
                code = code,
                contentLength = length,
                contentType = connection.contentType,
                acceptsRanges = code == 206 || connection.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true,
                finalUrl = connection.url?.toString()
            )
        } finally {
            connection.disconnect()
        }
    }
}
