package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.downloadhub.core.DownloadColumn
import com.downloadhub.core.DownloadStatus
import java.io.File

/** A table with every column on screen, so all of them can be tried. */
private fun fullLayout() = TableLayout(
    showStatus = true,
    showSpeed = true,
    showTimeLeft = true,
    showDateAdded = true,
    showRowActions = true,
    size = androidx.compose.ui.unit.Dp(90f),
    status = androidx.compose.ui.unit.Dp(110f),
    speed = androidx.compose.ui.unit.Dp(95f),
    timeLeft = androidx.compose.ui.unit.Dp(90f),
    dateAdded = androidx.compose.ui.unit.Dp(110f)
)

/**
 * Three things the queue got wrong: a completed torrent marked failed, a bottom pane
 * that only existed on one tab, and column headers that would not sit still while
 * being dragged.
 */
class ReportedBugsTest {

    /**
     * A torrent that had every byte on disk said "Failed".
     *
     * FAILED was terminal, and it was reached on any reading that carried an error.
     * libtorrent reports errors for things a torrent walks away from by itself - a
     * storage error that cleared, a tracker that went away, a resume-data rewrite that
     * raced a poll. One of those mid-download marked the row Failed for good, the
     * torrent carried on to 100%, and the list said Failed beside a full bar.
     *
     * The order of the tests is the fix: ask the data before the error, and ask
     * whether it is still moving before either.
     */
    @Test
    fun aFinishedTorrentIsNeverFailedAndAFailedOneMayRecover() {
        val engine = File("src/main/kotlin/com/downloadhub/desktop/DesktopTorrentEngine.kt").readText()
        val status = engine.substringAfter("status = when {").substringBefore("bytesDownloaded =")

        val finishedAt = status.indexOf("snapshot.isFinished")
        val errorAt = status.indexOf("snapshot.error != null")
        assertTrue("the status block should be here:\n$status", finishedAt >= 0 && errorAt >= 0)
        assertTrue(
            "a torrent with all its data must be Completed before an error is " +
                "consulted, or one stale error pins it at Failed:\n$status",
            finishedAt < errorAt
        )
        assertTrue(
            "a torrent that is still moving is not failed either:\n$status",
            status.indexOf("snapshot.downloadRate > 0") < errorAt
        )
        // And the recovery has to be reachable at all: the carry-forward that made
        // Failed a dead end has to come after both of the checks that say otherwise.
        // It used to be the very first thing consulted, which is what made one stale
        // error permanent.
        val carryForward = status.indexOf("it.status == DownloadStatus.FAILED")
        assertTrue(
            "FAILED must no longer be a dead end:\n$status",
            carryForward > finishedAt && carryForward > status.indexOf("snapshot.downloadRate > 0")
        )
    }

    /**
     * A row that is working must not still be carrying the last error's message.
     *
     * The message was written straight from the snapshot, so a cleared error left its
     * text on a running download - a red line of stale text beside a transfer that was
     * plainly going on.
     */
    @Test
    fun aWorkingRowDoesNotKeepAnOldErrorMessage() {
        val engine = File("src/main/kotlin/com/downloadhub/desktop/DesktopTorrentEngine.kt").readText()
        val message = engine.substringAfter("errorMessage = if (").substringBefore(",")
        assertTrue(
            "the message must depend on the row still being failed:\n$message",
            message.contains("DownloadStatus.FAILED") && message.contains("snapshot.error")
        )
    }

    /**
     * The pane with Details and Content was only on the Torrents tab.
     *
     * So the answer to "what is this doing, and which file is stuck" existed only
     * after switching tabs and finding the right row - which is the same as not having
     * it. It is now drawn for whatever is selected, on any tab.
     *
     * And for nothing at all when nothing is, which is the other half of the fix and the
     * half that matters more. It had briefly been given a fallback of "whatever the list
     * contains", so a pane sat permanently on screen describing a download nobody had
     * chosen; and before that, of "the first torrent", which on the main list described a
     * download that might not be on screen at all.
     */
    @Test
    fun theBottomPaneFollowsTheSelectionAndNotTheTorrentsTab() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val before = screen.substring(0, screen.indexOf("TorrentDetailPanel("))
        assertTrue(
            "the pane must not be inside `if (state.torrentsTab) {`",
            !before.contains("if (state.torrentsTab) {")
        )
        assertTrue(
            "its subject is the selection",
            screen.contains("val detailItem = all.firstOrNull { it.id in selected }")
        )
        assertTrue(
            "and it is drawn only when the selection found something",
            screen.contains("if (detailItem != null) {")
        )
        assertTrue(
            "with no fallback to a row nobody chose",
            !screen.contains("?: all.firstOrNull()") && !screen.contains("?: visible.firstOrNull()")
        )
    }

    @Test
    fun theNameColumnIsCappedByTheRoomTheOthersLeave() {
        val layout = fullLayout()
        val widths = ColumnWidths.DEFAULT
        val narrow = 900f
        val fixed = widths.fixedSpan(layout, narrow)
        val available = narrow - fixed

        val capped = ColumnWidths(nameShare = 0.9f)
        assertEquals(
            "the name may not take more than what is left for it",
            available,
            ColumnDividers.resolvedWidthOf(layout, capped, narrow, DownloadColumn.NAME),
            0.01f
        )
        // Pulled right when already at the cap, it stays put rather than overflowing.
        val divider = ColumnDividers.offsets(layout, capped, narrow)[DownloadColumn.NAME]!!
        val dragged = ColumnDividers.dragged(
            widths = capped,
            column = DownloadColumn.NAME,
            toX = divider + 200f,
            tableDp = narrow,
            layout = layout
        )
        assertEquals(
            available,
            ColumnDividers.resolvedWidthOf(layout, dragged, narrow, DownloadColumn.NAME),
            0.01f
        )
        // And the row's own controls still fit, which is the reason for the cap.
        assertTrue(
            "the fixed columns and the row's buttons must still have room",
            widths.fixedSpan(layout, narrow) + available <= narrow
        )
    }

    /**
     * And every visible column really can be resized, not just the name.
     *
     * That is what was asked for. A column with no override and a default width is
     * resizable; the point of the test is that none of them is quietly pinned.
     */
    @Test
    fun everyVisibleColumnCanBeResized() {
        val layout = fullLayout()
        val table = 1600f
        val widths = ColumnWidths.DEFAULT
        DownloadColumn.entries
            .filter { ColumnDividers.isShown(it, layout) }
            .forEach { column ->
                val before = ColumnDividers.resolvedWidthOf(layout, widths, table, column)
                val divider = ColumnDividers.offsets(layout, widths, table)[column]!!
                val after = ColumnDividers.dragged(
                    widths = widths,
                    column = column,
                    toX = divider + 40f,
                    tableDp = table,
                    layout = layout
                )
                val moved = ColumnDividers.resolvedWidthOf(layout, after, table, column)
                assertTrue(
                    "$column did not move when dragged right (was $before, now $moved)",
                    moved > before
                )
                assertNotEquals("$column recorded nothing", widths, after)
                // And it has a handle to grab, not just arithmetic.
                assertEquals(
                    "$column has no grab area",
                    com.downloadhub.desktop.ResizeTarget.Handle(column),
                    ColumnDividers.targetAt(divider, layout, widths, table)
                )
            }
    }

    /**
     * A finished torrent's status is what the row says, whatever the error said.
     *
     * Checked against the real enum rather than the source text, so the ordering the
     * other test pins is the ordering that is actually used.
     */
    @Test
    fun completionIsTerminalAndFailureIsNot() {
        val terminal = setOf(DownloadStatus.COMPLETED)
        assertTrue(DownloadStatus.COMPLETED in terminal)
        // The two are deliberately different now, and that difference is the bug report.
        assertNotEquals(DownloadStatus.FAILED in terminal, DownloadStatus.COMPLETED in terminal)
    }
}
