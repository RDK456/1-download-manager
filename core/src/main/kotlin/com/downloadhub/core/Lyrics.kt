package com.downloadhub.core

import java.net.URLEncoder

/** One line of lyrics and when it is sung; 0 for every line of unsynced lyrics. */
data class LyricLine(val atMillis: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/**
 * Lyrics for the player's lyrics panel, from LRCLIB - a free, open lyrics database whose
 * lyrics are timed line by line, so the panel can follow the song.
 */
object LyricsSource {
    suspend fun find(title: String, artist: String): Lyrics? {
        if (title.isBlank()) return null
        val query = buildString {
            append("track_name=").append(URLEncoder.encode(title, "UTF-8"))
            if (artist.isNotBlank()) append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
        }
        return runCatching { parseSearch(fetchText("https://lrclib.net/api/search?$query")) }.getOrNull()
    }

    /** The best of LRCLIB's matches: synced lyrics first, plain ones otherwise. */
    fun parseSearch(json: String): Lyrics? {
        val hits = parseJson(json).let { (it as? JsonValue.Arr)?.items.orEmpty() }
        hits.firstNotNullOfOrNull { it.string("syncedLyrics")?.takeIf(String::isNotBlank) }?.let { synced ->
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
        }
        val plain = hits.firstNotNullOfOrNull { it.string("plainLyrics")?.takeIf(String::isNotBlank) } ?: return null
        return Lyrics(plain.lines().map { LyricLine(0L, it) }, synced = false)
    }

    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** "[01:02.50] text" lines, in time order; a line may carry several stamps. */
    fun parseLrc(text: String): List<LyricLine> = text.lines().flatMap { line ->
        val stamps = stamp.findAll(line).toList()
        if (stamps.isEmpty()) return@flatMap emptyList()
        val words = line.substring(stamps.last().range.last + 1).trim()
        stamps.map { match ->
            val (min, sec, frac) = match.destructured
            val fraction = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.toLong()
            }
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + fraction, words)
        }
    }.sortedBy { it.atMillis }

    /** The line being sung at [positionMillis], or -1 before the first. */
    fun lineAt(lines: List<LyricLine>, positionMillis: Long): Int =
        lines.indexOfLast { it.atMillis <= positionMillis }

    /**
     * A title and artist from a file name: "Artist - Title (Official Video) [1080p].mp3"
     * gives ("Title", "Artist"). Without a dash the whole name is the title.
     */
    fun guessTitleArtist(fileName: String): Pair<String, String> {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
            .replace('_', ' ')
            .trim()
        val dash = base.indexOf(" - ")
        return if (dash > 0) base.substring(dash + 3).trim() to base.substring(0, dash).trim() else base to ""
    }
}
