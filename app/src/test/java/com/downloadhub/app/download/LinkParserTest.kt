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
    fun recognisesAWideRangeOfMediaContainers() {
        fun categoryOf(name: String) = LinkParser.categoryFor(DownloadSource.HTTP, name)

        // Video containers beyond the usual mp4/webm.
        listOf("mkv", "mov", "avi", "wmv", "flv", "m2ts", "ogv", "vob", "mxf", "3gp", "divx")
            .forEach { assertEquals("video: $it", DownloadCategory.VIDEO, categoryOf("clip.$it")) }

        // Audio containers.
        listOf("mp3", "m4a", "aac", "flac", "opus", "wma", "aiff", "ape", "mka")
            .forEach { assertEquals("audio: $it", DownloadCategory.AUDIO, categoryOf("track.$it")) }

        // Installers and binaries.
        listOf("exe", "msi", "apk", "deb", "rpm", "dmg", "appimage", "jar", "nupkg", "so")
            .forEach { assertEquals("program: $it", DownloadCategory.PROGRAM, categoryOf("setup.$it")) }

        // Documents and subtitles.
        listOf("pdf", "epub", "srt", "vtt", "ass", "sqlite", "yaml")
            .forEach { assertEquals("doc: $it", DownloadCategory.DOCUMENT, categoryOf("note.$it")) }
    }

    @Test
    fun keepsUnknownFirmwareAndImagesInSensibleBuckets() {
        fun categoryOf(name: String) = LinkParser.categoryFor(DownloadSource.HTTP, name)
        assertEquals(DownloadCategory.FILE, categoryOf("firmware.bin"))
        assertEquals(DownloadCategory.IMAGE, categoryOf("photo.avif"))
        assertEquals(DownloadCategory.IMAGE, categoryOf("photo.cr2"))
    }

    @Test
    fun handlesEmptyAndMalformedLinks() {
        assertNull(LinkParser.extractFirstLink("not a link"))
        assertTrue(LinkParser.fileNameFrom("https://example.com/", mimeType = "application/pdf").endsWith(".pdf"))
    }
}
