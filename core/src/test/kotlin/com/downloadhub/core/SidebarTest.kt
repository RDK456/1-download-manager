package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
                    is RailEntry.Kind -> it.kind.label
                    RailEntry.Search -> "Search"
                    RailEntry.YouTube -> "YouTube"
                    RailEntry.Rss -> "RSS"
                    is RailEntry.Heading -> null
                }
            }
        headings.forEach { heading ->
            assertFalse(
                "\"$heading\" is both a heading and a row, so it appears twice",
                rows.any { row -> row.equals(heading, ignoreCase = true) }
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
        assertTrue(LibraryGroup.FINISHED in groups)
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
        assertEquals(3, counts[LibraryGroup.FINISHED])
        assertEquals(mixed.size, counts[LibraryGroup.ALL])
        // The four states partition the list, which is what makes the rail navigable
        // rather than decorative.
        assertEquals(
            mixed.size,
            counts[LibraryGroup.DOWNLOADING]!! + counts[LibraryGroup.PAUSED]!! +
                counts[LibraryGroup.FAILED]!! + counts[LibraryGroup.FINISHED]!!
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
                LibraryQuery(group = group)
            ).size
            assertEquals(
                "the rail says ${railCount(RailEntry.Status(group), scoped)} for ${group.label} " +
                    "but the table shows $shown",
                expected,
                shown
            )
        }
        assertEquals(
            "the main list holds every download, torrents included",
            11,
            railCount(RailEntry.Status(LibraryGroup.ALL), scoped)
        )
    }

    /**
     * A torrent appears in All Downloads, and also under Torrents.
     *
     * The Torrents tab used to be a partition rather than a filter: `torrentsOnly !=
     * item.isTorrent` hid a torrent from the main list entirely. So a queue of five
     * downloads showed four, with nothing anywhere saying the fifth was being held back -
     * and the count beside All Downloads was wrong by exactly the number of torrents.
     */
    @Test
    fun aTorrentIsInAllDownloadsAndNotOnlyUnderTorrents() {
        val torrent = mixed.first { it.isTorrent }

        val all = DownloadLibrary.visible(mixed, LibraryQuery(group = LibraryGroup.ALL))
        assertTrue(
            "All Downloads must include torrents: ${all.map { it.fileName }}",
            torrent.id in all.map { it.id }
        )
        assertEquals("nothing may be held back from the main list", mixed.size, all.size)

        // And the Torrents tab narrows to just them, rather than being the only place
        // they can be seen at all.
        val onlyTorrents = DownloadLibrary.visible(
            mixed,
            LibraryQuery(kind = LibraryKind.TORRENT)
        )
        assertEquals(2, onlyTorrents.size)
        assertTrue(onlyTorrents.all { it.isTorrent })
    }

    @Test
    fun theMainListIsNotNarrowedByTheTorrentsTab() {
        assertEquals(
            "the main tab is everything",
            mixed,
            DownloadLibrary.scopedFor(mixed, torrentsOnly = false)
        )
        assertEquals(
            "and the Torrents tab is a filter over the same list",
            mixed.filter { it.isTorrent },
            DownloadLibrary.scopedFor(mixed, torrentsOnly = true)
        )
    }

    /**
     * The Torrents count is the same on both tabs now.
     *
     * It used to be zero on the main tab, because the main tab hid them - which made the
     * one row worth pressing read as empty exactly when it was the row you wanted.
     */
    @Test
    fun torrentsAreCountedTheSameOnEitherTab() {
        val torrents = RailEntry.Kind(LibraryKind.TORRENT)
        assertEquals(2, railCount(torrents, mixed))
        assertEquals(
            2,
            railCount(torrents, DownloadLibrary.scopedFor(mixed, torrentsOnly = false))
        )
        assertEquals(
            2,
            railCount(torrents, DownloadLibrary.scopedFor(mixed, torrentsOnly = true))
        )
    }

    /**
     * A kind's count must be taken over the whole queue, not over the narrowed list.
     *
     * Scoped to YouTube and then reading the Torrents count would otherwise say zero,
     * which means "there are none" and not "not the list you are looking at" - and
     * zero is the number that stops someone pressing it. railCount counts what it is
     * handed, so the responsibility is on the caller to hand it the whole queue.
     */
    @Test
    fun theKindRowsAreCountedOverTheWholeQueueAndTheStateRowsOverTheList() {
        // The fixture has no YouTube downloads, so the narrow list is torrents - which
        // is the same shape of question: one kind excluded, the other counted.
        val torrentsOnly = DownloadLibrary.scopedFor(mixed, LibraryKind.TORRENT)
        assertTrue("the narrowed list is not empty for this to mean anything", torrentsOnly.isNotEmpty())

        // From :core's working directory, so the path climbs out of the module.
        val rail = File("../desktop/src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        val kindRows = rail.substringAfter("is RailEntry.Kind -> RailRow(")
            .substringBefore("RailEntry.Search")
        assertTrue(
            "a kind row must be counted over the whole queue:\n$kindRows",
            kindRows.contains("railCount(entry, items)")
        )
        val stateRows = rail.substringAfter("is RailEntry.Status -> RailRow(")
            .substringBefore("is RailEntry.Category -> RailRow(")
        assertTrue(
            "a state row counts what the table is showing:\n$stateRows",
            stateRows.contains("railCount(entry, scoped)")
        )
        // And the two really do differ, so the distinction is not academic.
        assertEquals(2, railCount(RailEntry.Kind(LibraryKind.TORRENT), mixed))
        assertEquals(0, railCount(RailEntry.Kind(LibraryKind.NORMAL), torrentsOnly))
    }

    /**
     * The three kinds together are the whole queue, and All is the whole queue.
     *
     * The split adds ways to narrow. If anything fell between the three, a download
     * would exist that no row could produce - which is what happened when YouTube was
     * grouped with ordinary links and could not be asked for on its own.
     */
    @Test
    fun theThreeKindsAddUpToEverything() {
        val total = mixed.size
        val split = LibraryKind.entries
            .filter { it.isFilter }
            .sumOf { kind -> DownloadLibrary.kindCount(mixed, kind) }
        assertEquals("no download may fall between the kinds", total, split)
        assertEquals(total, DownloadLibrary.kindCount(mixed, LibraryKind.ALL))
        // And each download is in exactly one of them.
        mixed.forEach { item ->
            val hits = LibraryKind.entries.count { kind -> kind.isFilter && kind.matches(item) }
            assertEquals("${item.fileName} is in $hits kinds", 1, hits)
        }
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
    fun theKindsAreUnderTheirOwnHeadingAndDoNotRepeatAll() {
        val entries = sidebarEntries()
        val at = entries.indexOfFirst { it is RailEntry.Heading && it.label == "Kinds" }
        assertTrue("there must be a Kinds heading", at >= 0)
        val after = entries.drop(at + 1)
            .takeWhile { it !is RailEntry.Heading }
            .filterIsInstance<RailEntry.Kind>()
        assertEquals(3, after.size)
        assertEquals(
            listOf(LibraryKind.TORRENT, LibraryKind.YOUTUBE, LibraryKind.NORMAL),
            after.map { it.kind }
        )
        // All Downloads is the top row and still means everything; repeating it under
        // Kinds would be the same entry twice.
        assertFalse(after.any { it.kind == LibraryKind.ALL })
    }

    @Test
    fun theOrderIsStatesThenCategoriesThenKinds() {
        val kinds = sidebarEntries().map {
            when (it) {
                is RailEntry.Status -> "state"
                is RailEntry.Category -> "category"
                is RailEntry.Kind -> "kind"
                is RailEntry.Search -> "search"
                is RailEntry.YouTube -> "youtube"
                is RailEntry.Rss -> "rss"
                is RailEntry.Heading -> "heading"
            }
        }
        val firstCategory = kinds.indexOf("category")
        val firstKind = kinds.indexOf("kind")
        assertTrue("all the states come first", firstCategory > kinds.indexOfLast { it == "state" })
        assertTrue("the kinds come last", firstKind > firstCategory)
    }
}
