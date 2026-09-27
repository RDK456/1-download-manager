package com.downloadhub.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PageScanState {
    data object Idle : PageScanState
    data object Scanning : PageScanState
    /** The pasted link already points at a media file; just queue it. */
    data class AlreadyMedia(val url: String) : PageScanState
    data class Found(val pageUrl: String, val items: List<MediaCandidate>) : PageScanState
    data class Failed(val message: String) : PageScanState
}

/**
 * Fetches a pasted page and looks for media to download. Never throws: every
 * failure becomes a [PageScanState.Failed] so the user can fall back to the
 * normal add flow.
 */
class PageScanner {
    suspend fun scan(url: String): PageScanState = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return@withContext PageScanState.Failed("Only http and https links can be scanned")
        }
        if (PageMediaParser.looksLikeDirectMedia(trimmed)) {
            return@withContext PageScanState.AlreadyMedia(trimmed)
        }

        val fetched = runCatching { fetch(trimmed) }.getOrElse { error ->
            return@withContext PageScanState.Failed(
                error.message?.takeIf { it.isNotBlank() } ?: "Could not open that page"
            )
        }

        if (fetched.contentType.startsWith("video/") || fetched.contentType.startsWith("audio/")) {
            return@withContext PageScanState.AlreadyMedia(fetched.finalUrl)
        }
        if (fetched.contentType.isNotEmpty() && !fetched.contentType.contains("html")) {
            // Not a page (a zip, an image, an APK...) - there is nothing to scan.
            return@withContext PageScanState.Failed("That link is not a web page")
        }

        val items = PageMediaParser.parse(fetched.html, fetched.finalUrl)
        if (items.isEmpty()) {
            PageScanState.Failed("No video, audio or files were found on that page")
        } else {
            PageScanState.Found(fetched.finalUrl, items)
        }
    }

    private data class FetchedPage(val finalUrl: String, val contentType: String, val html: String)

    /** Fetches the page, following a few redirects by hand so the final URL is known. */
    private fun fetch(url: String): FetchedPage {
        var currentUrl = url
        var hops = 0
        while (true) {
            val connection = open(currentUrl)
            try {
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                val next = if (status in 300..399 && hops < MAX_REDIRECTS && !location.isNullOrBlank()) {
                    resolveLocation(currentUrl, location)
                } else {
                    null
                }
                if (next != null) {
                    currentUrl = next
                    hops++
                    continue
                }
                if (status !in 200..299) error("The page returned HTTP $status")
                return FetchedPage(
                    finalUrl = currentUrl,
                    contentType = connection.contentType.orEmpty().lowercase(),
                    html = connection.inputStream.readUpTo(MAX_HTML_BYTES)
                )
            } finally {
                connection.disconnect()
            }
        }
    }
    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

    private fun resolveLocation(base: String, location: String): String? = runCatching {
        java.net.URI(base).resolve(location.trim()).toString()
    }.getOrNull()

    private fun InputStream.readUpTo(limit: Int): String =
        BufferedInputStream(this).bufferedReader().use { reader ->
            val buffer = CharArray(16 * 1024)
            val builder = StringBuilder()
            while (builder.length < limit) {
                val read = reader.read(buffer)
                if (read <= 0) break
                builder.appendRange(buffer, 0, read)
            }
            builder.toString()
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val MAX_HTML_BYTES = 3 * 1024 * 1024
        const val MAX_REDIRECTS = 5
    }
}
