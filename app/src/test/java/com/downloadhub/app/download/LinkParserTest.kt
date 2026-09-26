package com.downloadhub.app.download

import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    @Test
    fun extractsHttpsFromSharedText() {
        assertEquals(
            "https://example.com/file.zip",
            LinkParser.extractFirstLink("Download this: https://example.com/file.zip now")
        )
    }

    @Test
    fun trimsSentencePunctuationFromLink() {
        assertEquals(
            "https://example.com/video.mp4",
            LinkParser.extractFirstLink("See https://example.com/video.mp4.")
        )
    }

    @Test
    fun detectsMagnetAndYoutubeSources() {
        assertEquals(DownloadSource.TORRENT, LinkParser.sourceFor("magnet:?xt=urn:btih:abc"))
        assertEquals(
            DownloadSource.YOUTUBE,
            LinkParser.sourceFor("https://www.youtube.com/watch?v=abc123")
        )
        assertEquals(
            DownloadSource.YOUTUBE,
            LinkParser.sourceFor("https://youtu.be/abc123")
        )
    }

    @Test
    fun sanitizesUnsafeFileNames() {
        assertEquals("a_b_c_.mp4", LinkParser.sanitizeFileName(" a/b:c*.mp4 "))
        assertFalse(LinkParser.sanitizeFileName("...").isBlank())
    }

    @Test
    fun infersCategoriesFromExtensions() {
        assertEquals(
            DownloadCategory.VIDEO,
            LinkParser.categoryFor(DownloadSource.HTTP, "movie.mkv")
        )
        assertEquals(
            DownloadCategory.COMPRESSED,
            LinkParser.categoryFor(DownloadSource.TORRENT, "archive.zip")
        )
        assertEquals(
            DownloadCategory.AUDIO,
            LinkParser.categoryFor(DownloadSource.YOUTUBE, "song.m4a")
        )
    }

    @Test
    fun handlesEmptyAndMalformedLinks() {
        assertNull(LinkParser.extractFirstLink("not a link"))
        assertTrue(LinkParser.fileNameFrom("https://example.com/", mimeType = "application/pdf").endsWith(".pdf"))
    }
}
