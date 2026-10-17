package com.downloadhub.app.ui.expressive.model

import java.net.URI

/**
 * True when [host] is a name or literal address that only means something on the phone's own
 * network: loopback, private ranges, link-local, and the usual local-only names.
 *
 * The add sheet looks at a link over the network before it is queued. That look is skipped
 * for these hosts (and for any redirect that lands on one), so pasting or sharing a link
 * cannot make the app quietly poke at the router, a device on the Wi-Fi, or itself. Adding
 * such a link still works: the user may genuinely be downloading from a NAS. Only the
 * automatic look is withheld. Names are judged by their text, never by resolving them, since
 * resolving is itself a request.
 */
fun isLocalHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return true
    val h = host.trim().trim('[', ']').lowercase().trimEnd('.')
    if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") ||
        h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".intranet") || h.endsWith(".corp")
    ) return true
    // A name with no dot at all ("router", "nas") is a local name.
    if (!h.contains('.') && !h.contains(':')) return true
    if (h.contains(':')) return isLocalV6(h)
    val parts = h.split('.')
    if (parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 3 }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }
    // Decimal, octal and hex spellings of an address (2130706433, 0x7f000001) hide a literal.
    if (h.all(Char::isDigit) || h.startsWith("0x")) return true
    return false
}

private fun isLocalV6(h: String): Boolean {
    if (h == "::1" || h == "::") return true
    if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
    if (h.startsWith("::ffff:")) return isLocalHost(h.removePrefix("::ffff:"))
    return false
}

/** [isLocalHost] for a whole address. An address that cannot be read counts as local. */
fun isLocalUrl(url: String): Boolean = isLocalHost(runCatching { URI(url.trim()).host }.getOrNull())
