package com.downloadhub.desktop

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the download table fits the space it has been given.
 *
 * The columns used to have fixed widths totalling 841 dp, next to a 230 dp sidebar.
 * That is 1071 dp before anything is padded, and the window could be resized narrower
 * than that, so the right-hand columns were simply clipped off the edge and the toolbar
 * lost its last three buttons. The window has a minimum and can be resized smaller
 * still, so the layout has to decide what to give up instead.
 *
 * The name is what the row is *about*, so it is the last thing to go: it takes the space
 * the fixed columns leave. Everything else is dropped from the right, in the order of
 * least to most useful in a narrow window - a countdown nobody can read is less use than
 * a status, and both are less use than the size.
 *
 * This is a plain function over a number of dp, rather than a composable, so the
 * decisions can be tested without a window. Which is also how they are checked: the
 * thresholds below are computed from the widths themselves rather than written down
 * twice, because the first version of this had thresholds that were a hundred dp out
 * and clipped the row at 620 dp.
 */

/**
 * The header's fixed height, in dp.
 *
 * It has to be fixed because a resize handle inside it fills the header's height, and
 * `fillMaxHeight` inside a row with no height constraint resolves against the incoming
 * *maximum* - which is the whole table. The header then became as tall as the list, the
 * dividers ran the full height of the window, and every row and the status bar were
 * pushed off the bottom with no error anywhere.
 */
const val HEADER_HEIGHT_DP = 30f

/** A row's checkbox and the padding down both sides of it. */
const val ROW_CHROME_DP = 26f + 16f

/**
 * The row action at the right-hand end.
 *
 * Two 16 dp buttons with a gap between them: the status action - pause, resume, retry
 * or show in folder - and the per-download options. The options button is always shown
 * rather than only on running rows, because share limits are wanted on a finished
 * download and everything else on a paused one.
 */
const val ROW_ACTION_DP = 52f

/**
 * The name ellipsises rather than the row wrapping to two lines, which is what keeps
 * rows a fixed height. Past this it stops being a filename and starts being a
 * placeholder, so the columns give way instead.
 */
const val NAME_MINIMUM_DP = 100f

/**
 * The narrowest a table can be and still show a name.
 *
 * Below this there is nothing left to drop - the columns are already all gone and only
 * the name remains - so the window has a minimum of its own rather than letting the
 * list shrink to a single truncated word.
 */
const val MINIMUM_TABLE_DP = ROW_CHROME_DP + NAME_MINIMUM_DP

data class TableLayout(
    val showStatus: Boolean,
    val showSpeed: Boolean,
    val showTimeLeft: Boolean,
    val showDateAdded: Boolean,
    val showRowActions: Boolean,
    /** Fixed width of each column; the name column takes the remainder. */
    val size: Dp,
    val status: Dp,
    val speed: Dp,
    val timeLeft: Dp,
    val dateAdded: Dp
) {
    /**
     * What the row needs in total, name included.
     *
     * A hidden column contributes nothing at all - not even its padding - so the header
     * and the row stay aligned when one of them drops a column.
     */
    val requiredDp: Float
        get() = ROW_CHROME_DP + NAME_MINIMUM_DP + (if (size.value > 0f) size.value else 0f) +
            (if (showStatus) status.value else 0f) +
            (if (showSpeed) speed.value else 0f) +
            (if (showTimeLeft) timeLeft.value else 0f) +
            (if (showDateAdded) dateAdded.value else 0f) +
            (if (showRowActions) ROW_ACTION_DP else 0f)

    /** The sidebar is a fixed share of the window; below this it is too much of it. */
    val narrowSidebar: Boolean get() = !showTimeLeft
}

/** Column widths when there is room for everything. */
private val WIDE = TableLayout(
    showStatus = true, showSpeed = true, showTimeLeft = true, showDateAdded = true,
    showRowActions = true,
    size = 90.dp, status = 110.dp, speed = 95.dp, timeLeft = 90.dp, dateAdded = 110.dp
)

/** Speed and countdown are gone; the rest stays. */
private val MEDIUM = TableLayout(
    showStatus = true, showSpeed = false, showTimeLeft = false, showDateAdded = true,
    showRowActions = true,
    size = 90.dp, status = 110.dp, speed = 0.dp, timeLeft = 0.dp, dateAdded = 110.dp
)

/** Size and status only. */
private val NARROW = TableLayout(
    showStatus = true, showSpeed = false, showTimeLeft = false, showDateAdded = false,
    showRowActions = true,
    size = 90.dp, status = 110.dp, speed = 0.dp, timeLeft = 0.dp, dateAdded = 0.dp
)

/** Only the name. */
private val COMPACT = TableLayout(
    showStatus = false, showSpeed = false, showTimeLeft = false, showDateAdded = false,
    showRowActions = false,
    size = 0.dp, status = 0.dp, speed = 0.dp, timeLeft = 0.dp, dateAdded = 0.dp
)

/**
 * Picks a layout for the width available to the table.
 *
 * [availableDp] is the content area, not the whole window: the sidebar and its rule come
 * off first, because a column that fits beside a 230 dp sidebar does not fit beside a
 * 150 dp one and the window is the width that is actually known.
 *
 * The thresholds are where each layout stops fitting, read off the layouts themselves
 * so they cannot drift out of step with the widths.
 */
fun tableLayoutFor(availableDp: Float): TableLayout = when {
    availableDp >= WIDE.requiredDp -> WIDE
    availableDp >= MEDIUM.requiredDp -> MEDIUM
    availableDp >= NARROW.requiredDp -> NARROW
    else -> COMPACT
}

/**
 * The width the sidebar should take.
 *
 * A fixed 230 dp is a third of a 700 dp window, which leaves nothing for the thing the
 * window is for. It narrows before anything is dropped from the table, because a
 * sidebar you can still read is worth more than a speed column - and at the bottom it
 * goes to 120 dp, which is what keeps the narrow layouts reachable at all.
 */
fun sidebarWidthFor(windowDp: Float): Dp = when {
    windowDp >= 1000f -> 230.dp
    windowDp >= 780f -> 190.dp
    windowDp >= 600f -> 160.dp
    else -> 120.dp
}

// --- the toolbar ------------------------------------------------------------
//
// Nine captioned buttons are 666 dp on their own, and with the search box the row was
// 982 dp wide - which is more than the table has even in the window's default size. The
// overflow was cut off the right, taking Delete, Downloads and Settings with it, and
// that happened at 1180 dp rather than only on a deliberately small window.
//
// So the toolbar gives things up in three steps rather than one: the search box goes
// first because it is the one thing here that is not a button, then the captions go but
// the buttons stay. The search box takes whatever is left rather than a fixed 260 dp,
// so it is never the thing that overflows.

/** How much of the toolbar is showing. */
enum class ToolbarStyle {
    /** Captions and a search box. */
    FULL,

    /** Captions, no search box - there was not enough room for one worth typing in. */
    CAPTIONED,

    /** Icons only. The tooltips still name them. */
    COMPACT
}

/** A captioned toolbar button at its natural width. */
const val CAPTION_BUTTON_DP = 74f

/**
 * The narrowest a captioned button may get before the caption is dropped.
 *
 * Measured from the longest label: "New Download" at 10 sp is about 65 dp, so 68 leaves
 * it a little room rather than sitting exactly on the edge. Below this the caption
 * ellipsises, and a row of truncated captions is worse than a row of icons.
 */
const val CAPTION_FLOOR_DP = 68f

/** An icon-only button: room for the 30 dp target and nothing more. */
const val COMPACT_BUTTON_DP = 38f

/**
 * The toolbar caption's line height, in sp.
 *
 * Deliberately larger than the 10 sp of text it sets. The caption was clipped along its
 * bottom edge on every button at once, all by the same few pixels, which is the signature
 * of a line box measured from font metrics that fall short of the glyphs rather than of a
 * button that is too small - giving the caption a fixed box made it worse, not better.
 *
 * So the line box is made taller than the text instead. This is the only value here that
 * is about the caption; the button's own height is measured from its contents, because a
 * button with a height constraint just moves the clip somewhere else.
 */
const val TOOLBAR_CAPTION_LINE_HEIGHT_SP = 16f

const val TOOLBAR_BUTTON_COUNT = 9
const val TOOLBAR_GAP_DP = 4f
const val TOOLBAR_PADDING_DP = 20f

/** The search box never grows past this, however wide the window is. */
const val SEARCH_MAX_DP = 260f

/**
 * Below this the box is too small to type a filename into, so it is hidden rather than
 * left as a sliver that looks broken.
 */
const val SEARCH_MIN_DP = 140f

/** The eight gaps between nine buttons, plus the toolbar's own horizontal padding. */
val TOOLBAR_CHROME_DP = (TOOLBAR_BUTTON_COUNT - 1) * TOOLBAR_GAP_DP + TOOLBAR_PADDING_DP

/** Captions and a search box, both at their preferred widths. */
val TOOLBAR_FULL_DP =
    TOOLBAR_CHROME_DP + TOOLBAR_GAP_DP + TOOLBAR_BUTTON_COUNT * CAPTION_BUTTON_DP + SEARCH_MIN_DP

/**
 * The narrowest the bar can be and still show captions on every button.
 *
 * Nine buttons each [CAPTION_FLOOR_DP] wide, their gaps and the toolbar's padding.
 * Between this and [TOOLBAR_FULL_DP] the captions stay and the search box goes; below
 * it the captions go too, because a truncated caption is worse than an icon.
 */
val TOOLBAR_CAPTIONED_MIN_DP =
    TOOLBAR_CHROME_DP + TOOLBAR_GAP_DP + TOOLBAR_BUTTON_COUNT * CAPTION_FLOOR_DP

/**
 * How much of the toolbar is showing, and how wide each button ended up.
 *
 * The button width is worked out rather than assumed, which is what stops this being a
 * threshold that happens to be right today: a 900 dp window has room for captioned
 * buttons 72 dp wide each, and a flat 74 dp would have thrown the captions away in
 * about 60 dp of empty space - which is what the first version of this did.
 */
data class ToolbarLayout(
    val style: ToolbarStyle,
    val buttonDp: Float,
    /** Whether the search box is shown; it then takes whatever is left over. */
    val showsSearch: Boolean
) {
    /** What the row adds up to, not counting the flexible search box. */
    val requiredDp: Float
        get() = TOOLBAR_CHROME_DP + TOOLBAR_BUTTON_COUNT * buttonDp +
            (if (showsSearch) TOOLBAR_GAP_DP else 0f)
}

fun toolbarLayoutFor(availableDp: Float): ToolbarLayout {
    // What each button would get if the search box got nothing.
    val share = (availableDp - TOOLBAR_CHROME_DP - TOOLBAR_GAP_DP) / TOOLBAR_BUTTON_COUNT
    return when {
        availableDp < TOOLBAR_CAPTIONED_MIN_DP ->
            ToolbarLayout(ToolbarStyle.COMPACT, COMPACT_BUTTON_DP, false)

        availableDp >= TOOLBAR_FULL_DP ->
            ToolbarLayout(ToolbarStyle.FULL, CAPTION_BUTTON_DP, true)

        else -> ToolbarLayout(ToolbarStyle.CAPTIONED, share, false)
    }
}

/** How much of the toolbar there is room for, without the detail of button widths. */
fun toolbarStyleFor(availableDp: Float): ToolbarStyle = toolbarLayoutFor(availableDp).style
