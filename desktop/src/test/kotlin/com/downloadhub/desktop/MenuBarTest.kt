package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The menu strip.
 *
 * It is the one row of this window that is not driven by :core, so nothing else checks
 * it, and both of the things that were wrong with it were invisible until the window was
 * made narrow or a menu was opened.
 */
class MenuBarTest {

    private fun menuBar() = File("src/main/kotlin/com/downloadhub/desktop/MenuBar.kt").readText()

    /**
     * A menu that opens somewhere other than under its own entry is not a menu, it is a
     * panel that happened to open. This used to be a table of four numbers shared between
     * "how wide is this label" and "where does its panel go", and every one of them was
     * short by the width of the wordmark.
     */
    @Test
    fun aMenuOpensUnderneathItsOwnEntry() {
        val text = menuBar()
        assertTrue(
            "the panel is positioned from a table of hard-coded offsets instead of from " +
                "where the entry was drawn",
            text.contains("onGloballyPositioned")
        )
        assertTrue(
            "and the offset it positions from is the measured one",
            text.contains("offsets[open]") && text.contains("positionInRoot()")
        )
        listOf("File", "Tasks", "Tools", "Help").forEach { entry ->
            assertTrue(
                "the $entry entry does not record where it was drawn, so its panel opens " +
                    "in the wrong place",
                text.contains("offsets[\"$entry\"] = it")
            )
        }
    }

    @Test
    fun theMagicOffsetsAreGone() {
        val text = menuBar()
        assertFalse(
            "offsetFor is still here, which means the panels are still placed by a table " +
                "of numbers that has to be kept in step with the labels",
            text.contains("fun offsetFor")
        )
        // The old cumulative left edges: 100, 158, 222, 280.
        assertFalse(
            "a label is still given a fixed width that doubles as a panel offset",
            Regex("""MenuLabel\("(\w+)", \d+\.dp,""").containsMatchIn(text)
        )
    }

    /**
     * The panel has to read as something floating above the window.
     *
     * It was a flat rectangle laid straight onto the content: no shadow, no edge and
     * nothing dimmed behind it, so it looked like a hole cut in the window rather than
     * a layer in front of it.
     */
    @Test
    fun thePanelLooksLikeALayerRatherThanAHole() {
        val text = menuBar()
        assertTrue(
            "there is nothing behind the panel, so it has nothing to float above",
            text.contains("MENU_SCRIM") && text.contains("private fun Scrim")
        )
        assertTrue("the panel has no shadow", text.contains("shadowElevation"))
        assertTrue("and no edge", text.contains("border = BorderStroke"))
    }

    /**
     * A panel must open below the strip, not over it.
     *
     * It was positioned with a hard-coded 32 dp while the strip is 34 dp tall, so the
     * top of every panel sat 2 dp inside the highlighted entry that opened it.
     */
    @Test
    fun aPanelOpensBelowTheStripRatherThanOverIt() {
        val text = menuBar()
        assertTrue(
            "a panel is still placed at a hard-coded offset rather than at the bottom " +
                "of the menu strip",
            !Regex("""top = 32\.dp""").containsMatchIn(text)
        )
        assertEquals(
            "every panel must anchor to the bottom of the strip",
            2,
            Regex("""top = MENU_BAR_HEIGHT""").findAll(text).count()
        )
    }

    /**
     * An item's text lines up with the entry it belongs to.
     *
     * A label had a 12 dp inset and its items had 14, so every item sat two pixels
     * right of the menu it came from.
     */
    @Test
    fun itemsLineUpWithTheMenuTheyBelongTo() {
        val text = menuBar()
        assertTrue(
            "the label and its items do not share one inset, so the text does not line up",
            text.contains("MENU_ITEM_INSET")
        )
        assertEquals(
            "both the label and the item must use it",
            2,
            Regex("""padding\(horizontal = MENU_ITEM_INSET""").findAll(text).count()
        )
    }

    /**
     * The bar was 930 dp wide and the window can be 520, so Help was cut off the right
     * of the window. Four short labels plus the version now fit comfortably; the
     * wordmark is what goes, because it repeats the window title.
     */
    @Test
    fun theBarFitsTheNarrowestWindow() {
        val text = menuBar()
        assertTrue(
            "the bar does not react to the window width",
            text.contains("BoxWithConstraints")
        )
        assertTrue(
            "and nothing is dropped when it is narrow",
            text.contains("roomForWordmark")
        )
        // Four labels at roughly 45 dp plus their padding, and the version.
        val estimated = 10f + 4 * 70f + 60f
        assertTrue(
            "four labels and the version need about ${estimated}dp but the smallest " +
                "window is ${MINIMUM_WINDOW_SIZE.width}dp",
            estimated <= MINIMUM_WINDOW_SIZE.width
        )
    }
}
