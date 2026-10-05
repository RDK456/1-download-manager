package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three small reports: the pane was always there, it showed tabs with nothing behind
 * them, and resuming something already finished re-downloaded it.
 */
class PaneAndResumeTest {

    /**
     * The pane belongs to the selection.
     *
     * It was drawn for whatever the list happened to contain when nothing was ticked, so
     * it was usually describing a download nobody had chosen - and on a queue of one it
     * was a permanent pane with nothing to say. It had briefly been given a fallback of
     * "the first torrent", which on the main list described a download that might not be
     * on screen at all.
     */
    @Test
    fun thePaneIsOnlyDrawnForASelectedRow() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val block = screen.substringAfter("// The bottom pane, but only when a download is actually selected.")
            .substringBefore("StatusBar(state, all)")
        assertTrue(
            "the pane's subject is the selection and nothing else:\n" +
                block.lines().take(6).joinToString("\n"),
            block.contains("all.firstOrNull { it.id in selected }")
        )
        assertTrue(
            "and it is only drawn when that found something",
            block.contains("if (detailItem != null) {")
        )
        assertFalse(
            "no falling back to the first row or the first torrent:\n" +
                block.lines().take(10).joinToString("\n"),
            block.contains("?: all.firstOrNull()") ||
                block.contains("?: visible.firstOrNull()") ||
                block.contains("DownloadSource.TORRENT }?.id")
        )
    }

    /**
     * A non-torrent gets Details; a torrent gets Details and Content.
     *
     * The other three tabs - Trackers, Peers and HTTP Sources - existed to say "this app
     * does not collect that yet", which is a fact about the app rather than about the
     * download. Four buttons out of five could only ever answer that, and on an ordinary
     * link Details is the entire story the pane has to tell.
     */
    @Test
    fun theTabsAreWhateverThereIsSomethingBehind() {
        assertEquals(
            listOf(TorrentTab.GENERAL),
            TorrentTab.forDownload(isTorrent = false)
        )
        assertEquals(
            listOf(TorrentTab.GENERAL, TorrentTab.CONTENT, TorrentTab.TRACKERS, TorrentTab.PEERS),
            TorrentTab.forDownload(isTorrent = true)
        )
        // The dead tabs are gone from the model, not merely hidden.
        // Trackers and Peers are back now that they read the live session; still torrents only.
        assertEquals(4, TorrentTab.entries.size)
        assertFalse(
            "a tab that can only ever say 'not collected yet' should not be in the strip",
            TorrentTab.entries.any { it.name in setOf("HTTP_SOURCES") }
        )
        assertEquals("Details", TorrentTab.GENERAL.label)
    }

    /**
     * The pane never shows a tab the current download has no button for.
     *
     * Ticking a torrent, opening Content, then ticking an ordinary download would
     * otherwise leave the file list on screen with no file list tab highlighted - a pane
     * displaying something with no way back out of it.
     */
    @Test
    fun theShownTabIsOneTheStripOffers() {
        TorrentTab.entries.forEach { wanted ->
            assertEquals(
                "a non-torrent showing $wanted has no tab to highlight",
                TorrentTab.GENERAL,
                TorrentTab.forDownload(isTorrent = false, wanted = wanted)
            )
            assertEquals(
                "a torrent keeps whatever it was showing",
                wanted,
                TorrentTab.forDownload(isTorrent = true, wanted = wanted)
            )
        }
        // And the caller has to actually use it, or the clamp is decoration.
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the tab handed to the pane must be the clamped one",
            screen.contains("tab = TorrentTab.forDownload(detailItem.isTorrent, detailTab)")
        )
        assertFalse(
            "and the tab row must be built from the same rule",
            screen.contains("TorrentTab.entries.forEach")
        )
    }

    /**
     * Resuming something already finished says so, and does not fetch it again.
     *
     * Resume handed the row straight to the engine, which restarted the transfer - so
     * resuming a download that had just finished re-fetched it from the beginning. From
     * the engine's point of view a download that has stopped is one that has been
     * paused, and nothing about the button said otherwise.
     */
    @Test
    fun resumeOnAFinishedDownloadSaysSoInsteadOfStartingItAgain() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val resume = controller.substringAfter("resume = { id ->")
            .substringBefore("stop = { id ->")
        assertTrue(
            "the finished case has to be checked at all:\n$resume",
            resume.contains("item?.status == DownloadStatus.COMPLETED")
        )
        // The answer in words moved to Retry: Resume is no longer offered for a finished
        // row, and a message on every Resume press was shown whatever was selected.
        val retry = controller.substringAfter("retry = { id ->").substringBefore("remove = { id ->")
        assertTrue(
            "Retry on a finished download says so, rather than fetching it again:\n$retry",
            retry.contains("already finished") && retry.contains("item.location")
        )
        assertTrue(
            "and checks before handing anything to an engine",
            retry.indexOf("engine.retry") > retry.indexOf("DownloadStatus.COMPLETED")
        )

        // Nothing may be handed to an engine before that check.
        assertTrue(
            "the torrent must not be resumed before the check",
            resume.indexOf("torrents.resume") > resume.indexOf("DownloadStatus.COMPLETED")
        )
        assertTrue(
            "and neither must an ordinary download",
            resume.indexOf("engine.resume") > resume.indexOf("DownloadStatus.COMPLETED")
        )
    }

    /**
     * Start Queue is not the same thing and must not be caught by the guard.
     *
     * It only ever picked paused and failed rows, so a finished download was never at
     * risk from it - but it goes through a different action, and a guard that grew to
     * cover it would stop it doing its job.
     */
    @Test
    fun startQueueStillStartsWhatWasPaused() {
        val engine = File("src/main/kotlin/com/downloadhub/desktop/DownloadEngine.kt").readText()
        val resumeAll = engine.substringAfter("fun resumeAll()").take(320)
        assertTrue(
            "it should pick paused and failed rows:\n$resumeAll",
            resumeAll.contains("DownloadStatus.PAUSED") && resumeAll.contains("DownloadStatus.FAILED")
        )
        assertFalse(
            "and never a finished one, which is what would turn Start Queue into a " +
                "re-download of everything",
            resumeAll.contains("COMPLETED")
        )
    }

    /** The same on Android, which has its own resume and its own copy of the bug. */
    @Test
    fun androidResumesNothingThatIsAlreadyFinished() {
        val viewModel = File("../app/src/main/java/com/downloadhub/app/ui/DownloadViewModel.kt").readText()
        val resume = viewModel.substringAfter("fun resume(id: String) {").take(900)
        assertTrue(
            "Android checks the status first:\n$resume",
            resume.contains("DownloadStatus.COMPLETED")
        )
        assertTrue("and answers in words", resume.contains("already finished"))
        assertTrue(
            "before sending the service an action",
            resume.indexOf("ACTION_RESUME") > resume.indexOf("DownloadStatus.COMPLETED")
        )
    }
}
