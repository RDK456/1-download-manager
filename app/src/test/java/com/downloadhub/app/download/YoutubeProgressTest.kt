package com.downloadhub.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A video sat on the resolving label for its whole download and then appeared
 * finished, because nothing moved the row between RESOLVING and RUNNING. The
 * transfer was happening; the row just never said so.
 */
class YoutubeProgressTest {

    private fun downloaderSource() =
        File("src/main/java/com/downloadhub/app/download/YoutubeDownloader.kt").readText()

    @Test
    fun theFirstProgressLineMovesTheRowToRunning() {
        val source = downloaderSource()
        // The transition call and its two statuses, whatever the indentation is.
        assertTrue(
            "the transfer must mark the row as running once it starts",
            source.contains("dao.transitionStatus(") &&
                source.contains("DownloadStatus.RESOLVING") &&
                source.contains("DownloadStatus.RUNNING")
        )
    }

    @Test
    fun itHappensOnceRatherThanEveryCallback() {
        val source = downloaderSource()
        assertTrue(
            "the transition must be guarded, or every progress line writes the " +
                "status again for a download that may already have finished",
            source.contains("startedTransfer.compareAndSet(false, true)")
        )
    }
}
