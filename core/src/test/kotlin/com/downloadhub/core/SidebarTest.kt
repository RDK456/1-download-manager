package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidebar.
 *
 * The rail used to be assembled inside the Windows composable, in two halves - the
 * categories, then the groups under some headings - and the two halves printed the same
 * words. "Finished" appeared as a heading and again as a row; "Unfinished" likewise. It
 * also had no way to reach anything that had failed, or everything that was running,
 * except by sorting the table.
 */
class SidebarTest {

    private fun item(
        id: String,
        status: DownloadStatus,
        category: DownloadCategory = DownloadCategory.DOCUMENT,
        url: String = "https://example.com/$id.zip"
    ) = DownloadItem(
        id = id,
        url = url,
        fileName = "$id.zip",
        source = if (url.startsWith("magnet:")) DownloadSource.TORRENT else DownloadSource.HTTP,
        category = category,
        status = status
    )

    private val mixed = listOf(
        item("a", DownloadStatus.RUNNING),
        item("b", DownloadStatus.RUNNING),
        item("c", DownloadStatus.QUEUED),
        item("d", DownloadStatus.RESOLVING),
        item("e", DownloadStatus.PAUSED),
        item("f", DownloadStatus.PAUSED),
        item("g", DownloadStatus.FAILED),
        item("h", DownloadStatus.COMPLETED),
        item("i", DownloadStatus.COMPLETED),
        item(
            "t1",
            DownloadStatus.RUNNING,
            url = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3"
        ),
        item(
            "t2",
            DownloadStatus.COMPLETED,
            url = "magnet:?xt=urn:btih:d12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a4"
        )
    )

    @Test
    fun everyLabelInTheRailIsUnique() {
        // The bug. Two halves written separately, each with its own headings, printing
        // "Finished" and "Unfinished" twice each - so the rail read as though it were
        // listing things it had already listed.
        val labels = sidebarEntries()
            .filterIsInstance<RailEntry.Status>()
            .map { it.group.label }
        assertEquals(
            "a status may not appear twice in the rail: $labels",
            labels.size,
            labels.toSet().size
        )
    }

    @Test
    fun noHeadingSharesItsWordingWithARow() {
        val headings = sidebarEntries()
            .filterIsInstance<RailEntry.Heading>()
            .map { it.label }
        val rows = sidebarEntries()
            .filter { it !is RailEntry.Heading }
            .mapNotNull {
                when (it) {
                    is RailEntry.Status -> it.group.label
                    is RailEntry.Category -> it.category.label
                    RailEntry.Torrents -> "Torrents"
                    is RailEntry.Heading -> null
                }
            }
        headings.forEach { heading ->
            assertFalse(
                "\"$heading\" is both a heading and a row, so it appears twice",
                rows.any { it.equals(heading, ignoreCase = true) }
            )
        }
    }

    @Test
    fun theRailAnswersTheQuestionsPeopleActuallyAsk() {
        val groups = sidebarEntries()
            .filterIsInstance<RailEntry.Status>()
            .map { it.group }
        // Downloading, Completed, Paused and Failed are the four questions worth asking of
        // a download manager. There was no way to ask three of them before.
        assertTrue(LibraryGroup.DOWNLOADING in groups)
        assertTrue(LibraryGroup.COMPLETED in groups)
        assertTrue(LibraryGroup.PAUSED in groups)
        assertTrue(LibraryGroup.FAILED in groups)
        assertEquals(LibraryGroup.ALL, groups.first())
    }

    @Test
    fun downloadingCoversEverythingThatWillMoveOnItsOwn() {
        val group = LibraryGroup.DOWNLOADING
        assertTrue("running", group.matches(item("a", DownloadStatus.RUNNING)))
        // Resolving is the stretch before a link is known to work, and queued is waiting
        // for a slot. Both move without being asked, which is what makes them "downloading"
        // to the person looking.
        assertTrue("resolving", group.matches(item("a", DownloadStatus.RESOLVING)))
        assertTrue("queued", group.matches(item("a", DownloadStatus.QUEUED)))
        assertFalse("paused is not downloading", group.matches(item("a", DownloadStatus.PAUSED)))
        assertFalse("failed is not downloading", group.matches(item("a", DownloadStatus.FAILED)))
        assertFalse("completed is not downloading", group.matches(item("a", DownloadStatus.COMPLETED)))
    }

    @Test
    fun everyStatusIsReachableAndTheCountsAddUpToTheWholeList() {
        val counts = LibraryGroup.entries.associateWith { group -> railCount(RailEntry.Status(group), mixed) }
        assertEquals(5, counts[LibraryGroup.DOWNLOADING])
        assertEquals(2, counts[LibraryGroup.PAUSED])
        assertEquals(1, counts[LibraryGroup.FAILED])
        // h, i and t2 - the completed torrent counts too.
        assertEquals(3, counts[LibraryGroup.COMPLETED])
        assertEquals(mixed.size, counts[LibraryGroup.ALL])
        // The four states partition the list, which is what makes the rail navigable
        // rather than decorative.
        assertEquals(
            mixed.size,
            counts[LibraryGroup.DOWNLOADING]!! + counts[LibraryGroup.PAUSED]!! +
                counts[LibraryGroup.FAILED]!! + counts[LibraryGroup.COMPLETED]!!
        )
    }

    /**
     * A count beside a row has to describe that row.
     *
     * The rail is drawn from the items the current tab is showing, so its counts come
     * from the same set the table is built from. A count taken from the whole list put
     * "All Downloads 11" above a table showing nine rows, which is the complaint the
     * scoping was meant to answer.
     */
    @Test
    fun everyStatusCountAgreesWithWhatTheTableWouldShow() {
        val scoped = DownloadLibrary.scopedFor(mixed, torrentsOnly = false)
        LibraryGroup.entries.forEach { group ->
            val expected = scoped.count { group.matches(it) }
            val shown = DownloadLibrary.visible(
                scoped,
                LibraryQuery(group = group, torrentsOnly = false)
            ).size
            assertEquals(
                "the rail says ${railCount(RailEntry.Status(group), scoped)} for ${group.label} " +
                    "but the table shows $shown",
                expected,
                shown
            )
        }
        assertEquals(
            "on the main tab the two torrents are hidden, so nothing may count them",
            9,
            railCount(RailEntry.Status(LibraryGroup.ALL), scoped)
        )
    }

    /**
     * Torrents is counted over the whole list, unlike everything else.
     *
     * Every other row narrows what the table is already showing, so it counts from that.
     * Torrents is the opposite: it switches the table to a different set. Counting it
     * from the current tab would make it read zero exactly when it is the one row worth
     * pressing.
     */
    @Test
    fun torrentsIsCountedAcrossTheWholeListNotJustTheCurrentTab() {
        assertEquals(2, railCount(RailEntry.Torrents, mixed))
        assertEquals(
            "from the main tab, which is hiding both of them",
            0,
            railCount(RailEntry.Torrents, DownloadLibrary.scopedFor(mixed, torrentsOnly = false))
        )
    }

    @Test
    fun categoriesAreUnderTheirOwnHeadingAndDoNotRepeatAll() {
        val entries = sidebarEntries()
        val categoriesAt = entries.indexOfFirst { it is RailEntry.Heading && it.label == "Categories" }
        assertTrue("there must be a Categories heading", categoriesAt >= 0)
        val after = entries.drop(categoriesAt + 1)
            .takeWhile { it !is RailEntry.Heading }
            .filterIsInstance<RailEntry.Category>()
        assertEquals(6, after.size)
        // All is already at the top as "All Downloads"; repeating it here would be the
        // same entry twice with two different counts.
        assertFalse(after.any { it.category == LibraryCategory.ALL })
    }

    @Test
    fun theOrderIsStatesThenCategoriesThenTorrents() {
        val kinds = sidebarEntries().map {
            when (it) {
                is RailEntry.Status -> "state"
                is RailEntry.Category -> "category"
                is RailEntry.Torrents -> "torrents"
                is RailEntry.Heading -> "heading"
            }
        }
        val firstCategory = kinds.indexOf("category")
        val firstTorrent = kinds.indexOf("torrents")
        assertTrue("all the states come first", firstCategory > kinds.indexOfLast { it == "state" })
        assertTrue("torrents come last", firstTorrent > firstCategory)
    }
}
