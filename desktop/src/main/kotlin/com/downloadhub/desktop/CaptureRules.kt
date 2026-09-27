package com.downloadhub.desktop

import java.net.URI
import java.util.Locale

/**
 * Decides what a request from the browser extension means.
 *
 * Kept apart from the socket handling so the rules can be tested without opening a
 * port, and so the security decisions are in one readable place.
 */
object CaptureRules {

    /** Only ever loopback: this must not be reachable from the network. */
    val ALLOWED_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    private val ALLOWED_SCHEMES = setOf("http", "https", "magnet")

    /**
     * True when a browser request should be accepted.
     *
     * The token check is what stops another program on the machine from queueing
     * downloads, and the host check is what stops a website from reaching this
     * server through DNS rebinding, where a page resolves its own attacker domain
     * to 127.0.0.1.
     */
    fun isAuthorised(hostHeader: String?, token: String?, expectedToken: String): Boolean {
        val host = hostHeader?.substringBefore(':')?.trim()?.lowercase(Locale.US)
        if (host == null || host !in ALLOWED_HOSTS) return false
        if (expectedToken.isBlank()) return false
        // Constant-time-ish comparison: a plain == on a shared secret leaks length
        // and prefix information through timing.
        val provided = token.orEmpty()
        if (provided.length != expectedToken.length) return false
        var difference = 0
        for (index in expectedToken.indices) {
            difference = difference or (provided[index].code xor expectedToken[index].code)
        }
        return difference == 0
    }

    /** Validates a submitted link, returning null when it is not something we can queue. */
    fun normaliseLink(raw: String?): String? {
        val link = raw?.trim().orEmpty()
        if (link.isEmpty() || link.length > MAX_LINK_LENGTH) return null
        val lower = link.lowercase(Locale.US)
        // A magnet has no authority component, so it is checked before parsing.
        if (lower.startsWith("magnet:")) return link.takeIf { isPlausibleMagnet(it) }
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) !in ALLOWED_SCHEMES) return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host.isBlank() || !PlausibleDomains.contains(host)) return null
        return link
    }

    private fun isPlausibleMagnet(link: String): Boolean =
        link.contains("xt=urn:btih:") && link.length <= MAX_LINK_LENGTH

    /**
     * Hosts the extension may hand over.
     *
     * It deliberately covers known media and file hosts plus a YouTube form, rather
     * than anything at all, so a compromised page cannot use the app as a relay for
     * arbitrary addresses.
     */
    private val PlausibleDomains = setOf(
        "youtube.com", "youtu.be", "www.youtube.com", "m.youtube.com",
        "vimeo.com", "www.vimeo.com",
        "github.com", "objects.githubusercontent.com", "raw.githubusercontent.com",
        "mediafire.com", "www.mediafire.com",
        "drive.google.com",
        "dropbox.com", "www.dropbox.com",
        "onedrive.live.com",
        "sourceforge.net", "downloads.sourceforge.net",
        "archive.org", "ia801504.us.archive.org",
        "mega.nz",
        "gutenberg.org", "www.gutenberg.org",
        "kernel.org", "cdn.kernel.org"
    )

    const val MAX_LINK_LENGTH = 4096
}
