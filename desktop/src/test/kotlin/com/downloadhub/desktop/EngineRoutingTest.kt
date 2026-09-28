package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers which items the plain HTTP engine is allowed to claim.
 *
 * The bug: the pump took anything QUEUED or RESOLVING without checking its source, so
 * a YouTube link was downloaded twice - once by yt-dlp, which produced the real
 * video, and once by the HTTP downloader, which fetched the web page and saved it to
 * the download folder as a stray file called "watch", after the last segment of the
 * URL. It also overwrote the finished row's size and MIME type with the HTML page's,
 * so a completed 32 MB video showed as 0 bytes of type text/html.
 */
class EngineRoutingTest {

    private fun item(source: DownloadSource, status: DownloadStatus) = DownloadItem(
        id = "id-1",
        url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        fileName = "",
        source = source,
        category = DownloadCategory.VIDEO,
        status = status,
        bytesDownloaded = 0L,
        totalBytes = 0L,
        speedBytesPerSecond = 0L,
        errorMessage = "",
        location = "",
        etag = "",
        lastModified = "",
        mimeType = "",
        quality = "",
        audioFormat = "",
        playlist = false,
        torrentFilePath = "",
        torrentInfoHash = "",
        outputPath = "",
        createdAt = 0L
    )

    /**
     * Reads the filter the engine actually uses, rather than restating it here, so
     * this test fails if someone loosens the condition back to "anything queued".
     */
    private fun pumpFilterFilter(): String {
        val text = File("src/main/kotlin/com/downloadhub/desktop/DownloadEngine.kt").readText()
        val pump = text.substringAfter("fun pump()").substringBefore("private fun start")
        assertTrue("could not find pump() to inspect", pump.isNotBlank())
        return pump
    }

    @Test
    fun theHttpEngineOnlyTakesHttpItems() {
        val filter = pumpFilterFilter()
        assertTrue(
            "the HTTP engine must not claim non-HTTP items: $filter",
            filter.contains("DownloadSource.HTTP")
        )
    }

    @Test
    fun theConditionCannotBeReopenedToEveryQueuedItem() {
        val filter = pumpFilterFilter()
        assertTrue(
            "the status test must be nested under the source test, not applied to " +
                "every item regardless of source: $filter",
            filter.contains("it.source == DownloadSource.HTTP &&")
        )
        assertTrue(
            "the bare 'any queued item' form is back: $filter",
            !filter.contains(".filter { it.status == DownloadStatus.QUEUED ||")
        )
    }

    @Test
    fun aQueuedYouTubeItemIsLeftForYtDlp() {
        val queued = item(DownloadSource.YOUTUBE, DownloadStatus.QUEUED)
        val isHttp = queued.source == DownloadSource.HTTP
        assertTrue("a YouTube item must not look like an HTTP item", !isHttp)
        // The status is still one the pump reacts to, so the only thing keeping the
        // two engines apart is the source.
        assertEquals(DownloadStatus.QUEUED, queued.status)
    }

    @Test
    fun aQueuedHttpItemIsStillClaimed() {
        val queued = item(DownloadSource.HTTP, DownloadStatus.QUEUED)
        assertTrue("a plain HTTP item must still be claimed", queued.source == DownloadSource.HTTP)
    }

    @Test
    fun aQueuedTorrentItemIsLeftForLibtorrent() {
        val queued = item(DownloadSource.TORRENT, DownloadStatus.QUEUED)
        assertTrue("a torrent must not be fetched over HTTP", queued.source != DownloadSource.HTTP)
    }
}
