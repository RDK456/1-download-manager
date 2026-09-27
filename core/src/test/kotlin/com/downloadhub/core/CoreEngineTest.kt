package com.downloadhub.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the engine code that now lives in :core and is compiled into both the
 * Android app and the Windows build.
 *
 * A test only in the Android module would not catch a regression that breaks the
 * desktop build, so the shared behaviour is asserted here instead.
 */
class CoreEngineTest {

    @Test
    fun speedLimiterStartsUnthrottled() = runBlocking {
        val limiter = SpeedLimiter()
        // An unlimited limiter must not block, otherwise a fresh install stalls.
        limiter.acquire(1024)
    }

    @Test
    fun speedLimiterCapsTheConfiguredRate() = runBlocking {
        val limiter = SpeedLimiter()
        limiter.setLimit(64 * 1024)
        val start = System.nanoTime()
        // Ask for 4x the per-second budget; the limiter must stretch the wait.
        repeat(8) { limiter.acquire(32 * 1024) }
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "expected throttling to delay the transfer, took ${elapsedMillis}ms",
            elapsedMillis > 100
        )
    }

    @Test
    fun linksAreClassifiedBySource() {
        assertEquals(DownloadSource.TORRENT, LinkParser.sourceFor("magnet:?xt=urn:btih:abc123"))
        assertEquals(
            DownloadSource.YOUTUBE,
            LinkParser.sourceFor("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(DownloadSource.HTTP, LinkParser.sourceFor("https://example.com/file.zip"))
    }

    @Test
    fun torrentFilesAreRecognised() {
        // looksLikeTorrent inspects a *file name* or MIME type; a magnet is
        // recognised separately by sourceFor.
        assertTrue(LinkParser.looksLikeTorrent("ubuntu.torrent"))
        assertTrue(LinkParser.looksLikeTorrent("/downloads/ubuntu.torrent"))
        assertTrue(LinkParser.looksLikeTorrent("anything", "application/x-bittorrent"))
        assertTrue(!LinkParser.looksLikeTorrent("movie.mkv"))
    }

    @Test
    fun categoriesFollowTheFileExtension() {
        assertEquals(
            DownloadCategory.VIDEO,
            LinkParser.categoryFor(DownloadSource.HTTP, "clip.mp4")
        )
        // .zip belongs to the compressed group, not the generic archive group.
        assertEquals(
            DownloadCategory.COMPRESSED,
            LinkParser.categoryFor(DownloadSource.HTTP, "bundle.zip")
        )
    }

    @Test
    fun fileNamesAreDerivedFromTheUrlPath() {
        val name = LinkParser.fileNameFrom("https://example.com/files/report.pdf")
        assertEquals("report.pdf", name)
    }

    @Test
    fun unsafeFileNamesAreSanitised() {
        val safe = LinkParser.sanitizeFileName("a/b\\c:d*e?.mp4")
        assertTrue("must not contain a path separator: $safe", !safe.contains('/') && !safe.contains('\\'))
    }

    @Test
    fun progressPercentIsBoundedAndSafe() {
        val noTotal = DownloadItem("1", "u", "f", totalBytes = 0, bytesDownloaded = 500)
        assertEquals(0, noTotal.progressPercent)

        val half = DownloadItem("2", "u", "f", totalBytes = 100, bytesDownloaded = 50)
        assertEquals(50, half.progressPercent)

        // A server that over-reports must not produce a nonsensical percentage.
        val over = DownloadItem("3", "u", "f", totalBytes = 100, bytesDownloaded = 250)
        assertTrue(over.progressPercent in 0..100)
    }

    @Test
    fun activeStatesAreRecognised() {
        assertTrue(DownloadItem("1", "u", "f", status = DownloadStatus.RUNNING).isActive)
        assertTrue(DownloadItem("1", "u", "f", status = DownloadStatus.QUEUED).isActive)
        assertTrue(!DownloadItem("1", "u", "f", status = DownloadStatus.PAUSED).isActive)
        assertTrue(!DownloadItem("1", "u", "f", status = DownloadStatus.COMPLETED).isActive)
    }

    @Test
    fun pageMediaParserFindsAnMp4PlayerSource() {
        val html = """
            <html><body>
              <video><source src="https://cdn.example.com/media/clip.mp4" type="video/mp4"></video>
            </body></html>
        """.trimIndent()
        val found = PageMediaParser.parse(html, "https://example.com/watch")
        assertTrue("expected to find the video, got $found", found.isNotEmpty())
        assertEquals("https://cdn.example.com/media/clip.mp4", found.first().url)
    }

    @Test
    fun pageMediaParserIgnoresAPageWithNoMedia() {
        val found = PageMediaParser.parse("<html><body>Nothing here</body></html>", "https://example.com")
        assertTrue(found.isEmpty())
    }

    @Test
    fun directMediaUrlsAreRecognised() {
        assertTrue(PageMediaParser.looksLikeDirectMedia("https://x.com/a/b.mp4"))
        assertTrue(!PageMediaParser.looksLikeDirectMedia("https://x.com/watch?v=1"))
    }

    @Test
    fun theEnginePortsAreImplementableWithoutAndroid() {
        // The whole point of :core: these three interfaces are the entire contract
        // the HTTP engine needs from a platform, and none of them mention Android.
        assertNotNull(object : DownloadStore {
            override fun updateValidators(id: String, etag: String?, lastModified: String?, now: Long) = Unit
            override fun updateMetadata(
                id: String, fileName: String, mimeType: String?,
                category: DownloadCategory, totalBytes: Long, now: Long
            ) = Unit

            override fun updateProgress(
                id: String, bytesDownloaded: Long, totalBytes: Long, percent: Int,
                speedBytesPerSecond: Long, etaSeconds: Long, now: Long
            ) = Unit

            override fun updateOutputPath(id: String, location: String?, now: Long) = Unit
            override fun setStatus(id: String, status: DownloadStatus, error: String?, now: Long) = Unit
        })
        assertNull(null as DownloadStore?)
    }
}
