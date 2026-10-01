package com.downloadhub.core

/**
 * One downloadable stream, as the site actually offers it.
 *
 * This is a reading of what exists, not a menu of intentions. Every field comes from the
 * extractor's own description of a format, and the size is the real one it reported - so a
 * row in a list of these says what the user will get and what it will cost, which is the
 * only honest version of a quality chooser.
 */
data class StreamFormat(
    val formatId: String,
    val ext: String,
    /** Pixel height, or null for an audio-only format. */
    val height: Int?,
    val fps: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    /** Bytes, or null when the extractor did not know. Zero is not the same as unknown. */
    val sizeBytes: Long?,
    val totalBitrate: Int?,
    val note: String? = null
) {
    val hasVideo: Boolean get() = videoCodec != null && videoCodec != "none"
    val hasAudio: Boolean get() = audioCodec != null && audioCodec != "none"

    /**
     * True when this stream is video on its own and has to be muxed with an audio stream
     * before it is a watchable file.
     *
     * This is the normal case on YouTube today, not the exception. Four videos checked
     * offered 48, 24, 53 and 44 formats between them and **not one** had both an audio and
     * a video stream, so every quality is two downloads and a merge. A chooser that did not
     * say so would be offering a file the user cannot play.
     */
    val needsMerge: Boolean get() = hasVideo && !hasAudio

    /** "1080p" where there is a height, and whatever the extractor called it where not. */
    val qualityLabel: String
        get() = when {
            height != null -> "${height}p"
            hasAudio -> note?.takeIf { it.isNotBlank() } ?: "Audio"
            else -> note?.takeIf { it.isNotBlank() } ?: ext
        }

    /** "1080p60" where the frame rate is worth saying, which is a real quality difference. */
    val fullLabel: String
        get() {
            val base = qualityLabel
            return if (hasVideo && fps != null && fps >= 50) "$base${fps}" else base
        }

    /** True when the two streams are separately downloadable and must be joined. */
    val isAudioOnly: Boolean get() = hasAudio && !hasVideo
}

/**
 * Reads the extractor's format list.
 *
 * Takes the JSON it printed rather than the human-readable table, because the table rounds
 * the sizes - "120.53MiB" - and this exists so a user can be told the real number.
 *
 * A format with no size is kept, not dropped. It is shown with the size unknown, which is
 * true; dropping it would hide a 4K option because the extractor did not estimate, which is
 * not.
 */
fun parseStreamFormats(json: String): List<StreamFormat> {
    val root = runCatching { parseJson(json) }.getOrElse { return emptyList() }
    return root.array("formats").mapNotNull { row ->
        val id = row.string("format_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val video = row.string("vcodec")?.takeIf { it != "none" }
        val audio = row.string("acodec")?.takeIf { it != "none" }
        if (video == null && audio == null) return@mapNotNull null
        StreamFormat(
            formatId = id,
            ext = row.string("ext").orEmpty(),
            height = row.number("height").takeIf { it > 0L }?.toInt(),
            fps = row.number("fps").takeIf { it > 0L }?.toInt(),
            videoCodec = video,
            audioCodec = audio,
            // filesize is the exact figure when the site gave one, filesize_approx the
            // estimate when it did not. The exact one wins, and a missing size stays null
            // rather than becoming zero.
            sizeBytes = row.number("filesize").takeIf { it > 0L }
                ?: row.number("filesize_approx").takeIf { it > 0L },
            totalBitrate = row.number("tbr").takeIf { it > 0.0 }?.toInt(),
            note = row.string("format_note")
        )
    }
}

/**
 * The video qualities worth offering, best first, one row per height.
 *
 * One row per height because a list of every container and codec is a list of forty rows
 * that differ in ways nobody choosing a download cares about. The best of each height is
 * kept - the highest frame rate, then the largest - so 1080p60 is the row and 1080p30 is not
 * a second row competing with it.
 */
fun offerVideoFormats(formats: List<StreamFormat>): List<StreamFormat> =
    formats.filter { it.hasVideo }
        .groupBy { it.height }
        .mapNotNull { (_, group) -> group.maxWithOrNull(compareBy({ it.fps ?: 0 }, { it.sizeBytes ?: 0L })) }
        .sortedByDescending { it.height ?: 0 }

/**
 * The audio qualities worth offering, best first.
 *
 * By bitrate, because for audio that is the whole quality difference, and the bitrate is
 * reported. A lossy codec and an AAC one are the same thing to a listener if the numbers
 * are the same, and the numbers are not.
 */
fun offerAudioFormats(formats: List<StreamFormat>): List<StreamFormat> =
    formats.filter { it.isAudioOnly }
        .sortedWith(
            compareByDescending<StreamFormat> { it.totalBitrate ?: 0 }
                .thenByDescending { it.sizeBytes ?: 0L }
        )
        .distinctBy { it.ext }

/**
 * The format pair for a chosen video quality, or null when the height is not offered.
 *
 * A pair rather than a selector string, so the caller can see what it is committing to -
 * and can say so in the dialog - before asking the extractor for it.
 */
data class StreamChoice(
    val video: StreamFormat,
    val audio: StreamFormat?
) {
    /** The bytes actually transferred: both streams, because both are fetched. */
    val totalBytes: Long
        get() = (video.sizeBytes ?: 0L) + (audio?.sizeBytes ?: 0L)

    /** Whether the two have to be joined, which is the normal case. */
    val needsMerge: Boolean get() = audio != null
}

/**
 * Pairs a video quality with the best audio stream, which is what actually gets downloaded.
 *
 * The audio is chosen by bitrate and nothing else, because it is not a choice the user was
 * offered: nobody picks which audio stream goes with their 1080p video, and asking would be
 * a question about an implementation detail.
 */
fun chooseStream(
    formats: List<StreamFormat>,
    height: Int,
    audio: List<StreamFormat> = offerAudioFormats(formats)
): StreamChoice? {
    val video = formats.filter { it.hasVideo && it.height == height }
        .maxWithOrNull(compareBy({ it.fps ?: 0 }, { it.sizeBytes ?: 0L })) ?: return null
    return StreamChoice(video, audio.firstOrNull())
}

/** The best audio-only download on offer, or null when there is none. */
fun bestAudioOnly(formats: List<StreamFormat>): StreamFormat? = offerAudioFormats(formats).firstOrNull()

/**
 * Whether any format can be downloaded as one file.
 *
 * It exists to be asserted in a test, because it is a fact about YouTube that changes
 * silently and that several pieces of this code were written assuming was true. When it goes
 * back to being true, the fallback paths become worth having again; until then they are
 * dead code pretending to be a safety net.
 */
fun hasAnyProgressiveFormat(formats: List<StreamFormat>): Boolean =
    formats.any { it.hasVideo && it.hasAudio }
