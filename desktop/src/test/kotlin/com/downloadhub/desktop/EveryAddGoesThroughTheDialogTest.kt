package com.downloadhub.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every route into the app has to reach the pre-download dialog.
 *
 * The reported bug: clicking a magnet link in the browser added it to the queue and no
 * dialog ever appeared. Two separate causes, both fixed here, and both of the same kind -
 * a route that went straight to the queue because nobody thought to check it.
 *
 *   - `acceptCapturedLink` called `addDownload` itself. The browser extension had no
 *     path to the dialog at all.
 *   - `queueTargets` only reviewed a `target.torrentFile != null`. A magnet has no file,
 *     so the ordinary way to start a torrent in 2026 - clicking a magnet link in a
 *     magnet-aware browser - was the one way that skipped it.
 *
 * The tests below are mostly about the *shape* of the routing rather than its behaviour,
 * because the behaviour is only reachable with a window, and a window is exactly what
 * was missing when this went wrong. A missing `else` branch and a missing callback read
 * identically in a log and very differently on screen.
 */
class EveryAddGoesThroughTheDialogTest {

    private val controller =
        File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
    private val window = File("src/main/kotlin/com/downloadhub/desktop/Main.kt").readText()

    /** Comments say what a thing should do; only code says what it does. */
    private fun codeOnly(source: String): String = source
        .lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private fun bodyOf(source: String, signature: String, until: String): String =
        codeOnly(source).substringAfter(signature).substringBefore(until)

    @Test
    fun aLinkFromTheBrowserIsReviewedRatherThanQueuedDirectly() {
        val body = bodyOf(
            controller,
            "private fun acceptCapturedLink(",
            "val capturePort: Int"
        )
        assertTrue(
            "the browser route must go to the dialog:\n$body",
            body.contains("onDownloadNeedsReview")
        )
        assertTrue(
            "and it must hand the link over, not a file - a magnet has no file, which is " +
                "why it was being skipped:\n$body",
            body.contains("review(request.url)")
        )
    }

    @Test
    fun theDirectQueueCallInTheBrowserRouteIsOnlyTheFallback() {
        val body = bodyOf(
            controller,
            "private fun acceptCapturedLink(",
            "val capturePort: Int"
        )
        // `addDownload` may still be there, but only after the callback has been found to
        // be null. If it is reachable without that check, a browser link is queued unseen
        // whenever the window is not up.
        val reviewAt = body.indexOf("onDownloadNeedsReview")
        val addAt = body.indexOf("addDownload(")
        assertTrue("the browser route no longer mentions the dialog at all", reviewAt >= 0)
        assertTrue(
            "addDownload is called before the dialog is offered, so the dialog is " +
                "decorative:\n$body",
            addAt > reviewAt
        )
        assertTrue(
            "and the fallback is not unconditional - it has to be a null check on the " +
                "callback, so that it only applies before the window exists:\n$body",
            body.contains("if (review != null)")
        )
    }

    @Test
    fun aMagnetArrivingFromAFileIsReviewedToo() {
        val body = bodyOf(
            controller,
            "fun queueTargets(",
            "private suspend fun runYtDlp("
        )
        assertTrue(
            "queueTargets must offer every target to the dialog:\n$body",
            body.contains("onDownloadNeedsReview")
        )
        // The old shape was `if (target.torrentFile != null)`, which quietly excluded
        // every magnet. The fix sends whichever of the two there is.
        assertTrue(
            "a magnet carries no file, so the file path alone cannot be what is sent:\n$body",
            body.contains("target.torrentFile ?: target.link")
        )
        assertTrue(
            "and there must no longer be a torrent-file-only early return:\n$body",
            !codeOnly(controller).contains("if (target.torrentFile != null) {")
        )
    }

    @Test
    fun theCallbackCarriesALinkRatherThanAFile() {
        // A magnet has no File behind it. A callback typed to take one cannot be given a
        // magnet at all, which is the whole reason this went wrong.
        assertTrue(
            "the review callback must take a link, not a file",
            controller.contains("var onDownloadNeedsReview: ((String) -> Unit)?")
        )
        assertTrue(
            "and the old file-typed callback must be gone, not merely unused",
            !controller.contains("var onTorrentNeedsReview")
        )
    }

    @Test
    fun theWindowShowsTheDialogForWhateverTheCallbackIsGiven() {
        val block = window.substringAfter("controller.onDownloadNeedsReview = { link ->")
            .substringBefore("controller.queueTargets(startupTargets)")
        assertTrue(
            "the window must build a PendingDownload from the link it is handed:\n$block",
            block.contains("PendingDownload.forLink(link)")
        )
        assertTrue(
            "and raise itself either way, so a link that cannot be read still says so",
            block.contains("showWindow()")
        )
    }

    @Test
    fun aMagnetIsAcceptedByTheCaptureLayerInTheFirstPlace() {
        // Checked because it is the layer below: had it been rejecting magnets, fixing
        // the routing above would have changed nothing at all.
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=ubuntu"
        assertEquals(
            "a magnet must survive capture, or there is nothing for the dialog to show",
            magnet,
            CaptureRules.normaliseLink(magnet)
        )
        // And the dialog can turn it into something queueable.
        val pending = PendingDownload.forLink(magnet)
        assertTrue("a magnet must reach the dialog", pending != null)
        assertTrue(pending!!.isTorrent)
        assertEquals("ubuntu", pending.name)
    }

    @Test
    fun aLinkThatIsNotADownloadIsRefusedBeforeTheDialogRatherThanAfter() {
        // The dialog is not the place to find out something is undownloadable; the point
        // of routing everything through it is that it *can* be a magnet, which used to
        // mean it could not also be a refusal point.
        assertEquals(
            null,
            PendingDownload.forLink("[Judas] Chainsaw Man (Season 1) [1080p]")
        )
    }

    @Test
    fun nothingAnnouncesAnAdditionThatIsStillWaitingBehindTheDialog() {
        val body = bodyOf(controller, "fun queueTargets(", "private suspend fun runYtDlp(")
        assertTrue(
            "a download queued into a dialog has not been added yet, and saying it has " +
                "reads as though it were running",
            body.contains("if (added == 0 && reviewed > 0) return")
        )
    }
}
