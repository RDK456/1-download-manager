package com.downloadhub.desktop

/**
 * How wide the file list's columns are, given how wide the list has become.
 *
 * The pre-download window is a real, resizable window, so "how wide is the file list" is
 * a question with an answer that changes while the user drags. It used to be answered
 * once: four fixed widths, 260 dp of numbers beside a name that took whatever was left.
 * Drag the window narrower than that and the numbers no longer fit, so Compose gave the
 * name zero and then overflowed the row - the size, the progress and the remaining
 * columns were drawn past the right-hand edge and simply gone. A resizable window whose
 * contents vanish when you resize it is not resizable.
 *
 * So the numbers are fitted to the space rather than assumed to fit it. The name keeps a
 * minimum, because a filename is the one thing the list exists to show; the numbers share
 * what is left, and the ones that matter least go first.
 */
data class TreeColumns(
    val size: Float,
    val progress: Float,
    val priority: Float,
    val remaining: Float
) {
    /** The width the name gets by not being asked for: it fills whatever is not here. */
    val fixedTotal: Float get() = size + progress + priority + remaining

    /**
     * Whether a priority column is drawn at all.
     *
     * A column squeezed to a few dp shows "Nor", which is worse than no column: it looks
     * like a truncated word rather than a deliberate omission. Below the width of the word
     * itself, the column is not drawn.
     */
    val showsPriority: Boolean get() = priority >= TreeLayout.PRIORITY_MIN_DP

    val showsRemaining: Boolean get() = remaining >= TreeLayout.REMAINING_MIN_DP

    val showsProgress: Boolean get() = progress >= TreeLayout.PROGRESS_MIN_DP
}

/**
 * The file list's column fitting.
 *
 * Pure arithmetic, so it can be tested against the widths that actually break the layout
 * rather than only against the one the screenshot happened to have.
 */
object TreeLayout {

    /**
     * The row furniture in front of the name: the tick box, the disclosure triangle, the
     * gap between them and the row's own end padding.
     */
    const val INDENT_DP = 40f

    /**
     * What the name is allowed to shrink to.
     *
     * 118 dp is about a dozen characters of an eleven-sp filename, so a file is still
     * identifiable by its beginning and by its size - which is the point of showing both
     * halves of the row.
     */
    const val NAME_MIN_DP = 118f

    const val SIZE_NATURAL_DP = 78f
    const val SIZE_FLOOR_DP = 46f
    const val PROGRESS_NATURAL_DP = 50f
    const val PROGRESS_FLOOR_DP = 32f

    /**
     * Priority is the first to go, and it has no floor.
     *
     * Every row says "Normal" in this build, because per-file priorities are not
     * implemented. A column of forty identical words is not information, so it is the one
     * worth losing before the size or what is left to arrive.
     */
    const val PRIORITY_NATURAL_DP = 48f
    const val PRIORITY_MIN_DP = 36f

    const val REMAINING_NATURAL_DP = 84f
    const val REMAINING_FLOOR_DP = 46f
    const val REMAINING_MIN_DP = 42f

    const val PROGRESS_MIN_DP = 28f

    /**
     * The columns for a list [availableDp] wide.
     *
     * Fits in three steps, in the order the information matters:
     *  1. every column shrinks toward its floor, in proportion to how much it wants above
     *     it, so a wide name and a wide size are not stolen from equally;
     *  2. remaining is dropped, which is the size restated as what is left and is the
     *     first number to be unreadable when the window is narrow;
     *  3. progress goes, which at 0% is not yet true of anything.
     *
     * The total never exceeds what there is room for, so a row cannot overflow its own
     * pane however narrow the window is dragged.
     */
    fun columnsFor(availableDp: Float): TreeColumns {
        val room = (availableDp - INDENT_DP - NAME_MIN_DP).coerceAtLeast(0f)

        val allNatural = SIZE_NATURAL_DP + PROGRESS_NATURAL_DP + PRIORITY_NATURAL_DP + REMAINING_NATURAL_DP
        if (room >= allNatural) {
            // Everything at its natural width, and the surplus goes to the name.
            //
            // This branch is the one that used to be missing, and its absence was worse
            // than any of the problems it was meant to fix. Fitting the columns to the
            // room by sharing it *proportionally* meant that a wide window made them all
            // wider - a 227 dp Size column, a 271 dp Pri column - until the name had only
            // its 118 dp minimum left and every filename was cut to "[Judas] Chainsaw"
            // on a window with four hundred spare pixels beside it. Spare room belongs to
            // the name, which is the only column whose content has no natural width.
            return TreeColumns(SIZE_NATURAL_DP, PROGRESS_NATURAL_DP, PRIORITY_NATURAL_DP, REMAINING_NATURAL_DP)
        }

        val allFloors = SIZE_FLOOR_DP + PROGRESS_FLOOR_DP + REMAINING_FLOOR_DP
        if (room >= allFloors) {
            val fitted = fit(
                naturals = floatArrayOf(
                    SIZE_NATURAL_DP, PROGRESS_NATURAL_DP, PRIORITY_NATURAL_DP, REMAINING_NATURAL_DP
                ),
                floors = floatArrayOf(SIZE_FLOOR_DP, PROGRESS_FLOOR_DP, 0f, REMAINING_FLOOR_DP),
                room = room
            )
            return TreeColumns(fitted[0], fitted[1], fitted[2], fitted[3])
        }
        if (room >= SIZE_FLOOR_DP + PROGRESS_FLOOR_DP) {
            // Size and progress alone, and they take all of it between their floors and
            // their natural widths.
            val fitted = fit(
                naturals = floatArrayOf(SIZE_NATURAL_DP, PROGRESS_NATURAL_DP),
                floors = floatArrayOf(SIZE_FLOOR_DP, PROGRESS_FLOOR_DP),
                room = room
            )
            return TreeColumns(fitted[0], fitted[1], 0f, 0f)
        }
        if (room >= SIZE_FLOOR_DP) {
            return TreeColumns(fit(floatArrayOf(SIZE_NATURAL_DP), floatArrayOf(SIZE_FLOOR_DP), room)[0], 0f, 0f, 0f)
        }
        // Narrower than one number and a filename: the size is all that survives, and it
        // is scaled down rather than dropped, so there is still something to read.
        return TreeColumns(room, 0f, 0f, 0f)
    }

    /**
     * Distributes [room] across [naturals], never going below a column's [floors] value and
     * never exceeding [room] in total.
     *
     * Every column starts at its floor and shares what is left in proportion to how much
     * it wanted above the floor. That is why a narrow window does not take the same bite
     * out of a 78 dp size column and a 48 dp priority column: they asked for different
     * amounts, so they lose different amounts.
     */
    internal fun fit(naturals: FloatArray, floors: FloatArray, room: Float): FloatArray {
        val out = floors.copyOf()
        val floorTotal = floors.sum()
        if (room <= 0f) {
            java.util.Arrays.fill(out, 0f)
            return out
        }
        if (floorTotal > room) {
            // Not even the floors fit. Share what there is in proportion to the floors, so
            // the biggest column keeps the biggest share rather than being clipped by the
            // pane's edge.
            for (i in out.indices) out[i] = floors[i] * (room / floorTotal)
            return out
        }
        val spare = room - floorTotal
        val want = FloatArray(naturals.size) { (naturals[it] - floors[it]).coerceAtLeast(0f) }
        val wantTotal = want.sum()
        if (wantTotal <= 0f) return out
        for (i in out.indices) out[i] += want[i] * (spare / wantTotal)
        return out
    }
}
