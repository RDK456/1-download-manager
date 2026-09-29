package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file list's columns, checked against the widths that actually break them.
 *
 * The list lives in a resizable window now, and every one of these tests is a bug that was
 * in the first version of it. A layout with fixed column widths is only ever correct at
 * one window size; these are the assertions that make it correct at all of them, and the
 * one that stopped it from over-correcting.
 */
class ContentTreeLayoutTest {

    /**
     * The columns never overflow the pane, at any width.
     *
     * The original bug: the four numeric columns were 260 dp of fixed widths and the name
     * took whatever was left. Drag the window narrower than 260 dp plus the name's minimum
     * and Compose gave the name zero and then overflowed the row - the size, the progress
     * and the remaining were drawn past the right-hand edge and simply gone. A resizable
     * window whose contents vanish when you resize it is not resizable.
     */
    @Test
    fun `the columns never take more than the pane has`() {
        var width = 120f
        while (width <= 2000f) {
            val cols = TreeLayout.columnsFor(width)
            val room = (width - TreeLayout.INDENT_DP - TreeLayout.NAME_MIN_DP).coerceAtLeast(0f)
            assertTrue(
                "at ${width}dp the columns total ${cols.fixedTotal} but there is only " +
                    "${room}dp for them",
                cols.fixedTotal <= room + 0.01f
            )
            width += 7f
        }
    }

    /**
     * Spare room goes to the name.
     *
     * This is the one the first version got wrong in the other direction. The columns were
     * fitted by sharing the room between them, so a wide window made a 227 dp Size column
     * and a 271 dp Pri column, the name was left its 118 dp minimum, and every filename
     * read "[Judas] Chainsaw" beside four hundred spare pixels. A number has a natural
     * width; a filename does not, so the surplus belongs to the filename.
     */
    @Test
    fun `a wide pane gives the surplus to the name rather than to the numbers`() {
        val cols = TreeLayout.columnsFor(1500f)
        assertEquals(TreeLayout.SIZE_NATURAL_DP, cols.size, 0.01f)
        assertEquals(TreeLayout.PROGRESS_NATURAL_DP, cols.progress, 0.01f)
        assertEquals(TreeLayout.PRIORITY_NATURAL_DP, cols.priority, 0.01f)
        assertEquals(TreeLayout.REMAINING_NATURAL_DP, cols.remaining, 0.01f)
    }

    /** And no column is ever wider than it needs to be, at any width. */
    @Test
    fun `no column is wider than its natural width`() {
        var width = 60f
        while (width <= 2000f) {
            val cols = TreeLayout.columnsFor(width)
            assertTrue("at ${width}dp the size column is ${cols.size}dp", cols.size <= TreeLayout.SIZE_NATURAL_DP + 0.01f)
            assertTrue("at ${width}dp progress is ${cols.progress}dp", cols.progress <= TreeLayout.PROGRESS_NATURAL_DP + 0.01f)
            assertTrue("at ${width}dp priority is ${cols.priority}dp", cols.priority <= TreeLayout.PRIORITY_NATURAL_DP + 0.01f)
            assertTrue("at ${width}dp remaining is ${cols.remaining}dp", cols.remaining <= TreeLayout.REMAINING_NATURAL_DP + 0.01f)
            width += 11f
        }
    }

    /**
     * Nothing is drawn that cannot be read.
     *
     * A column squeezed below the width of its own text shows "Nor" - which reads as a
     * truncated word rather than a deliberate omission, and is worse than no column. So a
     * column that is too narrow for its label is not drawn, and the header does not draw
     * it either: a heading over a gap is a heading pointing at nothing.
     */
    @Test
    fun `a column too narrow for its own text is not drawn`() {
        // Somewhere in the middle of the range where priority is part-way.
        val cols = TreeLayout.columnsFor(420f)
        if (!cols.showsPriority) assertTrue("priority claimed to be hidden but is ${cols.priority}dp", cols.priority < TreeLayout.PRIORITY_MIN_DP)
        if (cols.showsPriority) assertTrue("priority shown at ${cols.priority}dp", cols.priority >= TreeLayout.PRIORITY_MIN_DP)
        if (!cols.showsRemaining) assertTrue("remaining claimed hidden but is ${cols.remaining}dp", cols.remaining < TreeLayout.REMAINING_MIN_DP)
        if (cols.showsRemaining) assertTrue("remaining shown at ${cols.remaining}dp", cols.remaining >= TreeLayout.REMAINING_MIN_DP)
    }

    /**
     * Priority goes first, then what is left to arrive, then progress.
     *
     * In that order, because that is what each column is worth. Every row says "Normal"
     * for priority in this build, so it is forty identical words. What is still to arrive
     * is the size restated, so it is the first number to become unreadable. Progress, at
     * 0%, is not yet true of anything.
     *
     * The widths below are the measured crossovers rather than round numbers: the columns
     * hold their natural widths down to about 418 dp of pane, priority drops below 380,
     * and what is left to arrive drops below 290.
     */
    @Test
    fun `columns are given up in order of how little they are worth`() {
        // Wide: everything.
        val wide = TreeLayout.columnsFor(900f)
        assertTrue("a wide pane should keep every column", wide.showsPriority && wide.showsRemaining && wide.showsProgress)

        // 418 dp is where the natural widths stop fitting, so this is the last full one.
        val stillFull = TreeLayout.columnsFor(418f)
        assertTrue("at 418dp the columns still fit at their natural widths", stillFull.showsPriority)

        // Narrower: priority is the first to go.
        val noPriority = TreeLayout.columnsFor(370f)
        assertFalse("priority should be the first column dropped", noPriority.showsPriority)
        assertTrue("what is left to arrive should outrank priority", noPriority.showsRemaining)
        assertTrue(noPriority.showsProgress)

        // Narrower still: what is left to arrive goes too, then progress.
        val noRemaining = TreeLayout.columnsFor(280f)
        assertFalse(noRemaining.showsPriority)
        assertFalse("remaining should go before progress", noRemaining.showsRemaining)
        assertTrue(noRemaining.showsProgress)
        assertTrue("the size is the one number always worth showing", noRemaining.size > 0f)
    }

    /**
     * The size column is the last thing standing.
     *
     * It is the number that tells you whether the file is worth having, and it is the one
     * that fits in the least room: forty-six dp against a filename's hundred and eighteen.
     * Below that it is all that is left, and below *that* - below about 204 dp of pane,
     * narrower than the window's own minimum - everything is zero, which is a layout rather
     * than a crash.
     */
    @Test
    fun `the size column is the last thing standing`() {
        // Below 236 dp of pane the progress column no longer fits beside it, and the size
        // is the only number left.
        for (width in listOf(235f, 230f, 220f, 210f, 205f)) {
            val cols = TreeLayout.columnsFor(width)
            assertTrue("the size column vanished at ${width}dp", cols.size > 0f)
            assertFalse(
                "nothing but the size should be drawn at ${width}dp, but progress, " +
                    "remaining and priority were all present",
                cols.showsProgress || cols.showsRemaining || cols.showsPriority
            )
        }
        // Below the useful floor it degrades to nothing rather than to negative widths.
        val hopeless = TreeLayout.columnsFor(158f)
        assertEquals("a 158dp pane should draw nothing rather than negative widths", 0f, hopeless.fixedTotal, 0.001f)
    }

    /**
     * The window's minimum size gives a layout worth having.
     *
     * The pre-download window's floor is 620 px; the options column takes 250 of it and the
     * file list what is left - about 320 dp, which is past the point where priority has
     * gone, so the name, the size, the progress and what is left to arrive all fit. The
     * floor is chosen from this: any narrower, and the user arrives at the floor to find a
     * column already missing, which is the complaint the whole change exists to answer.
     */
    @Test
    fun `the layout at the window's minimum size still shows a name, a size and a progress`() {
        val options = 250f
        val list = 620f - 36f - options - 14f
        val cols = TreeLayout.columnsFor(list)
        assertTrue(
            "at the window's minimum the list is ${list}dp wide, which should still show " +
                "the size, the progress and what is left to arrive",
            cols.size > 0f && cols.showsProgress && cols.showsRemaining
        )
        // The name gets whatever the numbers do not use, and never less than its minimum.
        val nameGets = list - TreeLayout.INDENT_DP - cols.fixedTotal
        assertTrue(
            "the name is left $nameGets dp, which is under its ${TreeLayout.NAME_MIN_DP}dp minimum",
            nameGets >= TreeLayout.NAME_MIN_DP - 0.01f
        )
    }

    /** Degenerate widths must not produce negative or NaN sizes. */
    @Test
    fun `a width of zero or less is survivable`() {
        for (width in listOf(0f, -1f, -500f)) {
            val cols = TreeLayout.columnsFor(width)
            assertTrue(cols.size >= 0f && cols.progress >= 0f && cols.priority >= 0f && cols.remaining >= 0f)
            assertFalse(cols.size.isNaN() || cols.progress.isNaN() || cols.priority.isNaN() || cols.remaining.isNaN())
        }
    }

    /** Fitting to nothing must not divide by zero and produce infinity. */
    @Test
    fun `the fitter survives a room of zero`() {
        val fitted = TreeLayout.fit(floatArrayOf(78f, 50f), floatArrayOf(46f, 32f), 0f)
        assertEquals(0f, fitted[0], 0.001f)
        assertEquals(0f, fitted[1], 0.001f)
    }
}
