package com.downloadhub.app.ui.expressive.model

import java.net.URLDecoder
import java.net.URLEncoder

/** One queued item as it is written to disk. Mirrors the player's own item. */
data class SnapItem(
    val uri: String,
    val title: String,
    val isVideo: Boolean,
    val isLive: Boolean = false,
    val userAgent: String? = null,
    val referrer: String? = null
)

/**
 * The player's queue, where it was, and how far in, in a form that survives the app being killed.
 * Plain text rather than JSON so it can be written and read without a library and tested on the
 * JVM: a header line, then one line per item with percent-encoded, comma-separated fields.
 */
data class PlayerSnapshot(val items: List<SnapItem>, val index: Int, val positionMs: Long) {

    fun encode(): String = buildString {
        append("v1\n").append(index).append('\n').append(positionMs).append('\n')
        items.forEach { i ->
            append(
                listOf(
                    enc(i.uri), enc(i.title), if (i.isVideo) "1" else "0", if (i.isLive) "1" else "0",
                    i.userAgent?.let(::enc) ?: NULL, i.referrer?.let(::enc) ?: NULL
                ).joinToString(",")
            ).append('\n')
        }
    }

    /**
     * What is worth bringing back: live streams are not (a paused channel has nothing to resume),
     * and a very long queue is cut to a window around the current item so the file stays small.
     * Null when nothing playable remains or the current item was a live one.
     */
    fun restorable(maxItems: Int = 300): PlayerSnapshot? {
        items.getOrNull(index)?.takeIf { !it.isLive } ?: return null
        val keep = items.filter { !it.isLive }
        // Counted, not searched for: the same song may be in the queue twice.
        val at = items.take(index).count { !it.isLive }
        val from = (at - maxItems / 2).coerceAtLeast(0)
        val window = keep.drop(from).take(maxItems)
        return PlayerSnapshot(window, at - from, positionMs)
    }

    companion object {
        private const val NULL = "~"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

        /** Null for anything that is not exactly what [encode] writes, so a damaged file restores nothing. */
        fun decode(text: String?): PlayerSnapshot? {
            if (text.isNullOrBlank()) return null
            // Padding around lines is tolerated: the preferences file adds some after the last one.
            val lines = text.trim().split('\n').map { it.trim() }
            if (lines.size < 4 || lines[0] != "v1") return null
            val index = lines[1].toIntOrNull() ?: return null
            val position = lines[2].toLongOrNull() ?: return null
            val items = lines.drop(3).filter { it.isNotEmpty() }.map { line ->
                val f = line.split(',')
                if (f.size != 6) return null
                runCatching {
                    SnapItem(dec(f[0]), dec(f[1]), f[2] == "1", f[3] == "1", f[4].takeIf { it != NULL }?.let(::dec), f[5].takeIf { it != NULL }?.let(::dec))
                }.getOrNull() ?: return null
            }
            if (items.isEmpty() || index !in items.indices) return null
            return PlayerSnapshot(items, index, position.coerceAtLeast(0L))
        }
    }
}
