package com.downloadhub.app.ui

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentCategoryTest {
    @Test
    fun classifiesTorrentByItsNameWhenNothingIsPublished() {
        // A disk image maps to Compressed, so the name alone already tells us more
        // than "unknown file".
        val item = torrent(fileName = "Ubuntu 24.04 desktop amd64.iso", outputPath = null)
        assertEquals(DownloadCategory.COMPRESSED, classifyTorrent(item))
    }

    @Test
    fun classifiesTorrentByNameWhenTheNameSaysVideo() {
        val item = torrent(fileName = "Big Buck Bunny (2008).mkv", outputPath = null)
        assertEquals(DownloadCategory.VIDEO, classifyTorrent(item))
    }

    @Test
    fun unknownTorrentsFallBackToFiles() {
        val item = torrent(fileName = "some.release", outputPath = null)
        assertEquals(DownloadCategory.FILE, classifyTorrent(item))
    }

    @Test
    fun countCategoriesGroupsPerCategory() {
        val rows = listOf("a.mkv", "b.mkv", "notes.txt").mapIndexed { index, name ->
            val base = torrent(name)
            base.copy(id = index.toString(), category = classifyTorrent(base))
        }
        val counts = rows.countCategories { it.source == DownloadSource.TORRENT }
        assertEquals(2, counts[DownloadCategory.VIDEO])
        assertEquals(1, counts[DownloadCategory.DOCUMENT])
        assertTrue(rows.countCategories { it.source != DownloadSource.TORRENT }.isEmpty())
    }
    private fun torrent(fileName: String, outputPath: String? = null) = DownloadEntity(
        id = "t",
        source = DownloadSource.TORRENT,
        url = "file:///torrents/x.torrent",
        fileName = fileName,
        mimeType = null,
        category = DownloadCategory.FILE,
        status = DownloadStatus.COMPLETED,
        bytesDownloaded = 0,
        totalBytes = 0,
        progressPercent = 100,
        speedBytesPerSecond = 0,
        etaSeconds = -1,
        outputPath = outputPath,
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
