package com.downloadhub.core

/**
 * One video inside a YouTube collection.
 *
 * A collection is whatever the link was: a playlist, an album, a channel, an
 * artist page, a likes list. yt-dlp lists them all the same way, so this does
 * not care which one it was given - and neither does the section that shows it.
 */
data class YouTubeEntry(
    /** The eleven characters, so dedup and queuing never depend on URL shapes. */
    val id: String,
    val title: String,
    /** Seconds, or zero when the listing did not say. Zero is not short. */
    val durationSeconds: Long,
    /** The uploader where offered; flat listings usually offer it. */
    val uploader: String? = null
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

/**
 * Everything one pasted link turned out to be.
 *
 * A single video arrives as one entry with [single] set, so the section shows
 * the same list either way and the queue path never branches on link shape.
 */
data class YouTubeListing(
    /** Playlist title, channel name, or the video's own title when single. */
    val title: String,
    val entries: List<YouTubeEntry>,
    val single: Boolean
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * Reads `--dump-single-json`, flat or full.
 *
 * Flat is what the section asks for: one small object per entry instead of a
 * hundred kilobytes each, which is the difference between a playlist that lists
 * in seconds and one that downloads fourteen megabytes of JSON first. Full
 * output parses the same way, so a pasted single video - which is never flat -
 * needs no second code path.
 *
 * Null entries are skipped, not kept: a flat listing carries explicit nulls for
 * unavailable videos, and a row that can never download is not an entry. Dupes
 * collapse by id for the same reason playlists repeat: "all of an artist" and
 * "this album" overlap, and queuing both would download twice.
 *
 * Null when there is nothing to show: empty output, unparseable output, or a
 * listing with no usable entry in it.
 */
fun parseYouTubeListing(json: String): YouTubeListing? {
    val root = runCatching { parseJson(json) }.getOrElse { return null }
    if (root !is JsonValue.Obj) return null
    val type = root.string("_type")
    if (type == null || type == "video") {
        val entry = readEntry(root) ?: return null
        val title = root.string("title")?.takeIf { it.isNotBlank() } ?: entry.title
        return YouTubeListing(title, listOf(entry), single = true)
    }
    if (type != "playlist") return null
    val title = root.string("title")?.takeIf { it.isNotBlank() }.orEmpty()
    val seen = LinkedHashSet<String>()
    val entries = root.array("entries").mapNotNull { row ->
        if (row !is JsonValue.Obj) return@mapNotNull null
        val entry = readEntry(row) ?: return@mapNotNull null
        if (!seen.add(entry.id)) return@mapNotNull null
        entry
    }
    if (entries.isEmpty()) return null
    return YouTubeListing(title, entries, single = false)
}

/**
 * One entry row, flat or full.
 *
 * The id is read from `id` first and from the URL second: flat rows carry both,
 * full rows carry both, and a row with neither is metadata rather than a video.
 * Eleven characters is the whole format check - anything else is a playlist id
 * or a channel id that wandered into the entries.
 */
private fun readEntry(row: JsonValue.Obj): YouTubeEntry? {
    val id = row.string("id")?.takeIf { it.isNotBlank() }
        ?: row.string("url")?.let { youTubeIdFromUrl(it) }
        ?: row.string("webpage_url")?.let { youTubeIdFromUrl(it) }
        ?: return null
    if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
    val title = row.string("title")?.takeIf { it.isNotBlank() } ?: id
    return YouTubeEntry(
        id = id,
        title = title,
        durationSeconds = row.number("duration").takeIf { it > 0L } ?: 0L,
        uploader = row.string("uploader")?.takeIf { it.isNotBlank() }
            ?: row.string("channel")?.takeIf { it.isNotBlank() }
    )
}

/**
 * The video id in a URL, whatever shape it arrived in.
 *
 * Shared with the desktop message check, which used to own its own copy: two
 * copies of an eleven-character regex in two modules is how they drift apart.
 */
fun youTubeIdFromUrl(url: String): String? {
    val trimmed = url.trim()
    val host = runCatching { java.net.URI(trimmed).host?.lowercase() }.getOrNull()
        ?: return null
    if (!host.contains("youtube.com") && host != "youtu.be") return null
    val patterns = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("youtube\\.com/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})")
    )
    return patterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.get(1) }
}

/**
 * Drops entries already on record, by id.
 *
 * Downloading the same song twice is soundcli's headline never-do, and the id
 * is the only reliable key: titles repeat across uploads, and URLs vary by
 * shape while the id never does.
 */
fun filterKnownEntries(
    entries: List<YouTubeEntry>,
    isKnown: (String) -> Boolean
): List<YouTubeEntry> = entries.filterNot { isKnown(it.id) }

/**
 * The whole playlist behind a link to one video in it (`watch?v=…&list=…`), or null.
 *
 * Listing such a link shows just the video - it is what was clicked - so this is what an
 * "all of the playlist" offer opens instead. YouTube's auto-generated mixes (`RD…`) are
 * left out: they are a radio station, not a list, and have no playlist page to open.
 */
fun youTubePlaylistLink(url: String): String? {
    if (!LinkParser.isYouTube(url)) return null
    val id = Regex("""[?&]list=([\w-]+)""").find(url)?.groupValues?.get(1) ?: return null
    if (id.startsWith("RD")) return null
    return "https://www.youtube.com/playlist?list=$id"
}
