package com.downloadhub.desktop

import com.downloadhub.core.DownloadColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draggable column headers.
 *
 * The arithmetic is separated from the composables on purpose. The version that laid the
 * header and the rows out independently had them disagreeing by a few pixels, so the
 * captions sat over the wrong columns, and nothing in the build noticed - a header cannot
 * be checked by a unit test until the arithmetic it depends on can be.
 */
class ColumnResizingTest {

    private val wide = TableLayout(
        showStatus = true, showSpeed = true, showTimeLeft = true, showDateAdded = true,
        showRowActions = true,
        size = androidx.compose.ui.unit.Dp(90f),
        status = androidx.compose.ui.unit.Dp(110f),
        speed = androidx.compose.ui.unit.Dp(95f),
        timeLeft = androidx.compose.ui.unit.Dp(90f),
        dateAdded = androidx.compose.ui.unit.Dp(110f)
    )

    private fun dp(value: Float) = androidx.compose.ui.unit.Dp(value)

    @Test
    fun theNameColumnTakesItsShareAndTheRestKeepTheirWidths() {
        val widths = ColumnWidths(nameShare = 0.5f)
        assertEquals(400f, widths.widthOf(DownloadColumn.NAME, 800f), 0.01f)
        assertEquals(90f, widths.widthOf(DownloadColumn.SIZE, 800f), 0.01f)
        assertEquals(110f, widths.widthOf(DownloadColumn.STATUS, 800f), 0.01f)
    }

    @Test
    fun aDraggedNameKeepsItsProportionWhenTheWindowIsResized() {
        // The reason the name is a share and not a dp value. A name dragged to 600 dp on a
        // 1200 dp window is "about half"; asking for 600 dp again on a 700 dp window would
        // leave it wider than the table and push every other column off the edge.
        val dragged = ColumnDividers.dragged(
            widths = ColumnWidths.DEFAULT,
            column = DownloadColumn.NAME,
            toX = 600f,
            tableDp = 1200f,
            layout = wide
        )
        assertEquals(0.5f, dragged.nameShare, 0.01f)
        assertEquals(350f, dragged.widthOf(DownloadColumn.NAME, 700f), 0.5f)
    }

    @Test
    fun dividersLandOnTheBoundariesBetweenColumns() {
        val widths = ColumnWidths.DEFAULT
        val offsets = ColumnDividers.offsets(wide, widths, 1200f)
        // The name ends at its share; each later column ends where the next begins.
        assertEquals(600f, offsets[DownloadColumn.NAME]!!, 0.01f)
        assertEquals(690f, offsets[DownloadColumn.SIZE]!!, 0.01f)
        assertEquals(800f, offsets[DownloadColumn.STATUS]!!, 0.01f)
        assertEquals(895f, offsets[DownloadColumn.SPEED]!!, 0.01f)
        assertEquals(985f, offsets[DownloadColumn.TIME_LEFT]!!, 0.01f)
        assertEquals(1095f, offsets[DownloadColumn.DATE_ADDED]!!, 0.01f)
    }

    @Test
    fun aHiddenColumnHasNoDividerAndPushesNothing() {
        val medium = wide.copy(showSpeed = false, showTimeLeft = false)
        val offsets = ColumnDividers.offsets(medium, ColumnWidths.DEFAULT, 1200f)
        assertTrue(
            "a column the layout has dropped must not leave a divider behind",
            DownloadColumn.SPEED !in offsets && DownloadColumn.TIME_LEFT !in offsets
        )
        // And the ones that remain still meet each other exactly.
        assertEquals(
            offsets[DownloadColumn.STATUS]!!,
            offsets[DownloadColumn.DATE_ADDED]!! - 110f,
            0.01f
        )
    }

    @Test
    fun aPointerNearADividerGrabsIt() {
        val widths = ColumnWidths.DEFAULT
        assertEquals(
            ResizeTarget.Handle(DownloadColumn.NAME),
            ColumnDividers.targetAt(598f, wide, widths, 1200f)
        )
        assertEquals(
            ResizeTarget.Handle(DownloadColumn.SIZE),
            ColumnDividers.targetAt(692f, wide, widths, 1200f)
        )
        // Well away from any divider, nothing is grabbed - otherwise a click in the
        // middle of the header would start a drag.
        assertEquals(ResizeTarget.None, ColumnDividers.targetAt(400f, wide, widths, 1200f))
    }

    @Test
    fun theNearestDividerWinsSoAPassOverOneDoesNotSwitchColumns() {
        // Two dividers 90 dp apart and a 6 dp grab radius: there is no position that could
        // match both, but the nearest must be chosen rather than the first, or a drag that
        // crosses a divider changes which column it is dragging halfway through.
        val offsets = ColumnDividers.offsets(wide, ColumnWidths.DEFAULT, 1200f)
        val sizeEdge = offsets[DownloadColumn.SIZE]!!
        val statusEdge = offsets[DownloadColumn.STATUS]!!
        assertEquals(
            ResizeTarget.Handle(DownloadColumn.SIZE),
            ColumnDividers.targetAt(sizeEdge + 4f, wide, ColumnWidths.DEFAULT, 1200f)
        )
        assertEquals(
            ResizeTarget.Handle(DownloadColumn.STATUS),
            ColumnDividers.targetAt(statusEdge - 4f, wide, ColumnWidths.DEFAULT, 1200f)
        )
    }

    @Test
    fun aDragMovesTheColumnItStartedOn() {
        val start = ColumnWidths.DEFAULT
        val dragged = ColumnDividers.dragged(
            widths = start,
            column = DownloadColumn.SIZE,
            // 30 dp right of where the Size divider currently is.
            toX = 690f + 30f,
            tableDp = 1200f,
            layout = wide
        )
        assertEquals(120f, dragged.widthOf(DownloadColumn.SIZE, 1200f), 0.01f)
        // And nothing else moved.
        assertEquals(start.nameShare, dragged.nameShare, 0.0001f)
        assertEquals(110f, dragged.widthOf(DownloadColumn.STATUS, 1200f), 0.01f)
    }

    @Test
    fun aColumnCannotBeDraggedOutOfExistence() {
        val dragged = ColumnDividers.dragged(
            widths = ColumnWidths.DEFAULT,
            column = DownloadColumn.SIZE,
            toX = -5000f,
            tableDp = 1200f,
            layout = wide
        )
        assertTrue(
            "a column narrower than its own heading is a column nobody can identify",
            dragged.widthOf(DownloadColumn.SIZE, 1200f) >= ColumnWidths.MIN_COLUMN_DP
        )
    }

    @Test
    fun theNameCannotBeDraggedAwayOrOnTopOfTheOthers() {
        val narrow = ColumnDividers.dragged(
            widths = ColumnWidths.DEFAULT,
            column = DownloadColumn.NAME,
            toX = 0f,
            tableDp = 1200f,
            layout = wide
        )
        assertEquals(
            ColumnWidths.MIN_NAME_SHARE,
            narrow.nameShare,
            0.0001f
        )

        val wide2 = ColumnDividers.dragged(
            widths = ColumnWidths.DEFAULT,
            column = DownloadColumn.NAME,
            toX = 5000f,
            tableDp = 1200f,
            layout = wide
        )
        // Never more than nine tenths, so the other columns keep a tenth between them.
        assertEquals(0.9f, wide2.nameShare, 0.0001f)
    }

    @Test
    fun theNameStaysReadableInANarrowWindow() {
        // The share floor alone would allow 20 dp of name in a 100 dp table, which is one
        // truncated character. The absolute minimum has to win there.
        val dragged = ColumnDividers.dragged(
            widths = ColumnWidths.DEFAULT,
            column = DownloadColumn.NAME,
            toX = 0f,
            tableDp = 300f,
            layout = wide
        )
        assertTrue(
            "in a 300 dp table the name must still be at least ${ColumnWidths.MIN_NAME_DP} dp",
            dragged.widthOf(DownloadColumn.NAME, 300f) >= ColumnWidths.MIN_NAME_DP
        )
    }

    @Test
    fun draggingIsMeasuredAgainstTheDividerAndNotTheColumnStart() {
        // The bug this guards: treating the pointer's position as the new width, rather
        // than as how far the divider has moved. A drag of +30 dp from an already-wide
        // column must add 30 dp, not set the width to the pointer's x.
        val wide3 = ColumnWidths(overrides = mapOf(DownloadColumn.SIZE to 200f))
        val dragged = ColumnDividers.dragged(
            widths = wide3,
            column = DownloadColumn.SIZE,
            toX = 800f + 30f,
            tableDp = 1200f,
            layout = wide
        )
        assertEquals(230f, dragged.widthOf(DownloadColumn.SIZE, 1200f), 0.01f)
    }

    @Test
    fun everyVisibleColumnHasACaptionAndAWidth() {
        val widths = ColumnWidths.DEFAULT
        val all = widths.all(1200f)
        DownloadColumn.entries.forEach { column ->
            assertTrue("$column has no caption", column.label.isNotBlank())
            assertTrue("$column has no width", all.getValue(column) > 0f)
        }
    }
}
