package com.downloadhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the two ways a finished YouTube download used to look wrong.
 *
 * Both were only visible in the running app, and both had the same shape: the work
 * succeeded, the file was correct on disk, and the row in the list still lied.
 */
class YouTubeCompletionTest {

    private fun controllerSource() = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()

    private fun runYtDlpBody(): String {
        val text = controllerSource()
        val body = text.substringAfter("private suspend fun runYtDlp")
            .substringBefore("fun chooseFolder")
        assertTrue("could not find runYtDlp to inspect", body.isNotBlank())
        return body
    }

    /**
     * publishFile renames the file, so `first.length()` read after the call is 0.
     * A finished 32 MB video showed as 0 bytes in the list until the size was
     * captured first.
     */
    @Test
    fun theSizeIsReadBeforeTheFileIsMovedAway() {
        val body = runYtDlpBody()
        val read = body.indexOf("val size = first.length()")
        val publish = body.indexOf("area.publishFile(")
        assertTrue(
            "the size must be captured before publishFile, which renames the file; " +
                "read afterwards it is always 0",
            read >= 0 && publish >= 0 && read < publish
        )
    }

    @Test
    fun theCompletedRowCarriesThatSize() {
        val body = runYtDlpBody()
        assertTrue(
            "the completed row must record the real size",
            body.contains("bytesDownloaded = size") && body.contains("totalBytes = size")
        )
    }

    /**
     * yt-dlp reports a percentage, not a byte count, and the total is unknown until
     * the file exists. A progress line that lands after the job finished would
     * therefore write 0 over the real size.
     */
    @Test
    fun aProgressLineCannotOverwriteAFinishedDownload() {
        val body = runYtDlpBody()
        // The guard has to allow both live states now - RESOLVING and RUNNING are
        // both "in flight" - and nothing else. Matched on the status pair rather
        // than on one line, because the condition is two lines and wrapping it
        // differently is not a behaviour change.
        val guarded = body.contains(
            "current.status != DownloadStatus.RESOLVING &&"
        ) && body.contains(
            "current.status != DownloadStatus.RUNNING"
        )
        assertTrue(
            "a late progress update must not clobber the completed row: $body",
            guarded
        )
    }

    /**
     * The Android build does this correctly in YoutubeDownloader.kt. The two
     * implementations are ports of each other, so the desktop one has to agree -
     * this is what caught the original regression.
     */
    @Test
    fun theAndroidBuildCapturesTheSizeTheSameWay() {
        val android = File("../app/src/main/java/com/downloadhub/app/YoutubeDownloader.kt")
        org.junit.Assume.assumeTrue("Android sources are not present here", android.isFile)
        val text = android.readText()
        val read = text.indexOf("completedFile.length()")
        val publish = text.indexOf("storage.publishFile(")
        assertTrue(
            "Android must keep reading the size before publishing",
            read >= 0 && publish >= 0 && read < publish
        )
    }

    /** The stray file this bug produced: the watch page, saved as "watch". */
    @Test
    fun theHttpEngineCannotClaimAYouTubeItem() {
        val engine = File("src/main/kotlin/com/downloadhub/desktop/DownloadEngine.kt").readText()
        val pump = engine.substringAfter("fun pump()").substringBefore("private fun start")
        assertTrue(
            "the HTTP pump must filter on source, or it saves the watch page as a file",
            pump.contains("it.source == DownloadSource.HTTP &&")
        )
        assertFalse(
            "the old 'any queued item' filter is back",
            pump.contains(".filter { it.status == DownloadStatus.QUEUED ||")
        )
    }
}
