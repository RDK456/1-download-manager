package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The window can be resized smaller than the layout wants to be, so the layout has to
 * decide what to give up. These are the decisions, tested without a window, because a
 * clipped column is exactly the sort of thing that survives a compile and only shows up
 * when somebody drags the edge.
 */
class LibraryLayoutTest {

    /**
     * The sum the old fixed layout needed: a 26 dp checkbox, a 320 dp name, 90 of size,
     * 110 of status, 95 of speed, 90 of time left and 110 of date, which is 841 dp, and
     * the sidebar was another 230. The window opens at 1180 so it looked right, and it
     * broke the moment it was made narrower - the right-hand columns and three toolbar
     * buttons were simply off the edge.
     */
    @Test
    fun theOldFixedWidthsWouldNotHaveFitASmallWindow() {
        val fixedColumns = 26f + 320f + 90f + 110f + 95f + 90f + 110f
        val oldMinimum = fixedColumns + 230f
        assertEquals("the old layout's arithmetic changed", 1071f, oldMinimum, 0.01f)
        // It only fitted because the window opens at 1180.
        assertTrue("so it did not fit a 900 dp window", oldMinimum > 900f)
    }

    /**
     * The property that was broken: a fixed set of widths that added up to more than the
     * window had, so the row ran off the edge instead of giving way. This is the whole
     * reason [TableLayout.requiredDp] exists - the first version of the thresholds was
     * a hundred dp out and still clipped the row at 620 dp.
     *
     * Every width the window can actually be, not just the round ones.
     */
    @Test
    fun theRowAlwaysFitsTheWidthItWasGiven() {
        (MINIMUM_TABLE_DP.toInt()..1200).forEach { available ->
            val layout = tableLayoutFor(available.toFloat())
            assertTrue(
                "at ${available}dp the row needs ${layout.requiredDp}dp, so it is " +
                    "clipped again",
                layout.requiredDp <= available
            )
        }
    }

    /** And the same through the real path: window width in, row that fits out. */
    @Test
    fun theTableFitsWhateverTheWindowCanBe() {
        (MINIMUM_WINDOW_SIZE.width..1400).forEach { window ->
            val sidebar = sidebarWidthFor(window.toFloat())
            // The one dp is the vertical rule between the sidebar and the table.
            val content = window - sidebar.value - 1f
            val layout = tableLayoutFor(content)
            assertTrue(
                "at a ${window}dp window the table has $content dp, the sidebar " +
                    "takes ${sidebar.value} dp and the row needs " +
                    "${layout.requiredDp} dp",
                content >= layout.requiredDp
            )
        }
    }

    /**
     * The narrowest the window gets is the narrowest the layout has to survive, and it
     * still shows a name, a size and a status - not just a name.
     */
    @Test
    fun theSmallestWindowStillShowsTheUsefulColumns() {
        val window = MINIMUM_WINDOW_SIZE.width.toFloat()
        val layout = tableLayoutFor(window - sidebarWidthFor(window).value - 1f)
        assertTrue("the name, at least", layout.requiredDp > ROW_CHROME_DP + NAME_MINIMUM_DP - 1f)
        assertTrue("the size", layout.size.value > 0f)
        assertTrue("and the status", layout.showStatus)
        assertTrue("and the row can still be paused from it", layout.showRowActions)
    }

    @Test
    fun aWideWindowGetsEveryColumn() {
        val layout = tableLayoutFor(1000f)
        assertTrue(layout.showStatus)
        assertTrue(layout.showSpeed)
        assertTrue(layout.showTimeLeft)
        assertTrue(layout.showDateAdded)
        assertTrue(layout.showRowActions)
        // The table's own decision, kept separate from the toolbar's: at this width the
        // table still has every column, and the toolbar is the thing that gives way.
        assertFalse(tableLayoutFor(1000f).narrowSidebar)
    }

    /**
     * The name is the row, so it is the last thing to go. Everything else is dropped
     * from the right in the order of how much a person needs it.
     */
    @Test
    fun columnsAreGivenUpFromTheRight() {
        assertTrue("speed goes before status", tableLayoutFor(1000f).showSpeed)
        assertTrue("status outlives speed", tableLayoutFor(600f).showStatus)
        assertFalse("speed is the first to go", tableLayoutFor(600f).showSpeed)
        assertFalse("time left goes before the date", tableLayoutFor(600f).showTimeLeft)
        assertTrue("the date outlives the countdown", tableLayoutFor(600f).showDateAdded)
        assertFalse("the date goes before size", tableLayoutFor(400f).showDateAdded)
        assertTrue("size outlives the date", tableLayoutFor(400f).size.value > 0f)
        assertTrue("but not the row action", tableLayoutFor(400f).showRowActions)
    }

    /** Below the last threshold there is only the name, and no row action to lose. */
    @Test
    fun aVeryNarrowWindowStillShowsTheName() {
        val layout = tableLayoutFor(120f)
        assertFalse(layout.showStatus)
        assertFalse("size is the first thing to go when there is nothing else", layout.size.value > 0f)
        assertFalse(layout.showRowActions)
        // Nothing is shown, so nothing has a width. A zero-width column would still
        // take its padding and the row would not line up with the header.
        assertEquals(0f, layout.size.value, 0.01f)
        assertEquals(0f, layout.status.value, 0.01f)
        assertEquals(0f, layout.dateAdded.value, 0.01f)
    }

    /**
     * The sidebar narrows before the table gives up a column. A sidebar you can still
     * read is worth more than a speed column, and a fixed 230 dp is a third of a 700 dp
     * window.
     */
    @Test
    fun theSidebarNarrowsAsTheWindowDoes() {
        assertEquals(230f, sidebarWidthFor(1180f).value, 0.01f)
        assertEquals(190f, sidebarWidthFor(900f).value, 0.01f)
        assertEquals(160f, sidebarWidthFor(700f).value, 0.01f)
        assertEquals(120f, sidebarWidthFor(500f).value, 0.01f)
        // Never more than half the window, or there is nothing left for the list. This
        // has to hold across every reachable width, not just the round ones.
        (MINIMUM_WINDOW_SIZE.width..1400).forEach { window ->
            assertTrue(
                "the sidebar takes ${sidebarWidthFor(window.toFloat()).value} of $window dp",
                sidebarWidthFor(window.toFloat()).value <= window / 2f
            )
        }
    }

    /**
     * The toolbar has to fit at the size the app opens at, which is where it was worst:
     * nine captioned buttons plus a fixed 260 dp search box is 982 dp, and the table has
     * 949 dp of a 1180 dp window. Settings was cut off the right edge at the default
     * size, not just on a deliberately small window.
     */
    @Test
    fun theToolbarFitsTheWindowItOpensAt() {
        val window = 1180f
        val content = window - sidebarWidthFor(window).value - 1f
        assertEquals(
            "at ${window}dp the toolbar gets $content dp and needs ${TOOLBAR_FULL_DP} dp",
            ToolbarStyle.FULL,
            toolbarStyleFor(content)
        )
        assertTrue(
            "and the search box it leaves room for is worth typing in",
            content - toolbarLayoutFor(content).requiredDp >= SEARCH_MIN_DP
        )
    }

    /**
     * The toolbar gives things up in a defined order: the search box goes first, because
     * it is the one thing in the row that is not a button, and only then the captions -
     * and never the buttons themselves.
     */
    @Test
    fun theToolbarGivesUpTheSearchBoxBeforeTheCaptions() {
        assertEquals(ToolbarStyle.FULL, toolbarStyleFor(1000f))
        assertEquals(ToolbarStyle.CAPTIONED, toolbarStyleFor(TOOLBAR_FULL_DP - 1f))
        assertEquals(
            "captions are the last thing to go, so they survive right down to the floor",
            ToolbarStyle.CAPTIONED,
            toolbarStyleFor(TOOLBAR_CAPTIONED_MIN_DP)
        )
        assertEquals(ToolbarStyle.COMPACT, toolbarStyleFor(TOOLBAR_CAPTIONED_MIN_DP - 1f))
        assertEquals(ToolbarStyle.COMPACT, toolbarStyleFor(200f))
        assertFalse("but never both at once", toolbarLayoutFor(TOOLBAR_FULL_DP - 1f).showsSearch)
    }
    @Test
    fun captionedButtonsAreAsWideAsTheWindowAllows() {
        // A width where each button would clear the caption floor, and no narrower.
        //
        // This used to name one - 709 dp - which was a number tuned to a toolbar of nine
        // buttons. Adding a tenth moved the thresholds and the test failed, which is
        // what it is for; but the number itself was only ever standing in for the
        // property, so asking it to be derived means it keeps standing in for it when the
        // eleventh button arrives.
        val roomy = TOOLBAR_CAPTIONED_MIN_DP + 1f
        val mid = toolbarLayoutFor(roomy)
        assertEquals(ToolbarStyle.CAPTIONED, mid.style)
        assertTrue(
            "the buttons are ${mid.buttonDp} dp, below the ${CAPTION_FLOOR_DP} dp floor",
            mid.buttonDp >= CAPTION_FLOOR_DP
        )
        assertTrue(
            "and no wider than they need to be: ${mid.buttonDp} dp",
            mid.buttonDp <= CAPTION_BUTTON_DP
        )

        // Below the floor the captions go, which is the trade the floor exists for.
        assertEquals(ToolbarStyle.COMPACT, toolbarLayoutFor(TOOLBAR_CAPTIONED_MIN_DP - 1f).style)
    }
    fun noCaptionedButtonIsEverTooNarrowForItsName() {
        (MINIMUM_WINDOW_SIZE.width..1400).forEach { window ->
            val available = window - sidebarWidthFor(window.toFloat()).value - 1f
            val layout = toolbarLayoutFor(available)
            if (layout.style != ToolbarStyle.COMPACT) {
                assertTrue(
                    "at a ${window}dp window the buttons are ${layout.buttonDp} dp, " +
                        "below the ${CAPTION_FLOOR_DP} dp floor",
                    layout.buttonDp >= CAPTION_FLOOR_DP
                )
            }
        }
    }

    /** The toolbar has to fit at every reachable width, whatever it decided to show. */
    @Test
    fun theToolbarFitsAtEveryReachableWidth() {
        (MINIMUM_WINDOW_SIZE.width..1400).forEach { window ->
            val available = window - sidebarWidthFor(window.toFloat()).value - 1f
            val layout = toolbarLayoutFor(available)
            var needed = layout.requiredDp
            // The search box is a weight with a ceiling, so it only has to be worth
            // having, not to be at its full width.
            if (layout.showsSearch) needed += SEARCH_MIN_DP
            assertTrue(
                "at a ${window}dp window the toolbar needs $needed dp of $available dp",
                needed <= available
            )
        }
    }

    /**
     * The search box must never be what overflows: it is a weight with a ceiling, so it
     * takes what is left. This is the property that was broken.
     */
    @Test
    fun theSearchBoxTakesWhatIsLeftRatherThanAFixedWidth() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the search box is a fixed width again, so it is what pushes Settings off " +
                "the end of a narrow window",
            screen.contains(".weight(1f)") &&
                screen.contains("widthIn(min = SEARCH_MIN_DP.dp, max = SEARCH_MAX_DP.dp)")
        )
        assertTrue(
            "and it must not be a fixed 260 dp any more",
            !screen.contains("Modifier.width(260.dp)")
        )
    }

    /** Narrow enough that the rail's labels no longer sit comfortably: tighten its indent. */
    @Test
    fun theRailLosesItsIndentOnlyWhenItIsNarrow() {
        assertFalse(tableLayoutFor(1000f).narrowSidebar)
        assertTrue(tableLayoutFor(600f).narrowSidebar)
    }
}
