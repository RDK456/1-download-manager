package com.downloadhub.core

import java.io.File

/** One blocked address range, both ends included. */
data class IpRange(val start: String, val end: String)

/**
 * qBittorrent's IP filter file formats.
 *
 * eMule `.dat`: `001.002.003.000 - 001.002.003.255 , 100 , Some description`, where a
 * level of 127 or lower means blocked. PeerGuardian `.p2p`: `Some description:1.2.3.0-1.2.3.255`.
 * Comment lines (`#`, `//`) and anything that does not parse are skipped, because these
 * lists are hundreds of thousands of lines from the internet and one bad line must not
 * throw the rest away.
 */
object IpFilterParser {
    private const val ADDRESS = """(\d{1,3}(?:\.\d{1,3}){3})"""
    private val dat = Regex("""^\s*$ADDRESS\s*-\s*$ADDRESS\s*,\s*(\d+)""")
    private val p2p = Regex(""":\s*$ADDRESS\s*-\s*$ADDRESS\s*$""")

    fun parse(lines: Sequence<String>): List<IpRange> = lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@mapNotNull null
        dat.find(line)?.let { match ->
            val level = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            return@mapNotNull if (level <= 127) range(match.groupValues[1], match.groupValues[2]) else null
        }
        p2p.find(line)?.let { match -> range(match.groupValues[1], match.groupValues[2]) }
    }.toList()

    fun parse(file: File): List<IpRange> = file.useLines { parse(it) }

    /** Normalises "001.002.003.000" to "1.2.3.0" and drops impossible addresses. */
    private fun range(start: String, end: String): IpRange? {
        val a = normalise(start) ?: return null
        val b = normalise(end) ?: return null
        return IpRange(a, b)
    }

    private fun normalise(ip: String): String? {
        val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return parts.joinToString(".")
    }
}
