package com.downloadhub.app.download

import com.downloadhub.app.ui.expressive.model.Probe
import com.downloadhub.app.ui.expressive.model.isLocalUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A quick look at a link before it is queued: how big it is, what the server says it is,
 * and whether it can be resumed. Advisory only, so every failure is `null` and the user can
 * still add the link.
 *
 * It is deliberately modest about what it sends and where:
 * - Only http and https, and never to a local or private address (see `isLocalHost`).
 * - Redirects are followed by hand, a few hops, and a hop to a local address stops the look
 *   there, so a public address cannot bounce the phone onto its own network.
 * - It carries no cookies, headers or login the user typed. Those belong to the real
 *   download, and sending them in a background look could hand them to a host they were
 *   never meant for. A site that needs them answers 401/403, which is reported as such.
 * - The caller only starts it after the user has edited the link, never for one that
 *   arrived from a share or an intent.
 */
object LinkProbe {
    private const val TIMEOUT_MS = 6_000
    private const val MAX_HOPS = 5
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun probe(url: String): Probe? = withContext(Dispatchers.IO) {
        val first = url.trim()
        if (!isWeb(first) || isLocalUrl(first)) return@withContext null
        runCatching {
            // HEAD first; many servers refuse it, so fall back to asking for one byte.
            var result = look(first, head = true)
            if (result.code == 403 || result.code == 405 || result.code == 501 || result.code == 400) {
                result = look(first, head = false)
            }
            result
        }.getOrNull()
    }

    private fun isWeb(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)

    private fun look(start: String, head: Boolean): Probe {
        var current = start
        repeat(MAX_HOPS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = if (head) "HEAD" else "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", BROWSER)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (!head) connection.setRequestProperty("Range", "bytes=0-0")
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")?.let { resolve(current, it) }
                    // No usable Location, or the next hop is not a public web address: stop here.
                    if (next == null || !isWeb(next) || isLocalUrl(next)) {
                        return Probe(code, -1, null, false, next ?: current)
                    }
                    current = next
                    return@repeat
                }
                val range = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                return Probe(
                    code = code,
                    contentLength = range ?: connection.contentLengthLong,
                    contentType = connection.contentType,
                    acceptsRanges = code == 206 || connection.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true,
                    finalUrl = current
                )
            } finally {
                connection.disconnect()
            }
        }
        return Probe(310, -1, null, false, current)
    }

    private fun resolve(base: String, location: String): String? =
        runCatching { URI(base).resolve(location.trim()).toString() }.getOrNull()
}
