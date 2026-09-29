package com.downloadhub.desktop

import com.downloadhub.core.DownloadColumn

/**
 * Column widths the user has dragged, per column.
 *
 * The name column is stored as a *share of the table's width* rather than as a dp value.
 * That is the whole trick that makes a dragged column survive the window being resized,
 * and it is why this is not just a `Map<DownloadColumn, Dp>`: a name column dragged to
 * 600 dp is a deliberate choice on a 1200 dp window, and re-asking for 600 dp on a 700 dp
 * window would leave it wider than the table and push every other column off the edge.
 * As a share, "the name takes about half" means the same thing at any size.
 */
data class ColumnWidths(
    /** The name column's share of the table width, between [MIN_NAME_SHARE] and 0.9. */
    val nameShare: Float = DEFAULT_NAME_SHARE,
    /** Overrides for the fixed columns, in dp. Zero or absent means the built-in width. */
    val overrides: Map<DownloadColumn, Float> = emptyMap()
) {
    /** The width of one column, given the table's width. */
    fun widthOf(column: DownloadColumn, tableDp: Float): Float {
        overrides[column]?.let { return it }
        return when (column) {
            // Everything the name does not take is the fixed columns plus the row's own
            // chrome, and the chrome is not a column.
            DownloadColumn.NAME -> tableDp * nameShare
            else -> DEFAULT_WIDTHS[column] ?: 90f
        }
    }

    /** Every column's width, in the order they appear. */
    fun all(tableDp: Float): Map<DownloadColumn, Float> =
        DownloadColumn.entries.associateWith { widthOf(it, tableDp) }

    companion object {
        const val DEFAULT_NAME_SHARE = 0.5f

        /**
         * The narrowest the name column may be dragged to, as a share.
         *
         * Below about a fifth of the table a filename is a single truncated character and
         * the row cannot be identified at all, so the drag stops there rather than letting
         * the column disappear.
         */
        const val MIN_NAME_SHARE = 0.2f

        /** The narrowest any fixed column may be dragged to, in dp. */
        const val MIN_COLUMN_DP = 44f

        /** The narrowest the name column may be dragged to, in dp, whatever the share. */
        const val MIN_NAME_DP = 90f

        /** The built-in widths of the columns that are not the name. */
        val DEFAULT_WIDTHS: Map<DownloadColumn, Float> = mapOf(
            DownloadColumn.SIZE to 90f,
            DownloadColumn.STATUS to 110f,
            DownloadColumn.SPEED to 95f,
            DownloadColumn.TIME_LEFT to 90f,
            DownloadColumn.DATE_ADDED to 110f
        )

        val DEFAULT = ColumnWidths()
    }
}

/** Which column a drag at this x position would move, and how far in. */
sealed interface ResizeTarget {
    /** Not over a divider, so the drag should not start. */
    data object None : ResizeTarget

    /** Over the divider on the right-hand edge of [column]. */
    data class Handle(val column: DownloadColumn) : ResizeTarget
}

/**
 * Where the column dividers are, given the layout and the widths in force.
 *
 * A function over numbers rather than a composable, so the arithmetic that decides whether
 * a drag lands on a divider can be tested without a window - which matters, because the
 * version of this that was laid out by hand had the header and the rows disagreeing by
 * four pixels, and nothing caught it.
 */
object ColumnDividers {

    /**
     * The x offset, relative to the table's left edge, of each column's right-hand edge.
     *
     * The name comes first and takes its share; then the rest in order, each at its own
     * width, skipping any the current layout has dropped. The last one ends at the table's
     * right edge, which is why the rightmost divider is at `tableDp` rather than at the
     * sum of the widths.
     */
    fun offsets(layout: TableLayout, widths: ColumnWidths, tableDp: Float): Map<DownloadColumn, Float> {
        val result = LinkedHashMap<DownloadColumn, Float>()
        var x = 0f
        val order = listOf(
            DownloadColumn.NAME,
            DownloadColumn.SIZE,
            DownloadColumn.STATUS,
            DownloadColumn.SPEED,
            DownloadColumn.TIME_LEFT,
            DownloadColumn.DATE_ADDED
        )
        order.forEach { column ->
            if (!isShown(column, layout)) return@forEach
            x += widths.widthOf(column, tableDp)
            result[column] = x
        }
        return result
    }

    /** Is this column on screen at all, given what the layout has dropped? */
    fun isShown(column: DownloadColumn, layout: TableLayout): Boolean = when (column) {
        DownloadColumn.NAME -> true
        DownloadColumn.SIZE -> true
        DownloadColumn.STATUS -> layout.showStatus
        DownloadColumn.SPEED -> layout.showSpeed
        DownloadColumn.TIME_LEFT -> layout.showTimeLeft
        DownloadColumn.DATE_ADDED -> layout.showDateAdded
    }

    /**
     * Which divider is within [grabDp] of this x position, if any.
     *
     * The nearest one wins rather than the first, so a drag that passes over a divider
     * does not suddenly grab a different column on the way.
     */
    fun targetAt(
        xDp: Float,
        layout: TableLayout,
        widths: ColumnWidths,
        tableDp: Float,
        grabDp: Float = 6f
    ): ResizeTarget {
        val offsets = offsets(layout, widths, tableDp)
        var best: DownloadColumn? = null
        var bestDistance = grabDp
        offsets.forEach { (column, offset) ->
            val distance = kotlin.math.abs(offset - xDp)
            if (distance <= bestDistance) {
                bestDistance = distance
                best = column
            }
        }
        return best?.let { ResizeTarget.Handle(it) } ?: ResizeTarget.None
    }

    /**
     * Applies a drag.
     *
     * Clamped rather than accepted as given, because the divider can be dragged past
     * either end: past the far side it would push the following columns off the table
     * entirely, and below its own minimum the header would stop saying what the column is.
     */
    fun dragged(
        widths: ColumnWidths,
        column: DownloadColumn,
        toX: Float,
        tableDp: Float,
        layout: TableLayout
    ): ColumnWidths {
        val current = widths.widthOf(column, tableDp)
        val startOffset = offsets(layout, widths, tableDp)[column] ?: current
        val proposed = current + (toX - startOffset)

        return if (column == DownloadColumn.NAME) {
            // Two floors, and the stricter one wins.
            //
            // The share floor keeps the name from being squeezed out on a wide window; the
            // absolute floor keeps it readable on a narrow one, where a fifth of a 300 dp
            // table is 60 dp - one truncated character. Clamping the share alone satisfied
            // the first and silently failed the second.
            val shareFloor = ColumnWidths.MIN_NAME_SHARE
            val shareCeiling = 0.9f
            val absoluteFloor = (ColumnWidths.MIN_NAME_DP / tableDp).coerceAtMost(shareCeiling)
            val floor = maxOf(shareFloor, absoluteFloor)
            widths.copy(nameShare = (proposed / tableDp).coerceIn(floor, shareCeiling))
        } else {
            widths.copy(
                overrides = widths.overrides + (column to
                    proposed.coerceAtLeast(ColumnWidths.MIN_COLUMN_DP))
            )
        }
    }
}
