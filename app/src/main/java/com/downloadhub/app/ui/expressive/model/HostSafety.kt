package com.downloadhub.app.ui.expressive.model

import java.net.URI

/**
 * Decides whether an address is one that only means something on the phone's own network, so
 * the add sheet's automatic look at a link (`LinkProbe`) can leave it alone.
 *
 * That look is skipped for these addresses, and for any redirect or DNS answer that lands on
 * one, so pasting a link cannot make the app quietly touch the router, a device on the Wi-Fi,
 * or itself. Adding such a link still works: the user may really be downloading from a NAS.
 * Only the automatic look is withheld.
 *
 * Addresses are parsed, not pattern-matched: an address can be written many ways (`127.1`,
 * `0177.0.0.1`, `0x7f000001`, `2130706433`, `0:0:0:0:0:0:0:1`, `::ffff:7f00:1`) and matching
 * text misses some of them. Everything here is pure and does no lookups.
 */

/** The bytes of an address written as a literal (4 for IPv4, 16 for IPv6), or null if [host] is a name. */
fun parseIpLiteral(host: String): ByteArray? {
    val h = host.trim().trim('[', ']').substringBefore('%')
    if (h.isEmpty()) return null
    return if (':' in h) parseV6(h) else parseV4(h)
}

/** C `inet_aton`: one to four parts, each decimal, octal (leading 0) or hex (0x), the last filling what is left. */
private fun parseV4(h: String): ByteArray? {
    val parts = h.split('.')
    if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
    val values = parts.map { part ->
        val n = when {
            part.startsWith("0x", true) -> part.substring(2).takeIf { it.isNotEmpty() && it.all(::isHex) }?.toLongOrNull(16)
            part.length > 1 && part.startsWith("0") -> part.substring(1).takeIf { it.all { c -> c in '0'..'7' } }?.toLongOrNull(8)
            else -> part.takeIf { it.all(Char::isDigit) }?.toLongOrNull(10)
        }
        n ?: return null
    }
    for (i in 0 until values.lastIndex) if (values[i] !in 0..255) return null
    val last = values.last()
    val lastBytes = 4 - values.lastIndex
    if (last < 0 || last >= (1L shl (8 * lastBytes))) return null
    val out = ByteArray(4)
    for (i in 0 until values.lastIndex) out[i] = values[i].toByte()
    for (i in 0 until lastBytes) out[4 - 1 - i] = ((last shr (8 * i)) and 0xFF).toByte()
    return out
}

private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun parseV6(h: String): ByteArray? {
    var text = h
    // An embedded IPv4 tail (::ffff:1.2.3.4) counts as the last two groups.
    var tail: ByteArray? = null
    val lastColon = text.lastIndexOf(':')
    if ('.' in text.substring(lastColon + 1)) {
        tail = parseV4(text.substring(lastColon + 1))?.takeIf { text.substring(lastColon + 1).count { it == '.' } == 3 } ?: return null
        text = text.substring(0, lastColon + 1) + "0:0"
    }
    val doubleColon = text.indexOf("::")
    if (doubleColon != text.lastIndexOf("::")) return null
    fun groups(s: String): List<String>? = if (s.isEmpty()) emptyList() else s.split(':').takeIf { g -> g.all { it.length in 1..4 && it.all(::isHex) } }
    val groupValues: List<Int> = if (doubleColon >= 0) {
        val head = groups(text.substring(0, doubleColon)) ?: return null
        val back = groups(text.substring(doubleColon + 2)) ?: return null
        if (head.size + back.size > 7) return null
        (head.map { it.toInt(16) } + List(8 - head.size - back.size) { 0 } + back.map { it.toInt(16) })
    } else {
        val g = groups(text) ?: return null
        if (g.size != 8) return null
        g.map { it.toInt(16) }
    }
    val out = ByteArray(16)
    groupValues.forEachIndexed { i, v -> out[2 * i] = (v shr 8).toByte(); out[2 * i + 1] = v.toByte() }
    if (tail != null) for (i in 0..3) out[12 + i] = tail[i]
    return out
}

/** True for loopback, private, link-local, shared (carrier NAT), multicast, unspecified and reserved addresses. */
fun isLocalAddress(bytes: ByteArray): Boolean {
    fun u(i: Int) = bytes[i].toInt() and 0xFF
    if (bytes.size == 4) {
        val a = u(0)
        val b = u(1)
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0 && u(2) == 0) ||
            (a == 198 && b in 18..19)
    }
    if (bytes.size != 16) return true
    val allZeroUntil12 = (0 until 12).all { bytes[it].toInt() == 0 }
    val v4 = bytes.copyOfRange(12, 16)
    // ::, ::1, and IPv4-compatible ::a.b.c.d
    if (allZeroUntil12) return isLocalAddress(v4)
    // IPv4-mapped ::ffff:a.b.c.d
    if ((0 until 10).all { bytes[it].toInt() == 0 } && u(10) == 0xFF && u(11) == 0xFF) return isLocalAddress(v4)
    // NAT64 64:ff9b::/96
    if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xFF && u(3) == 0x9B && (4 until 12).all { bytes[it].toInt() == 0 }) return isLocalAddress(v4)
    // 6to4 2002::/16 carries an IPv4 address in bytes 2..5
    if (u(0) == 0x20 && u(1) == 0x02) return isLocalAddress(bytes.copyOfRange(2, 6))
    return (u(0) and 0xFE) == 0xFC ||                 // fc00::/7 unique local
        (u(0) == 0xFE && (u(1) and 0xC0) == 0x80) ||  // fe80::/10 link-local
        (u(0) == 0xFE && (u(1) and 0xC0) == 0xC0) ||  // fec0::/10 site-local (deprecated)
        u(0) == 0xFF ||                               // multicast
        (u(0) == 0x01 && u(1) == 0x00 && (2 until 8).all { bytes[it].toInt() == 0 }) // 100::/64 discard
}

private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa", ".intranet", ".corp", ".private")

/**
 * True when [host] is local by its own text: a local-only name, a name with no dot at all, or
 * an address literal in a local range. A public-looking *name* returns false here, because
 * what it points at is only known by looking it up, which the probe does and then checks with
 * [isLocalAddress].
 */
fun isLocalHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return true
    val h = host.trim().trim('[', ']').lowercase().trimEnd('.')
    parseIpLiteral(h)?.let { return isLocalAddress(it) }
    if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
    if (!h.contains('.')) return true
    // A string of digits and dots that did not parse as an address is a malformed or hostile
    // spelling, never a real name.
    if (h.all { it.isDigit() || it == '.' || it == 'x' }) return true
    return false
}

/** [isLocalHost] for a whole address. An address that cannot be read counts as local. */
fun isLocalUrl(url: String): Boolean = isLocalHost(runCatching { URI(url.trim()).host }.getOrNull())
