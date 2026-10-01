package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the extractor's format list, and turning it into something worth choosing from.
 *
 * The shape of these tests is dictated by a fact about YouTube that is easy to forget and
 * expensive to be wrong about: **it serves no single-file formats any more.** Four videos
 * checked offered 48, 24, 53 and 44 formats and not one had both an audio and a video
 * stream. So "pick a quality" means "pick two files and join them", and anything in this
 * file that pretends otherwise is testing a fiction.
 */
class StreamFormatTest {

    /**
     * A trimmed real response.
     *
     * Real field names, real codec strings, and both the exact `filesize` and the
     * `filesize_approx` that the real one also carries - because a parser tested only
     * against the field that happens to be present is a parser that silently loses sizes the
     * day a site stops sending it.
     */
    private val REAL = """
        {"title":"A Video","duration":212,
         "formats":[
          {"format_id":"160","ext":"mp4","height":144,"fps":12,"vcodec":"avc1.4d400c",
           "acodec":"none","filesize":1234567,"tbr":100.0,"format_note":"144p"},
          {"format_id":"278","ext":"webm","height":144,"fps":12,"vcodec":"vp9",
           "acodec":"none","filesize":1100000,"tbr":90.0,"format_note":"144p"},
          {"format_id":"137","ext":"mp4","height":1080,"fps":30,"vcodec":"avc1.640028",
           "acodec":"none","filesize":50000000,"tbr":4000.0,"format_note":"1080p"},
          {"format_id":"248","ext":"webm","height":1080,"fps":60,"vcodec":"vp9",
           "acodec":"none","filesize":48000000,"tbr":4200.0,"format_note":"1080p60"},
          {"format_id":"136","ext":"mp4","height":720,"fps":30,"vcodec":"avc1.4d401f",
           "acodec":"none","filesize":22000000,"tbr":1800.0,"format_note":"720p"},
          {"format_id":"140","ext":"m4a","height":null,"vcodec":"none",
           "acodec":"mp4a.40.2","filesize":3000000,"tbr":130.0,"format_note":"medium"},
          {"format_id":"251","ext":"webm","height":null,"vcodec":"none",
           "acodec":"opus","filesize":2900000,"tbr":140.0,"format_note":"medium"},
          {"format_id":"sb0","ext":"mhtml","height":null,"vcodec":"none",
           "acodec":"none","filesize":800000,"format_note":"storyboard"}
         ]}
    """.trimIndent()

    @Test
    fun `every format with a stream is read`() {
        val formats = parseStreamFormats(REAL)
        // The storyboard has neither, so it is not a format anyone can download as video.
        assertEquals(7, formats.size)
    }

    @Test
    fun `a video-only format needs merging and says so`() {
        val video = parseStreamFormats(REAL).first { it.formatId == "137" }
        assertTrue(video.hasVideo)
        assertFalse(video.hasAudio)
        assertTrue("a video-only stream is two files until something joins them", video.needsMerge)
    }

    @Test
    fun `an audio-only format is not a video`() {
        val audio = parseStreamFormats(REAL).first { it.formatId == "140" }
        assertTrue(audio.isAudioOnly)
        assertFalse(audio.hasVideo)
        assertFalse(audio.needsMerge)
        assertNull("an audio format has no height", audio.height)
    }

    @Test
    fun `sizes are the real ones`() {
        val formats = parseStreamFormats(REAL)
        assertEquals(50000000L, formats.first { it.formatId == "137" }.sizeBytes)
        assertEquals(3000000L, formats.first { it.formatId == "140" }.sizeBytes)
    }

    /**
     * A size the site did not give stays unknown.
     *
     * Not zero. A zero beside a quality is a claim that the file costs nothing, and it is
     * indistinguishable from a format that really is free.
     */
    @Test
    fun `a missing size is unknown rather than zero`() {
        val json = """{"formats":[{"format_id":"1","ext":"mp4","height":720,
            "vcodec":"avc1","acodec":"none","format_note":"720p"}]}"""
        assertNull(parseStreamFormats(json).single().sizeBytes)
    }

    /** The exact figure is preferred over the estimate when the site sends both. */
    @Test
    fun `the exact size beats the estimate`() {
        val json = """{"formats":[{"format_id":"1","ext":"mp4","height":720,
            "vcodec":"avc1","acodec":"none","filesize":999,"filesize_approx":8888}]}"""
        assertEquals(999L, parseStreamFormats(json).single().sizeBytes)
    }

    /** An estimate alone is better than nothing, and is used. */
    @Test
    fun `an estimate is used when there is no exact size`() {
        val json = """{"formats":[{"format_id":"1","ext":"mp4","height":720,
            "vcodec":"avc1","acodec":"none","filesize_approx":8888}]}"""
        assertEquals(8888L, parseStreamFormats(json).single().sizeBytes)
    }

    /**
     * One row per height, and the best of each height wins.
     *
     * A list of every container and codec is forty rows differing in ways nobody choosing a
     * download cares about. And 1080p60 must beat 1080p30 - that is a real quality
     * difference, and hiding it behind "1080p" is how a user ends up with the lesser one
     * without being told they had a choice.
     */
    @Test
    fun `one row per height, best first, and the higher frame rate wins`() {
        val offered = offerVideoFormats(parseStreamFormats(REAL))
        assertEquals(listOf(1080, 720, 144), offered.map { it.height })

        val tenEighty = offered.first { it.height == 1080 }
        assertEquals("248", tenEighty.fps.let { tenEighty.formatId })
        assertEquals(60, tenEighty.fps)
    }

    @Test
    fun `a sixty frame rate is worth saying and a thirty is not`() {
        val formats = parseStreamFormats(REAL)
        assertEquals("1080p60", formats.first { it.formatId == "248" }.fullLabel)
        assertEquals("1080p", formats.first { it.formatId == "137" }.fullLabel)
    }

    /** Audio by bitrate, which is the whole quality difference, and one row per container. */
    @Test
    fun `audio is offered by bitrate, once per container`() {
        val offered = offerAudioFormats(parseStreamFormats(REAL))
        assertEquals(2, offered.size)
        assertTrue("the higher bitrate must come first", offered.first().totalBitrate!! >= offered.last().totalBitrate!!)
        assertEquals(offered.size, offered.map { it.ext }.toSet().size)
    }

    /**
     * A chosen quality is two files, and the total is what actually transfers.
     *
     * The number shown in a chooser has to be the number the user is about to spend, which
     * is both streams - not the video alone, which is the mistake that makes a 1080p row
     * look like 48 MB when the download is 51.
     */
    @Test
    fun `a chosen quality is the video plus the best audio`() {
        val formats = parseStreamFormats(REAL)
        val choice = chooseStream(formats, 1080)
        assertNotNull(choice)
        requireNotNull(choice)
        assertEquals("248", choice.video.formatId)
        assertNotNull("a video with no audio is not playable", choice.audio)
        assertEquals(48000000L + 2900000L, choice.totalBytes)
        assertTrue(choice.needsMerge)
    }

    /** A height that is not on offer has no choice, rather than the nearest thing. */
    @Test
    fun `a height that is not offered has no choice`() {
        assertNull(chooseStream(parseStreamFormats(REAL), 2160))
    }

    /**
     * And the fact this whole file exists to record.
     *
     * A video with a format carrying both streams would make the no-ffmpeg paths live code
     * again. It is asserted so that when YouTube changes, this fails loudly instead of
     * quietly leaving dead fallbacks looking like safety nets.
     */
    @Test
    fun `no format on offer carries both streams`() {
        val formats = parseStreamFormats(REAL)
        assertFalse(
            "a single-file format appeared, so the no-ffmpeg paths are worth having again",
            hasAnyProgressiveFormat(formats)
        )
    }

    /** Garbage in, nothing out, rather than an exception from a download button. */
    @Test
    fun `a response that is not the expected shape reads as nothing`() {
        for (junk in listOf("", "not json", "<html>error</html>", """{"noFormats":1}""")) {
            assertTrue("accepted '$junk'", parseStreamFormats(junk).isEmpty())
        }
    }

    /** A format with neither stream is not a format. */
    @Test
    fun `a format with neither an audio nor a video stream is dropped`() {
        val json = """{"formats":[{"format_id":"1","ext":"mp4","vcodec":"none","acodec":"none"}]}"""
        assertTrue(parseStreamFormats(json).isEmpty())
    }
}
