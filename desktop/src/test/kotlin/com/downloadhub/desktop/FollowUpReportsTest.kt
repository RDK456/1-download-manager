package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.downloadhub.core.DownloadColumn
import com.downloadhub.core.DownloadStatus
import java.io.File
import com.downloadhub.core.LibraryGroup
import com.downloadhub.core.LibraryKind
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource

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
 * A second round of reports: the drag still fought back, finished torrents did not
 * finish themselves, there was no way to stop just the selected ones, and the rail
 * could not be folded away.
 */
class FollowUpReportsTest {

    /**
     * The drag died part-way across, every time.
     *
     * The handle listened with onPointerEvent(Move), which only fires while the pointer
     * is inside the 14 dp target. A drag is faster than its own handle, so the cursor
     * left it within a few milliseconds and the resize stopped dead - which is what
     * "still not proper" is.
     */
    @Test
    fun theHandleKeepsTheDragAfterTheCursorLeavesIt() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val handle = screen.substringAfter("private fun ResizeHandle(")
            .substringBefore("private fun ColumnHeaderCell(")
        assertTrue(
            "the handle must capture the press and keep listening, not wait for the " +
                "pointer to stay inside 14 dp:\n$handle",
            handle.contains("pointerInput") && handle.contains("awaitPointerEventScope")
        )
        assertTrue(
            "it has to wait for the press, then loop on moves until the button comes up:\n$handle",
            handle.contains("changedToDown()") && handle.contains("changedToUp()")
        )
        assertTrue(
            "and a plain Move handler only fires inside the target, which is the bug:\n$handle",
            !handle.contains("onPointerEvent(PointerEventType.Move)")
        )
    }

    /**
     * The column moves by how far the pointer travelled, not to where it is.
     *
     * An absolute position has to reconcile the sidebar, the header's padding, the
     * checkbox column and the display's pixel-to-dp scale - four things to get right,
     * all in different units. A delta from the press point needs none of them, and it
     * also cancels out landing anywhere inside the 14 dp handle instead of snapping the
     * column by however far in you grabbed it.
     */
    @Test
    fun aDragIsAMovementAndNotAPosition() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the width to drag from is read once, at the press",
            screen.contains("onDragStart") && screen.contains("dragStartWidth")
        )
        assertTrue(
            "and the move is added to it",
            screen.contains("proposedWidth = dragStartWidth + deltaDp")
        )
        val resizing = File("src/main/kotlin/com/downloadhub/desktop/ColumnResizing.kt").readText()
        assertTrue(
            "so the arithmetic takes a width, not an x:\n" +
                resizing.substringAfter("fun draggedBy(").take(400),
            resizing.contains("fun draggedBy(") && resizing.contains("proposedWidth: Float")
        )
    }

    /**
     * Pressing a handle and not moving must change nothing.
     *
     * The invariant that a 14 dp target and a pointer in the middle of it would break:
     * press seven dp left of the divider and the column used to shrink seven dp before
     * anything moved.
     */
    @Test
    fun grabbingAHandleAndNotMovingChangesNothing() {
        val layout = fullLayout()
        val widths = ColumnWidths.DEFAULT
        val table = 1600f
        DownloadColumn.entries
            .filter { ColumnDividers.isShown(it, layout) }
            .forEach { column ->
                val start = ColumnDividers.resolvedWidthOf(layout, widths, table, column)
                val after = ColumnDividers.draggedBy(
                    widths, column, proposedWidth = start + 0f, tableDp = table, layout = layout
                )
                assertEquals(
                    "grabbing $column must not resize it",
                    start,
                    ColumnDividers.resolvedWidthOf(layout, after, table, column),
                    0.01f
                )
            }
    }

    /** And a real movement still moves it, both ways. */
    @Test
    fun aDragStillMovesTheColumnBothWays() {
        val layout = fullLayout()
        val widths = ColumnWidths.DEFAULT
        val table = 1600f
        DownloadColumn.entries
            .filter { ColumnDividers.isShown(it, layout) }
            .forEach { column ->
                val start = ColumnDividers.resolvedWidthOf(layout, widths, table, column)
                val wider = ColumnDividers.draggedBy(widths, column, start + 40f, table, layout)
                val narrower = ColumnDividers.draggedBy(widths, column, start - 40f, table, layout)
                assertTrue(
                    "$column did not widen",
                    ColumnDividers.resolvedWidthOf(layout, wider, table, column) > start
                )
                assertTrue(
                    "$column did not narrow",
                    ColumnDividers.resolvedWidthOf(layout, narrower, table, column) < start
                )
            }
    }

    /**
     * A finished download is Finished, and everything else is Unfinished.
     *
     * The row said Completed, which is the same fact in different words - but the point
     * of the second group is that "not finished" is the question being asked most of
     * the time, and it could not be asked at all.
     */
    @Test
    fun finishedAndUnfinishedBetweenThemCoverTheQueue() {
        assertEquals("Finished", LibraryGroup.FINISHED.label)
        val done = DownloadItem("a", "u", "a.zip", DownloadSource.HTTP, DownloadStatus.COMPLETED)
        val going = DownloadItem("b", "u", "b.zip", DownloadSource.HTTP, DownloadStatus.RUNNING)
        val stopped = DownloadItem("c", "u", "c.zip", DownloadSource.TORRENT, DownloadStatus.PAUSED)
        val broken = DownloadItem("d", "u", "d.zip", DownloadSource.YOUTUBE, DownloadStatus.FAILED)
        val queued = DownloadItem("e", "u", "e.zip", DownloadSource.HTTP, DownloadStatus.QUEUED)

        assertTrue(LibraryGroup.FINISHED.matches(done))
        listOf(going, stopped, broken, queued).forEach {
            assertTrue("${it.fileName} should be unfinished", LibraryGroup.UNFINISHED.matches(it))
            assertTrue("and not finished", !LibraryGroup.FINISHED.matches(it))
        }
        // Including the ones that failed or were stopped, which is the point of saying
        // it as a subtraction: a status nobody thought of is unfinished, not invisible.
        assertEquals(1, listOf(done, going, stopped, broken, queued).count(LibraryGroup.FINISHED::matches))
        assertEquals(4, listOf(done, going, stopped, broken, queued).count(LibraryGroup.UNFINISHED::matches))
    }

    /**
     * Stop is its own action, and it is not Pause.
     *
     * Pause asks libtorrent to remember where it was; Stop takes the torrent out of the
     * session. A second name for pause would leave the download holding its slot and the
     * swarm open, which is not what pressing Stop means.
     */
    @Test
    fun stopIsItsOwnActionAndNotAPause() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val engine = File("src/main/kotlin/com/downloadhub/desktop/DesktopTorrentEngine.kt").readText()
        assertTrue("the action has to exist", controller.contains("val stop: (String) -> Unit"))
        assertTrue("and be wired up", controller.contains("stop = { id ->"))
        assertTrue(
            "a stopped torrent must be taken out of libtorrent, keeping its files:\n" +
                engine.substringAfter("fun stop(").take(500),
            engine.contains("fun stop(id: String)") &&
                engine.contains("engine.remove(item.toCoreItem(), deleteFiles = false)")
        )
        assertTrue(
            "and it must not delete what it has fetched",
            !engine.substringAfter("fun stop(").take(600).contains("deleteFiles = true")
        )
    }

    /** Stop is on the toolbar, beside Pause, and acts on the selection only. */
    @Test
    fun theToolbarHasAStopForTheSelectionAndSaysHowMany() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val toolbar = screen.substringAfter("private fun LibraryToolbar(")
            .substringBefore("private fun ToolbarButton(")
        assertTrue("Stop All is still there", toolbar.contains("ToolbarButton(\"Stop All\""))
        assertTrue(
            "and Stop for the selection sits beside Pause:\n$toolbar",
            toolbar.contains("ToolbarButton(\"Stop\", DlmIcons.Stop, enabled = hasSelection")
        )
        assertTrue(
            "acting on the ticked rows only",
            screen.contains("onStop = { selected.forEach { actions.stop(it) } }")
        )
        // Stop All must not have been quietly turned into "stop the selection".
        assertTrue(
            "Stop All still stops everything",
            screen.contains("onStopAll = actions.pauseAll")
        )
    }

    /**
     * The selection is counted where the buttons that use it are.
     *
     * "Which rows did I mean" is a question the toolbar can answer, and it is asked
     * every time a row is ticked.
     */
    @Test
    fun theSelectionIsCountedOnTheButtonsThatActOnIt() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue("the count reaches the toolbar", screen.contains("selectedCount = selected.size"))
        listOf("Resume", "Pause", "Stop").forEach { label ->
            assertTrue(
                "$label acts on the selection and should say how big it is",
                screen.contains("ToolbarButton(\"$label\"") &&
                    screen.substringAfter("ToolbarButton(\"$label\"").take(200).contains("badge = selectedCount")
            )
        }
    }

    /**
     * The rail folds, and a folded section takes its rows with it.
     *
     * With the states, the categories and the kinds all listed, the rail is taller than
     * the window on a laptop, and there is no way to see less of it.
     */
    @Test
    fun theRailFoldsAndARowKnowsWhichSectionOwnsIt() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val rail = screen.substringAfter("private fun CategoryRail(")
            .substringBefore("private object LibraryKindIcons")
        assertTrue("a heading can be folded", rail.contains("collapsedSections"))
        assertTrue(
            "and the whole heading is the target, with a chevron to say so",
            rail.contains("onToggle") && screen.contains("KeyboardArrowDown")
        )
        assertTrue(
            "a row in a folded section is not drawn at all, rather than greyed:\n" +
                rail.substringAfter("ownerOf[entry]").take(300),
            rail.contains("return@forEach")
        )
        // Everything starts open: folding has to be a deliberate act.
        assertTrue("nothing is folded to begin with", rail.contains("mutableStateOf(emptySet<String>())"))
    }

    /** The strip is on every tab, and counts the whole queue. */
    @Test
    fun theStatusStripIsAlwaysThereAndCountsEverything() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val panel = File("src/main/kotlin/com/downloadhub/desktop/TorrentDetailPanel.kt").readText()
        assertTrue(
            "the strip must not be inside the detail pane's if-block",
            !screen.contains("if (state.torrentsTab) {\n        // The selected download")
        )
        assertTrue("and it is drawn", screen.contains("TorrentStatusBar(all)"))
        assertTrue(
            "counting the queue rather than only the torrents:\n" +
                panel.substringAfter("fun TorrentStatusBar").take(700),
            panel.contains("items.size")
        )
    }
}
