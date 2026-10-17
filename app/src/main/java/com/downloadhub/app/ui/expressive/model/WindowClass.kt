package com.downloadhub.app.ui.expressive.model

/**
 * How much room the window has. Chosen by the width of the window the app is in, never by
 * the kind of device: a phone in split-screen is compact, a tablet in a narrow window is too,
 * and an unfolded foldable is medium or expanded.
 */
enum class WidthClass {
    /** Under 600 dp: phones. Bottom navigation bar, one pane. */
    COMPACT,

    /** 600 to 839 dp: small tablets, foldables, landscape phones. Collapsed rail, one pane. */
    MEDIUM,

    /** 840 dp and up: tablets, desktop windows. Labelled rail, list beside details. */
    EXPANDED;

    val usesRail: Boolean get() = this != COMPACT
    val showsDetailPane: Boolean get() = this == EXPANDED
}

fun widthClassFor(widthDp: Int): WidthClass = when {
    widthDp < 600 -> WidthClass.COMPACT
    widthDp < 840 -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}
