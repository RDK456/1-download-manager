package com.downloadhub.app.ui

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import org.junit.Assert.assertEquals
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

    private fun entity(total: Long, downloaded: Long, percent: Int) = DownloadEntity(
        id = "test",
        source = DownloadSource.HTTP,
        url = "https://example.com/file",
        fileName = "file.bin",
        mimeType = null,
        category = DownloadCategory.OTHER,
        status = DownloadStatus.RUNNING,
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
