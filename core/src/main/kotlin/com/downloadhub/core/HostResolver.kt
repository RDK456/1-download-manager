package com.downloadhub.core

import java.io.IOException
import java.net.CookieManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a file-host page link into the direct file URL, the way JDownloader's host plugins do.
 *
 * `fuckingfast.co/<id>` is a page, not the file: its DOWNLOAD button POSTs `/f/<id>/go` and
 * the reply's `HX-Redirect` header is the real, resumable URL on `dl.fuckingfast.co`. Any
 * other link comes back unchanged. The result is cached briefly because the downloader asks
 * once per connection and every ask would otherwise be a fresh page load and POST.
 */
object HostResolver {
    private val fastFile = Regex("""^https?://(?:www\.)?fuckingfast\.co/([A-Za-z0-9]+)(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val sitePages = setOf("speedtest", "help", "login", "privacy", "terms", "contact")
    private const val CACHE_MILLIS = 5 * 60_000L
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** Direct URLs the user obtained in a real browser after passing the host's check; kept until the app exits. */
    private val provided = ConcurrentHashMap<String, String>()

    fun needsResolving(url: String): Boolean = fileId(url) != null

    /** Records the direct URL a browser got for the page link [url]. */
    fun provide(url: String, direct: String) {
        fileId(url)?.let { provided[it] = direct }
    }

    /** The direct URL for [url], or [url] itself when it is not a page this knows. Throws [IOException] when the host will not give one. */
    fun resolve(url: String, proxy: Proxy? = null): String {
        val id = fileId(url) ?: return url
        provided[id]?.let { return it }
        cache[id]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MILLIS }?.let { return it.second }
        val direct = fetchFastFile(id, proxy)
        cache[id] = System.currentTimeMillis() to direct
        return direct
    }

    private fun fileId(url: String): String? =
        fastFile.find(url.trim())?.groupValues?.get(1)?.takeIf { it.lowercase() !in sitePages }

    private fun fetchFastFile(id: String, proxy: Proxy?): String {
        val client = HttpClient.newBuilder()
            .cookieHandler(CookieManager())
            .connectTimeout(Duration.ofSeconds(20))
            .apply { (proxy?.address() as? InetSocketAddress)?.let { proxy(ProxySelector.of(it)) } }
            .build()
        val page = "https://fuckingfast.co/$id"
        // The page visit first: the host sets cookies there that the POST expects.
        client.send(
            HttpRequest.newBuilder(URI.create(page)).header("User-Agent", USER_AGENT).build(),
            HttpResponse.BodyHandlers.discarding()
        )
        val reply = client.send(
            HttpRequest.newBuilder(URI.create("https://fuckingfast.co/f/$id/go"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .header("HX-Request", "true")
                .header("Origin", "https://fuckingfast.co")
                .header("Referer", page)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        return reply.headers().firstValue("hx-redirect").orElse(null)
            ?: throw IOException("fuckingfast.co gave no download link (${reply.statusCode()}): ${reply.body().take(120)}")
    }
}
