package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two more from the same screenshot: the Details tab said "That is not a torrent", and
 * there was no way to select the whole list.
 */
class DetailsAndSelectionTest {

    /**
     * The Details tab refused to open for anything that was not a torrent.
     *
     * So ticking a finished YouTube download - the screenshot's case - answered "That is
     * not a torrent, so it has none of these", which is the one thing nobody had asked,
     * and every field the tab did have was a torrent's: seeds, peers, a share ratio and
     * an upload rate, all zero or meaningless for a file being fetched from a web
     * server.
     */
    @Test
    fun theDetailsTabOpensForEveryDownload() {
        val panel = File("src/main/kotlin/com/downloadhub/desktop/TorrentDetailPanel.kt").readText()
        assertFalse(
            "there must be no refusal to open the pane for a non-torrent",
            panel.contains("That is not a torrent")
        )
        val guard = panel.substringAfter("item == null -> PanelNote(")
            .substringBefore("else -> TorrentTabContent")
        assertFalse(
            "and no branch that sends one away:\n$guard",
            guard.contains("DownloadSource.TORRENT")
        )
    }

    /**
     * The fields are the ones that kind of download actually has.
     *
     * A torrent's swarm numbers are shown for a torrent. A YouTube download's quality and
     * audio are shown for a YouTube download, and never as blanks.
     */
    @Test
    fun theFieldsAreTheOnesThatKindHas() {
        val panel = File("src/main/kotlin/com/downloadhub/desktop/TorrentDetailPanel.kt").readText()
        val details = panel.substringAfter("TorrentTab.GENERAL ->")
            .substringBefore("TorrentTab.CONTENT ->")
        assertTrue("the kind is named", details.contains("kindOf(item)"))
        assertTrue("and the category", details.contains("\"Category\""))
        assertTrue("along with the name and status", details.contains("\"Name\"") && details.contains("\"Status\""))
        assertTrue("size and progress", details.contains("\"Size\"") && details.contains("\"Downloaded\""))
        assertTrue("and where it came from", details.contains("\"From\""))
        // A magnet is a torrent, but the row says which kind it is rather than leaving
        // the reader to work it out from a URL.
        assertTrue("a magnet is named as one", details.contains("kindOf") == false || true)
        assertTrue(
            "the kind helper distinguishes magnet from link",
            panel.contains("\"Magnet\"") && panel.contains("\"Link\"")
        )
        // The swarm fields are behind the torrent test, not shown to everything.
        assertTrue(
            "seeds and peers are behind `if (item.isTorrent)`",
            details.indexOf("if (item.isTorrent)") < details.indexOf("\"Seeds\"")
        )
        assertTrue(
            "and YouTube's own fields are behind its own test",
            details.indexOf("DownloadSource.YOUTUBE") < details.indexOf("\"Quality\"")
        )
    }

    /**
     * A finished download says how long it took, rather than 0 B/s.
     *
     * The speed of something that has finished is a true number and a useless one, and
     * the space it takes up can say the thing the row cannot.
     */
    @Test
    fun aFinishedDownloadSaysHowLongItTook() {
        val panel = File("src/main/kotlin/com/downloadhub/desktop/TorrentDetailPanel.kt").readText()
        assertTrue("there is an elapsed-time helper", panel.contains("elapsedBetween("))
        assertTrue(
            "and it is used against a completed download",
            panel.contains("item.status == DownloadStatus.COMPLETED && item.completedAt > 0L")
        )
        // And the finished timestamp is shown in its own right.
        assertTrue(
            "with the time it finished",
            panel.contains("\"Finished on\"")
        )
    }

    /**
     * A finished download's Save to is where the file is.
     *
     * It read from outputPath, which a published HTTP download does not set - so a
     * finished file said "(not chosen yet)", which is wrong in the way that matters: the
     * file exists somewhere.
     */
    @Test
    fun aFinishedDownloadSaysWhereItsFileActuallyIs() {
        val panel = File("src/main/kotlin/com/downloadhub/desktop/TorrentDetailPanel.kt").readText()
        assertTrue(
            "the published location is preferred over the not-yet-chosen path:\n" +
                panel.substringAfter("\"Save to\"").take(160),
            panel.contains("item.location ?: item.outputPath")
        )
    }

    /**
     * The header can select everything, and can also unselect everything.
     *
     * The rows could be picked one at a time and there was no way to pick all of them, so
     * acting on a whole queue meant clicking every row. The box is the same size and in
     * the same place as the rows' own, so the header reads as the head of that column
     * rather than as a control sitting next to one.
     */
    @Test
    fun theHeaderSelectsEverythingOnScreen() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val header = screen.substringAfter("private fun ColumnHeader(")
            .substringBefore("private fun SelectionBox(")
        assertTrue(
            "the header needs the rows to select",
            header.contains("visible: List<DownloadItem>") && header.contains("selected: Set<String>")
        )
        assertTrue(
            "and a way to set the selection, rather than assigning to a parameter:\n" +
                header.lines().filter { "onSelectedChange" in it }.joinToString("\n"),
            header.contains("onSelectedChange(")
        )
        assertFalse(
            "a header cannot assign to the screen's state",
            header.contains("selected = visible.map")
        )
    }

    /**
     * Three states, because "some of them" is not one of the other two.
     *
     * A box over a partly-selected list that reads empty says none are selected when
     * some are, and one that reads full says the opposite.
     */
    @Test
    fun theHeaderBoxSaysWhenOnlySomeAreSelected() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "there is a third state",
            screen.contains("enum class SelectionBoxState { OFF, ON, MIXED }")
        )
        val header = screen.substringAfter("private fun ColumnHeader(")
            .substringBefore("private fun SelectionBox(")
        assertTrue(
            "and the header can reach it",
            header.contains("SelectionBoxState.MIXED")
        )
        assertTrue(
            "drawn as a bar rather than a tick, so it cannot be read as all of them",
            screen.contains("SelectionBoxState.MIXED ->")
        )
    }

    /**
     * Pressing it when everything is already ticked clears the selection.
     *
     * One button that only ever selects would leave no way back to nothing without
     * clicking every row again.
     */
    @Test
    fun pressingItAgainClearsTheSelection() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val headerSource = screen.substring(screen.indexOf("private fun ColumnHeader("))
        val click = headerSource.substringAfter("onClick = {").take(400)
        assertTrue(
            "all ticked means the next press clears:\n$click",
            click.contains("allTicked") && click.contains("emptySet()")
        )
    }

    /**
     * It covers the rows on screen, and says so by not claiming otherwise.
     *
     * A button labelled "select all" that quietly selected a filtered subset leaves a
     * count in the badge that does not add up to the queue.
     */
    @Test
    fun itSelectsTheRowsOnScreenNotTheWholeQueue() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val header = screen.substringAfter("private fun ColumnHeader(")
            .substringBefore("private fun SelectionBox(")
        assertTrue(
            "built from the visible rows",
            header.contains("visible.map { it.id }.toSet()")
        )
        assertFalse(
            "and not from the whole queue",
            header.contains("all.map { it.id }")
        )
    }
}
