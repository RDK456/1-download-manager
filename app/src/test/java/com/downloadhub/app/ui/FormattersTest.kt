package com.downloadhub.app.ui

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.label
import com.downloadhub.app.download.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {
    @Test
    fun formatsByteValues() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 KB", formatBytes(1000))
        assertTrue(formatBytes(1_500_000).contains("MB"))
    }

    @Test
    fun calculatesProgressFromKnownTotal() {
        val item = entity(total = 200, downloaded = 50, percent = 0)
        assertEquals(0.25f, progressFor(item))
        assertEquals("25%", progressLabel(item))
    }

    @Test
    fun usesReportedPercentWhenTotalIsUnknown() {
        val item = entity(total = 0, downloaded = 0, percent = 42)
        assertEquals(0.42f, progressFor(item))
        assertEquals("42%", progressLabel(item))
    }

    @Test
    fun completedDownloadsReadAsCompleted() {
        assertEquals("Completed", statusLabel(DownloadStatus.COMPLETED))
        val item = entity(total = 100, downloaded = 100, percent = 100, status = DownloadStatus.COMPLETED)
        assertEquals(1f, progressFor(item))
        assertEquals("100%", progressLabel(item))
    }

    @Test
    fun detectsCommonFileTypes() {
        fun categoryOf(name: String, mime: String? = null) =
            LinkParser.categoryFor(DownloadSource.HTTP, name, mime)

        assertEquals(DownloadCategory.PROGRAM, categoryOf("setup.exe"))
        assertEquals(DownloadCategory.PROGRAM, categoryOf("app.apk"))
        assertEquals(DownloadCategory.PROGRAM, categoryOf("binary", "application/x-msdownload"))
        assertEquals(DownloadCategory.COMPRESSED, categoryOf("photos.zip"))
        assertEquals(DownloadCategory.COMPRESSED, categoryOf("backup.7z"))
        assertEquals(DownloadCategory.AUDIO, categoryOf("song.mp3"))
        assertEquals(DownloadCategory.VIDEO, categoryOf("clip.mkv"))
        assertEquals(DownloadCategory.IMAGE, categoryOf("photo.webp"))
        assertEquals(DownloadCategory.DOCUMENT, categoryOf("report.pdf"))
        assertEquals(DownloadCategory.FILE, categoryOf("notes.bin"))
    }

    @Test
    fun usesMimeTypeWhenNameHasNoUsefulExtension() {
        assertEquals(
            DownloadCategory.PROGRAM,
            LinkParser.categoryFor(DownloadSource.HTTP, "setup", "application/vnd.android.package-archive")
        )
        assertEquals(
            DownloadCategory.COMPRESSED,
            LinkParser.categoryFor(DownloadSource.HTTP, "bundle", "application/zip")
        )
    }

    @Test
    fun legacyArchiveCategoryStillReadsAsCompressed() {
        assertEquals("Compressed", DownloadCategory.ARCHIVE.label)
        assertEquals("Compressed", DownloadCategory.COMPRESSED.label)
    }

    @Test
    fun parsesMediaChoices() {
        assertEquals(MediaQuality.Q1080, MediaQuality.fromValue("1080"))
        assertEquals(MediaQuality.AUDIO, MediaQuality.fromValue("audio"))
        assertEquals(MediaQuality.BEST, MediaQuality.fromValue("nonsense"))
        assertTrue(MediaQuality.AUDIO.isAudioOnly)
        assertFalse(MediaQuality.BEST.isAudioOnly)

        assertEquals(AudioFormat.MP3, AudioFormat.fromValue("mp3"))
        assertEquals(AudioFormat.M4A, AudioFormat.fromValue(null))
        assertEquals("opus", AudioFormat.OPUS.value)
    }

    @Test
    fun recognizesTorrentPayloads() {
        assertTrue(LinkParser.looksLikeTorrent("ubuntu.torrent"))
        assertTrue(LinkParser.looksLikeTorrent("mystery.bin", "application/x-bittorrent"))
        assertTrue(LinkParser.looksLikeTorrent("/storage/emulated/0/Download/x.torrent"))
        assertFalse(LinkParser.looksLikeTorrent("notes.txt", "text/plain"))
    }

    @Test
    fun detectsSourceFromLink() {
        assertEquals(DownloadSource.TORRENT, LinkParser.sourceFor("magnet:?xt=urn:btih:abc"))
        assertEquals(DownloadSource.TORRENT, LinkParser.sourceFor("https://host/debian.torrent"))
        assertEquals(DownloadSource.YOUTUBE, LinkParser.sourceFor("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(DownloadSource.YOUTUBE, LinkParser.sourceFor("https://www.youtube.com/watch?v=abc"))
        assertEquals(DownloadSource.HTTP, LinkParser.sourceFor("https://host/file.zip"))
    }

    private fun entity(
        total: Long,
        downloaded: Long,
        percent: Int,
        status: DownloadStatus = DownloadStatus.RUNNING
    ) = DownloadEntity(
        id = "test",
        source = DownloadSource.HTTP,
        url = "https://example.com/file",
        fileName = "file.bin",
        mimeType = null,
        category = DownloadCategory.FILE,
        status = status,
        bytesDownloaded = downloaded,
        totalBytes = total,
        progressPercent = percent,
        speedBytesPerSecond = 0,
        etaSeconds = -1,
        outputPath = null,
        torrentFilePath = null,
        torrentInfoHash = null,
        userAgent = null,
        contentDisposition = null,
        etag = null,
        lastModified = null,
        errorMessage = null,
        createdAt = 0,
        updatedAt = 0
    )
}
