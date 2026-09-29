package com.downloadhub.desktop

import com.downloadhub.core.DownloadColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two faults that only show on screen.
 *
 * Both were found by looking at a capture rather than by reading the code, and both are
 * the kind that leave nothing in a log: a caption sliced along its bottom edge, and a
 * row's buttons pushed off the right-hand end of the window. The tests here pin the
 * arithmetic and the shape of the fix; the pixels still have to be looked at.
 */
class RowFitsAndCaptionsShowTest {

    private val wide = TableLayout(
        showStatus = true, showSpeed = true, showTimeLeft = true, showDateAdded = true,
        showRowActions = true,
        size = androidx.compose.ui.unit.Dp(90f),
        status = androidx.compose.ui.unit.Dp(110f),
        speed = androidx.compose.ui.unit.Dp(95f),
        timeLeft = androidx.compose.ui.unit.Dp(90f),
        dateAdded = androidx.compose.ui.unit.Dp(110f)
    )

    /**
     * A dragged name may not squeeze the row's own buttons off the end.
     *
     * Pause, resume, retry and the per-download settings live at the right of every row.
     * The name could be dragged to nine tenths of the table, which left the fixed columns
     * 495 dp of width to find in the remaining tenth, and the buttons went off the edge of
     * the window where nothing can reach them.
     */
    @Test
    fun theRowAlwaysLeavesRoomForItsOwnButtons() {
        val greedy = ColumnWidths(nameShare = 0.9f)
        val tableDp = 1200f
        val laidOut = DownloadColumn.entries
            .filter { ColumnDividers.isShown(it, wide) }
            .fold(0f) { total, column ->
                total + ColumnDividers.resolvedWidthOf(wide, greedy, tableDp, column)
            }
        val chrome = ROW_CHROME_DP + ROW_ACTION_DP

        assertTrue(
            "the columns and the row's chrome must fit inside the table, but they add " +
                "up to ${laidOut + chrome} in $tableDp",
            laidOut + chrome <= tableDp + 0.5f
        )
    }

    @Test
    fun theCapOnlyBitesWhenTheShareWouldOverrun() {
        val tableDp = 1200f
        // Comfortably roomy: the user's chosen share is honoured exactly.
        assertEquals(
            0.5f * tableDp,
            ColumnWidths(nameShare = 0.5f).effectiveNameWidth(wide, tableDp),
            0.5f
        )
        // Nine tenths in 1200 dp is 1080, and the rest needs 495 + 78, so it is capped.
        val capped = ColumnWidths(nameShare = 0.9f).effectiveNameWidth(wide, tableDp)
        assertTrue(
            "nine tenths of 1200 leaves no room for the other columns",
            capped < 0.9f * tableDp
        )
        assertTrue("and it must still leave them all something", capped > 0f)
    }

    /**
     * A wide window still gets the wide name.
     *
     * The cap is applied when the width is read, not when the share is stored. Clamping
     * on store would reduce the user's choice the first time they made it and never give
     * it back on a bigger screen.
     */
    @Test
    fun aWideWindowHonoursTheFullShare() {
        val greedy = ColumnWidths(nameShare = 0.9f)
        // 8000 dp: nine tenths is 7200, and everything else - the other five columns,
        // the row's checkbox and its buttons - needs 589. So it fits and the share is
        // the user's, untouched.
        assertEquals(0.9f * 8000f, greedy.effectiveNameWidth(wide, 8000f), 0.5f)
    }

    /**
     * A drag is a distance, not a position.
     *
     * The pointer's x is measured from the table's left edge; a column's width is not.
     * Treating the pointer's position as the new width made a drag of +30 dp on an
     * already-wide column set it to 830 dp instead of adding 30 - which looked like the
     * column jumping about under the pointer.
     */
    @Test
    fun aDragAddsItsDistanceRatherThanTakingThePointerPosition() {
        val wide3 = ColumnWidths(overrides = mapOf(DownloadColumn.SIZE to 200f))
        val tableDp = 1200f
        val before = ColumnDividers.resolvedWidthOf(wide, wide3, tableDp, DownloadColumn.SIZE)
        val divider = ColumnDividers.offsets(wide, wide3, tableDp)[DownloadColumn.SIZE]!!

        val dragged = ColumnDividers.dragged(
            widths = wide3,
            column = DownloadColumn.SIZE,
            toX = divider + 30f,
            tableDp = tableDp,
            layout = wide
        )
        val after = ColumnDividers.resolvedWidthOf(wide, dragged, tableDp, DownloadColumn.SIZE)

        assertEquals(
            "30 dp of drag must move the divider 30 dp, whatever the pointer's absolute " +
                "position was",
            30f,
            after - before,
            0.5f
        )
    }

    @Test
    fun theDragHandlesLandOnTheEdgesOfTheColumnsAsDrawn() {
        // The handles decide what a drag means, and the cells decide what is drawn. If
        // they measure the name differently - one using the raw share, the other the
        // capped width - then a drag that is working perfectly moves a handle away from
        // the edge it is supposed to be on.
        val greedy = ColumnWidths(nameShare = 0.9f)
        val offsets = ColumnDividers.offsets(wide, greedy, 1200f)
        val nameAsDrawn = ColumnDividers.resolvedWidthOf(wide, greedy, 1200f, DownloadColumn.NAME)
        assertEquals(
            "the first divider must be the right-hand edge of the name column as drawn",
            nameAsDrawn,
            offsets[DownloadColumn.NAME]!!,
            0.01f
        )
    }

    @Test
    fun aDragAfterTheCapStillMovesTheColumnItStartedOn() {
        // With the name capped, the pointer's distance from the divider is the change to
        // the *drawn* width, not to the stored share. Getting that wrong makes a column
        // jump when it is dragged.
        val capped = ColumnWidths(nameShare = 0.9f)
        val tableDp = 1200f
        val asDrawn = ColumnDividers.resolvedWidthOf(wide, capped, tableDp, DownloadColumn.NAME)
        val divider = ColumnDividers.offsets(wide, capped, tableDp)[DownloadColumn.NAME]!!

        val dragged = ColumnDividers.dragged(
            widths = capped,
            column = DownloadColumn.NAME,
            toX = divider + 40f,
            tableDp = tableDp,
            layout = wide
        )
        val after = ColumnDividers.resolvedWidthOf(wide, dragged, tableDp, DownloadColumn.NAME)
        // The name was already at its cap, so a further drag right cannot widen it; what
        // matters is that it does not throw or shrink it.
        assertTrue("a drag past the cap must not shrink the name", after >= asDrawn - 0.5f)
    }

    /**
     * The toolbar caption is not sliced.
     *
     * Every caption in the row was cut along its bottom edge, all by the same amount.
     * Giving the caption a fixed box made it worse rather than better, which ruled out
     * the button being too small and pointed at the line box: at 10 sp the default font's
     * metrics measured shorter than the glyphs, so the descenders were drawn past the
     * bottom of the line and cut off.
     *
     * Checked by reading the source, because a caption's rendered height is not something
     * a unit test can measure. The two things that must *not* be there are as much of the
     * answer as the one that must.
     */
    @Test
    fun theCaptionGetsALineBoxRatherThanAConstraint() {
        val source = java.io.File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt")
            .readText()
        val button = source.substringAfter("private fun ToolbarButton(")
            .substringBefore("private fun ColumnHeader(")

        assertTrue(
            "the caption's line box must be made taller than the text it sets:\n$button",
            button.contains("lineHeight = TOOLBAR_CAPTION_LINE_HEIGHT_SP.sp")
        )
        assertTrue(
            "and the caption must not be given a height of its own - that is what made " +
                "it worse, because a short box clips the descenders rather than fixing " +
                "the line they sit in",
            !button.contains("TOOLBAR_CAPTION_HEIGHT_DP")
        )
        assertTrue(
            "nor may the button be height-constrained, or the clip just moves",
            !button.contains("TOOLBAR_BUTTON_HEIGHT_DP")
        )
    }

    @Test
    fun theCaptionLineBoxIsTallerThanTheTextItSets() {
        // 10 sp of text, and the box is given a third more. The gap is the leading and
        // the descenders, and it is the whole of the fix.
        assertTrue(
            "a 10 sp caption needs a line box bigger than 10 sp or its descenders are cut",
            TOOLBAR_CAPTION_LINE_HEIGHT_SP > 10f
        )
    }
}
