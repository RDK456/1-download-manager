package com.downloadhub.desktop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table's header must not grow.
 *
 * A resize handle inside the header fills the header's height. `fillMaxHeight` inside a
 * row with no height constraint resolves against the incoming *maximum*, which is the
 * whole table - so the header became as tall as the list, the drag handles' dividers ran
 * the full height of the window, and every row and the status bar were pushed off the
 * bottom with nothing thrown and nothing logged. It looked like an empty download list.
 *
 * It is checked by reading the source because that is the only place the mistake is
 * visible: a header's height is not something a unit test can measure without a window,
 * but whether the height is *constrained* is exactly what went wrong.
 */
class HeaderHeightTest {

    private val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()

    private fun headerBody(): String = screen
        .substringAfter("private fun ColumnHeader(")
        .substringAfter(") {")
        .substringBefore("private fun ResizeHandle(")

    @Test
    fun theHeaderRowHasAFixedHeight() {
        val body = headerBody()
        assertTrue(
            "the header row must carry a fixed height, or the handles inside it fill " +
                "the table:\n$body",
            body.contains("height(HEADER_HEIGHT_DP.dp)")
        )
    }

    @Test
    fun theHeightComesFromOneNamedConstant() {
        // A literal here and a different one in the layout would drift, and the bug it
        // guards is invisible until the window is a particular size.
        assertTrue(
            "the header height is declared in LibraryLayout, not inline",
            screen.contains("HEADER_HEIGHT_DP") &&
                !headerBody().contains("height(HEADER_HEIGHT_DP)") &&
                !headerBody().contains("height(32.dp)")
        )
    }

    @Test
    fun theHandleFillsTheHeaderAndNotTheWindow() {
        val handle = screen
            .substringAfter("private fun ResizeHandle(")
            .substringBefore("private fun ColumnHeaderCell(")
        assertTrue(
            "the handle should fill whatever height its row has, which is now bounded",
            handle.contains("fillMaxHeight()")
        )
        // And nothing above it may hand it an unbounded height again.
        assertTrue(
            "the handle must not ask for the window's height directly",
            !handle.contains("fillMaxSize()")
        )
    }

    @Test
    fun theListAndTheStatusBarStillFollowTheHeader() {
        val body = screen.substringAfter("ColumnHeader(").substringBefore("StatusBar(state, all)")
        assertTrue(
            "the list is a weighted child of the same column as the header, so it takes " +
                "what is left rather than being pushed off the bottom",
            body.contains("Modifier.weight(1f)")
        )
    }
}
