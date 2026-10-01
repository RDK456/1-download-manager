package com.downloadhub.app.download

import com.downloadhub.core.StreamFormat
import com.downloadhub.core.YouTubeEntry
import com.downloadhub.core.chooseStream
import com.downloadhub.core.filterKnownEntries
import com.downloadhub.core.offerAudioFormats
import com.downloadhub.core.offerVideoFormats
import com.downloadhub.core.parseYouTubeListing
import com.downloadhub.core.youTubeIdFromUrl
import com.yausername.youtubedl_android.mapper.VideoFormat

/**
 * What a YouTube link actually offers, with real sizes.
 *
 * The add sheet used to offer a fixed menu - Best, 4K, 2K, 1080p and down - which
 * is a menu of intentions, not of what exists. A video published in 1080p still
 * showed 4K and 2K rows, and picking one silently downloaded 1080p: the ceiling
 * selector `bestvideo[height<=2160]` cannot tell the difference between "the video
 * has 4K" and "the video stops at 1080p". This lists the extractor's own formats
 * instead, so a 1080p video shows rows up to 1080p and nothing above it.
 */
data class YouTubeFormatListing(
    val videoFormats: List<StreamFormat>,
    val audioFormats: List<StreamFormat>,
    val title: String,
    /** Best video plus audio, because that is what the top row will transfer. */
    val bestTotalBytes: Long,
    val error: String?,
    /**
     * Every format the extractor offered, ungrouped.
     *
     * The section's single-video view lists these raw - every height, frame
     * rate and codec - because collapsing one row per height is what once hid a
     * 4K option behind a 1080p row. Empty unless built by [youTubeFormatListing].
     */
    val allFormats: List<StreamFormat> = emptyList()
) {
    val isEmpty: Boolean get() = videoFormats.isEmpty() && audioFormats.isEmpty()
}

/**
 * Reads one wrapper format into the shared model.
 *
 * Null when the format carries neither stream: storyboards, thumbnails and other
 * metadata ride in the same list, and offering them as qualities would be offering
 * downloads that are not videos. A missing size stays null rather than becoming
 * zero - unknown is not empty.
 */
fun VideoFormat.toStreamFormat(): StreamFormat? {
    val id = formatId?.takeIf { it.isNotBlank() } ?: return null
    val video = vcodec?.takeIf { it != "none" }
    val audio = acodec?.takeIf { it != "none" }
    if (video == null && audio == null) return null
    return StreamFormat(
        formatId = id,
        ext = ext.orEmpty(),
        height = height.takeIf { it > 0 },
        fps = fps.takeIf { it > 0 },
        videoCodec = video,
        audioCodec = audio,
        sizeBytes = fileSize.takeIf { it > 0L } ?: fileSizeApproximate.takeIf { it > 0L },
        totalBitrate = tbr.takeIf { it > 0 },
        note = formatNote
    )
}

/** Builds a listing from the extractor's formats: one row per height, best first. */
fun youTubeFormatListing(all: List<StreamFormat>, title: String): YouTubeFormatListing {
    val video = offerVideoFormats(all)
    val audio = offerAudioFormats(all)
    return YouTubeFormatListing(
        videoFormats = video,
        audioFormats = audio,
        title = title,
        bestTotalBytes = video.firstOrNull()
            ?.let { chooseStream(all, it.height ?: 0, audio)?.totalBytes } ?: 0L,
        error = null,
        allFormats = all
    )
}

fun failedYouTubeFormats(reason: String): YouTubeFormatListing =
    YouTubeFormatListing(emptyList(), emptyList(), "", 0L, reason)

/**
 * The video id in a YouTube URL, whatever shape it arrived in.
 *
 * One copy, in :core: this used to own its own, and two copies of an
 * eleven-character regex in two modules is how they drift apart.
 */
fun youTubeVideoId(url: String): String? = youTubeIdFromUrl(url)

/**
 * Drops entries already on record, by id.
 *
 * Downloading the same song twice is the one thing the section must never do,
 * and the id is the only reliable key: titles repeat across uploads, and URLs
 * vary by shape while the id never does.
 */
fun filterQueuedEntries(
    entries: List<YouTubeEntry>,
    isKnown: (String) -> Boolean
): List<YouTubeEntry> = filterKnownEntries(entries, isKnown)

/**
 * Whether YouTube itself admits the video exists: true for public, false for
 * gone-or-private, null when the check could not complete.
 */
fun youTubeVideoLooksPublic(videoId: String): Boolean? = runCatching {
    val connection = java.net.URL(
        "https://www.youtube.com/oembed?url=" +
            "https://www.youtube.com/watch?v=$videoId&format=json"
    ).openConnection().apply {
        setRequestProperty("User-Agent", "1-download-manager")
        connectTimeout = 8_000
        readTimeout = 8_000
    } as java.net.HttpURLConnection
    try {
        when (connection.responseCode) {
            200 -> true
            401, 403, 404 -> false
            else -> null
        }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

/**
 * What a pasted link holds: a playlist, album, channel, or one video.
 *
 * Entries, or why there are none: a collection the extractor refused reads the
 * same as an empty one unless the reason travels with it.
 */
data class AppPlaylistFetch(
    val title: String,
    val entries: List<YouTubeEntry>,
    val single: Boolean,
    val error: String?
)
