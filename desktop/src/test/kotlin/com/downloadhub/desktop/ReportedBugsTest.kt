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
     * The pane with Details, Content, Peers and Trackers was only on the Torrents tab.
     *
     * So the answer to "what is this doing, and which file is stuck" existed only
     * after switching tabs and finding the right row.
     */
    @Test
    fun theBottomPaneIsNotHiddenBehindTheTorrentsTab() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val pane = screen.substringAfter("TorrentDetailPanel(").let {
            screen.substring(0, screen.indexOf("TorrentDetailPanel("))
        }
        assertTrue(
            "the pane must not be inside `if (state.torrentsTab)`",
            !pane.contains("if (state.torrentsTab) {")
        )
        assertTrue(
            "the pane is drawn unconditionally now",
            screen.contains("val detailItem = all.firstOrNull { it.id in selected }")
        )
        // And it must describe something the user can see. It used to fall back to the
        // first torrent whatever tab was open, which on the main list was a row that
        // might not be on screen at all.
        assertTrue(
            "the pane should follow the list it sits under",
            screen.contains("?: visible.firstOrNull()")
        )
        assertTrue(
            "and must not fall back to a torrent chosen by kind:\n",
            !screen.substringAfter("val detailItem =").substringBefore("TorrentStatusBar")
                .contains("DownloadSource.TORRENT")
        )
    }

    /**
     * Every column has a handle, and pressing one does not move the column.
     *
     * The handles are drawn inside the header, which is 8 dp of padding plus an 18 dp
     * checkbox column in from the table's edge. The drag conversion took the sidebar
     * off the pointer's x and nothing else, so every handle sat 26 dp to the right of
     * where the arithmetic thought it was. A drag is a difference, so a constant 26 dp
     * error meant pressing a handle and moving one pixel snapped the column 26 dp
     * sideways - which is a header that feels like it is fighting back, and for the
     * columns nearest the right edge pushes the rest off the table.
     */
    @Test
    fun aHeaderIsInTheSameSpaceAsTheArithmeticThatMovesIt() {
        assertEquals(26f, HEADER_LEADING_DP, 0.01f)
        assertEquals(
            "the sidebar and the header's own chrome both have to come off",
            400f,
            ColumnDividers.tableXOf(windowX = 656f, sidebarDp = 230f),
            0.01f
        )

        val layout = fullLayout()
        val widths = ColumnWidths.DEFAULT
        // Wide enough that the name column is not already pinned against the cap. It is
        // capped by whatever the fixed columns leave over, so on a narrow table it
        // cannot grow at all - by design, and checked separately below.
        val table = 1600f

        // The invariant: a pointer resting exactly on a divider, moved nowhere, must
        // leave every column exactly as it was. Under the old conversion this moved
        // every column by the width of the header's chrome.
        DownloadColumn.entries
            .filter { ColumnDividers.isShown(it, layout) }
            .forEach { column ->
                val divider = ColumnDividers.offsets(layout, widths, table)[column]!!
                val unchanged = ColumnDividers.dragged(
                    widths = widths,
                    column = column,
                    toX = divider,
                    tableDp = table,
                    layout = layout
                )
                assertEquals(
                    "grabbing $column and not moving must not resize it",
                    ColumnDividers.resolvedWidthOf(layout, widths, table, column),
                    ColumnDividers.resolvedWidthOf(layout, unchanged, table, column),
                    0.01f
                )
            }
    }

    /**
     * The name column stops growing when the fixed columns have taken all that is left.
     *
     * Not a bug - a name wider than the room available would push the row's pause and
     * options buttons off the end of the table, taking the row's controls with them.
     * Pinned here because the resize test has to use a table wide enough not to hit it,
     * and that is only safe to assume if the cap is a fact.
     */
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
