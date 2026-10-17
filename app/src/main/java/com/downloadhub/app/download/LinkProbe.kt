package com.downloadhub.app.download

import com.downloadhub.app.ui.expressive.model.Probe
import com.downloadhub.app.ui.expressive.model.isLocalAddress
import com.downloadhub.app.ui.expressive.model.isLocalHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * A quick look at a link before it is queued: how big it is, what the server says it is,
 * and whether it can be resumed. Advisory only, so every failure is `null` and the user can
 * still add the link.
 *
 * It is deliberately modest about what it sends and where:
 * - Only http and https, and only to public addresses. The host is parsed (every spelling of
 *   an address, see `isLocalHost`) and, if it is a name, looked up first: if any address it
 *   answers with is local or private, nothing is sent. The same check runs on every hop.
 * - Redirects are followed by hand, a few hops, and a hop to a local address ends the look
 *   there, so a public address cannot bounce the phone onto its own network.
 * - It carries no cookies, headers or login the user typed. Those belong to the real
 *   download, and sending them in a background look could hand them to a host they were
 *   never meant for. A site that needs them answers 401/403, which is reported as such.
 * - The caller only starts it after the user has edited the link, never for one that
 *   arrived from a share or an intent.
 *
 * Known limit: the name is resolved once here and again when the connection opens, so a
 * server that changes its answer between the two (DNS rebinding) is not caught. The reply
 * is only ever shown as advice (a size and a type), nothing is read from the body, and no
 * credentials are sent, which keeps what such a server could learn or do small.
 */
object LinkProbe {
    private const val TIMEOUT_MS = 6_000
    private const val LOOKUP_TIMEOUT_MS = 4_000L
    private const val MAX_HOPS = 5
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    suspend fun probe(url: String): Probe? = withContext(Dispatchers.IO) {
        val first = url.trim()
        if (!isWeb(first) || !isPublic(first)) return@withContext null
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

    /** True when the host is not local by its text and every address it resolves to is public. */
    private suspend fun isPublic(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (isLocalHost(host)) return false
        val addresses = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
            runCatching { InetAddress.getAllByName(host) }.getOrNull()
        } ?: return false
        return addresses.isNotEmpty() && addresses.none { isLocalAddress(it.address) }
    }

    private suspend fun look(start: String, head: Boolean): Probe {
        var current = start
        repeat(MAX_HOPS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = if (head) "HEAD" else "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", BROWSER)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (!head) connection.setRequestProperty("Range", "bytes=0-0")
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")?.let { resolve(current, it) }
                    // No usable Location, or the next hop is not a public web address: stop here.
                    if (next == null || !isWeb(next) || !isPublic(next)) {
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
