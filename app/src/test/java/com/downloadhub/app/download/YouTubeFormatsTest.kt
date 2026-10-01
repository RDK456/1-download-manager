package com.downloadhub.app.download

import com.downloadhub.core.chooseStream
import com.downloadhub.core.offerVideoFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add sheet used to offer a fixed Best-to-360p menu for every video, so a
 * 1080p video showed 4K and 2K rows that silently downloaded 1080p. The picker
 * now lists what the extractor offered, which means the mapping from the
 * wrapper's formats to rows - and the rows for a video that stops at 1080p - are
 * worth pinning down.
 */
class YouTubeFormatsTest {

    private fun videoFormat(
        id: String,
        height: Int,
        fps: Int = 30,
        size: Long = 10_000_000L,
        vcodec: String? = "avc1.640028",
        acodec: String? = "none",
        ext: String = "mp4"
    ) = com.downloadhub.core.StreamFormat(
        formatId = id,
        ext = ext,
        height = height.takeIf { it > 0 },
        fps = fps.takeIf { it > 0 },
        videoCodec = vcodec,
        audioCodec = acodec,
        sizeBytes = size.takeIf { it > 0L },
        totalBitrate = null,
        note = null
    )

    private fun audioFormat(id: String, ext: String = "m4a", bitrate: Int = 128) =
        com.downloadhub.core.StreamFormat(
            formatId = id,
            ext = ext,
            height = null,
            fps = null,
            videoCodec = "none",
            audioCodec = "mp4a.40.2",
            sizeBytes = 5_000_000L,
            totalBitrate = bitrate,
            note = null
        )

    @Test
    fun a1080pVideoOffersNothingAbove1080p() {
        val all = listOf(
            videoFormat("248", 1080),
            videoFormat("247", 720),
            videoFormat("244", 480),
            audioFormat("140")
        )
        val offered = offerVideoFormats(all)
        assertEquals(listOf(1080, 720, 480), offered.map { it.height })
        assertTrue(
            "a 4K or 2K row must never appear for this video",
            offered.none { (it.height ?: 0) > 1080 }
        )
    }

    @Test
    fun oneRowPerHeightKeepsTheHigherFrameRate() {
        val all = listOf(
            videoFormat("303", 1080, fps = 60),
            videoFormat("248", 1080, fps = 30),
            audioFormat("140")
        )
        val offered = offerVideoFormats(all)
        assertEquals(1, offered.size)
        assertEquals("303", offered.single().formatId)
    }

    @Test
    fun thePairIsTheRowPlusTheBestAudio() {
        val all = listOf(
            videoFormat("248", 1080),
            audioFormat("140", bitrate = 128),
            audioFormat("251", ext = "webm", bitrate = 160)
        )
        val listing = youTubeFormatListing(all, "title")
        val pair = chooseStream(
            (listing.videoFormats + listing.audioFormats).distinctBy { it.formatId },
            1080,
            listing.audioFormats
        )
        assertEquals("248", pair?.video?.formatId)
        // Best by bitrate, not first in the list.
        assertEquals("251", pair?.audio?.formatId)
    }

    @Test
    fun anEmptyExtractionExplainsItself() {
        val failed = failedYouTubeFormats("No downloadable streams were offered for that link.")
        assertTrue(failed.isEmpty)
        assertEquals(
            "No downloadable streams were offered for that link.",
            failed.error
        )
    }

    @Test
    fun theWrapperMappingKeepsSizesAndDropsMetadata() {
        val json = """
            {
              "format_id": "248",
              "ext": "webm",
              "height": 1080,
              "fps": 30,
              "vcodec": "vp9",
              "acodec": "none",
              "filesize": 257000000,
              "filesize_approx": 0,
              "tbr": 1200,
              "format_note": "1080p"
            }
        """.trimIndent()
        val parsed = com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(json, com.yausername.youtubedl_android.mapper.VideoFormat::class.java)
        val mapped = parsed.toStreamFormat()
        assertEquals("248", mapped?.formatId)
        assertEquals(1080, mapped?.height)
        assertEquals(257000000L, mapped?.sizeBytes)
        assertTrue(mapped?.needsMerge == true)
    }

    @Test
    fun theWrapperMappingPrefersExactSizeOverEstimate() {
        val json = """
            {
              "format_id": "140",
              "ext": "m4a",
              "height": 0,
              "fps": 0,
              "vcodec": "none",
              "acodec": "mp4a.40.2",
              "filesize": 0,
              "filesize_approx": 5200000,
              "tbr": 128,
              "format_note": "medium"
            }
        """.trimIndent()
        val parsed = com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(json, com.yausername.youtubedl_android.mapper.VideoFormat::class.java)
        val mapped = parsed.toStreamFormat()
        assertEquals(5200000L, mapped?.sizeBytes)
        assertTrue(mapped?.isAudioOnly == true)
    }

    @Test
    fun thumbnailAndStoryboardEntriesAreNotQualities() {
        val json = """
            {
              "format_id": "sb0",
              "ext": "mhtml",
              "height": 0,
              "fps": 0,
              "vcodec": "none",
              "acodec": "none",
              "filesize": 1000,
              "filesize_approx": 0,
              "tbr": 0,
              "format_note": "storyboard"
            }
        """.trimIndent()
        val parsed = com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(json, com.yausername.youtubedl_android.mapper.VideoFormat::class.java)
        assertNull(
            "a storyboard is not a download and must not become a row",
            parsed.toStreamFormat()
        )
    }

    /**
     * The rows that were not streams.
     *
     * A real extraction on one video reported 3.34 MB at 1772p, 1182p, 886p,
     * 590p and 394p - five heights, identical size - next to a real 334 MB
     * 1772p stream. Five different resolutions cannot all be 3.34 MB, so those
     * rows are previews or duplicates wearing a real stream's label, and a list
     * that shows them looks like noise rather than like a choice.
     */
    @Test
    fun rowsFarSmallerThanTheirOwnHeightAreNotStreams() {
        val preview = 3_340_000L
        val formats = listOf(
            videoFormat("401", 1772, size = preview, vcodec = "vp09", ext = "mp4"),
            videoFormat("399", 1772, size = 334_150_000L, vcodec = "vp9", ext = "webm"),
            videoFormat("137", 1772, size = 202_070_000L, vcodec = "av01", ext = "mp4"),
            videoFormat("271", 1182, size = preview, vcodec = "vp09", ext = "mp4"),
            videoFormat("136", 1182, size = 141_880_000L, vcodec = "avc1", ext = "mp4"),
            videoFormat("140", height = 0, size = preview, vcodec = null, ext = "mp4")
        )
        val offered = com.downloadhub.core.offerVideoRows(formats)
        assertEquals(
            "the 3.34 MB rows are previews and must not be offered",
            listOf(399L, 137L, 136L),
            offered.map { it.formatId.toLong() }
        )
        assertEquals(
            "largest first",
            listOf(1772, 1772, 1182),
            offered.map { it.height }
        )
    }

    @Test
    fun aRealSmallerVariantOfTheSameHeightSurvives() {
        val formats = listOf(
            videoFormat("401", 1080, size = 200_000_000L, vcodec = "vp9"),
            videoFormat("137", 1080, size = 150_000_000L, vcodec = "av01"),
            videoFormat("136", 1080, size = 120_000_000L, vcodec = "avc1")
        )
        assertEquals(
            "three real streams at one height are three choices",
            3,
            com.downloadhub.core.offerVideoRows(formats).size
        )
    }

    @Test
    fun anUnknownSizeIsKeptBecauseUnknownIsNotSmall() {
        val formats = listOf(
            videoFormat("399", 1080, size = 200_000_000L),
            com.downloadhub.core.StreamFormat(
                formatId = "298",
                ext = "mp4",
                height = 1080,
                fps = 60,
                videoCodec = "avc1",
                audioCodec = "none",
                sizeBytes = null,
                totalBitrate = null
            )
        )
        assertEquals(2, com.downloadhub.core.offerVideoRows(formats).size)
    }

    @Test
    fun theSameFormatListedTwiceIsOneRow() {
        val one = videoFormat("399", 1080, size = 200_000_000L)
        assertEquals(1, com.downloadhub.core.offerVideoRows(listOf(one, one)).size)
    }

    @Test
    fun knownIdsAreDroppedByIdNotTitle() {
        val entries = listOf(
            com.downloadhub.core.YouTubeEntry("KPh2Efv66Aw", "Same Title", 1),
            com.downloadhub.core.YouTubeEntry("dQw4w9WgXcQ", "Same Title", 2)
        )
        val remaining = filterQueuedEntries(entries) { it == "KPh2Efv66Aw" }
        assertEquals(listOf("dQw4w9WgXcQ"), remaining.map { it.id })
    }

    @Test
    fun videoIdsAreFoundWhateverShapeTheLinkArrivedIn() {
        val id = "KPh2Efv66Aw"
        mapOf(
            "https://www.youtube.com/watch?v=$id" to id,
            "https://youtu.be/$id" to id,
            "https://www.youtube.com/shorts/$id" to id,
            "https://www.youtube.com/embed/$id" to id
        ).forEach { (url, expected) ->
            assertEquals("no id found in $url", expected, youTubeVideoId(url))
        }
        assertNull(youTubeVideoId("https://example.com/watch?v=$id"))
    }
}
