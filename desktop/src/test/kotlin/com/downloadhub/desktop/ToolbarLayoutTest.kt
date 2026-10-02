package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A screenshot of a broken toolbar, and what it turned out to be.
 *
 * The search box had collapsed into a sliver a couple of dozen pixels wide with its
 * placeholder wrapped one letter per line down the window, two Pause buttons sat side
 * by side, and the download list had no height left to be in.
 */
class ToolbarLayoutTest {

    /**
     * The button count is a number in another file, and it was wrong.
     *
     * A tenth button was added and this stayed at nine, so the row was measured as
     * though the tenth did not exist: it decided there was room for captions *and* the
     * search box, handed the search box the leftover, and the leftover was nothing.
     *
     * This is the test that should have stopped it, and it is here so the next button
     * cannot do it again.
     */
    @Test
    fun theToolbarMeasuresTheButtonsItActuallyDraws() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val toolbar = screen.substringAfter("private fun LibraryToolbar(")
            .substringBefore("private fun ToolbarButton(")
        val drawn = Regex("""ToolbarButton\(""").findAll(toolbar).count()
        assertEquals(
            "the toolbar draws $drawn buttons but TOOLBAR_BUTTON_COUNT says otherwise, " +
                "so the row is measured wrong and the search box is handed the leftover",
            drawn,
            TOOLBAR_BUTTON_COUNT
        )
    }

    /** One button per action, with no action drawn twice. */
    @Test
    fun noButtonIsDrawnTwice() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val toolbar = screen.substringAfter("private fun LibraryToolbar(")
            .substringBefore("private fun ToolbarButton(")
        val labels = Regex("""ToolbarButton\("([^"]+)"""")
            .findAll(toolbar)
            .map { it.groupValues[1] }
            .toList()
        val repeats = labels.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "the same button is in the row twice: $repeats",
            repeats.isEmpty()
        )
        assertEquals(
            "Stop for the selection and Stop All are different actions and both belong " +
                "there",
            setOf("Resume", "Pause", "Stop", "Start Queue", "Stop Queue", "Stop All"),
            labels.toSet().intersect(setOf("Resume", "Pause", "Stop", "Start Queue", "Stop Queue", "Stop All"))
        )
    }

    /**
     * The search box is never taller than one line, whatever its width does.
     *
     * A field measures its height from its contents, and contents that wrap grow it
     * without limit. A placeholder in a column a couple of dozen pixels wide wrapped one
     * letter per line, which made the field nearly 400 dp tall, which made the toolbar
     * nearly half the window, which left the list nothing.
     */
    @Test
    fun theSearchBoxCannotGrowItsToolbar() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val field = screen.substringAfter("OutlinedTextField(").take(900)
        assertTrue(
            "the placeholder must not wrap:\n$field",
            field.contains("maxLines = 1") && field.contains("softWrap = false")
        )
        assertTrue(
            "and it should ellipsise rather than wrap",
            field.contains("TextOverflow.Ellipsis")
        )
        assertTrue(
            "the field needs a height it will keep:\n$field",
            field.contains("heightIn(max = SEARCH_MAX_HEIGHT_DP.dp)")
        )
        assertTrue(
            "one line of text plus padding, and no more",
            SEARCH_MAX_HEIGHT_DP in 32f..48f
        )
    }

    /**
     * The search box is only offered when it genuinely fits.
     *
     * Below the floor the box is a sliver, which is worse than no box: it looks broken
     * rather than absent.
     */
    @Test
    fun theSearchBoxIsOnlyShownWhenItFits() {
        val full = TOOLBAR_FULL_DP
        assertTrue(
            "the captioned toolbar plus a usable search box needs $full dp",
            full > TOOLBAR_CAPTIONED_MIN_DP
        )
        // Below the full width there is no search box, and above it there is.
        assertTrue(
            "a window wide enough for the search box should be offered one",
            toolbarLayoutFor(full).showsSearch
        )
        assertTrue(
            "a window that is not should not be",
            !toolbarLayoutFor(full - 1f).showsSearch
        )
        // And however narrow the window the app will actually open at, the buttons
        // themselves still fit. The compact width is worked out from the room there is
        // for exactly this reason: a flat one overflowed at the minimum window size and
        // pushed Settings off the end.
        val narrowest = MINIMUM_WINDOW_SIZE.width - sidebarWidthFor(MINIMUM_WINDOW_SIZE.width.toFloat()).value - 1f
        (narrowest.toInt()..1400 step 7).forEach { width ->
            val layout = toolbarLayoutFor(width.toFloat())
            assertTrue(
                "the toolbar needs ${layout.requiredDp} dp but only has $width",
                layout.requiredDp <= width
            )
        }
    }

    /**
     * The bottom pane cannot take the list's room.
     *
     * The pane draws at a fixed height with a floor, and the list takes what is left.
     * There has to be enough left for a row to be visible, or selecting a download makes
     * the list of downloads disappear - which is the opposite of what a detail pane is
     * for.
     */
    @Test
    fun thePaneLeavesRoomForTheList() {
        assertTrue(
            "the pane's default height should leave a list in a normal window",
            PANE_DEFAULT_DP + LIST_MIN_DP + 120f < 731f
        )
        assertTrue(
            "and at the floor the pane is still smaller than half a short window",
            PANE_MIN_DP < 731f / 3
        )
        assertTrue("the pane's floor is a floor", PANE_MIN_DP > 0f)
        assertTrue("and it has a ceiling", PANE_MAX_DP > PANE_MIN_DP)
    }
}
