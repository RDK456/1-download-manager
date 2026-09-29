package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidebar's numbers have to describe rows you can actually see.
 *
 * Found by loading two torrents and finding "Programs 2" sitting above an empty list: a
 * count that cannot be reached is worse than no count, because it says a download is
 * there when it is not on the screen.
 *
 * The original fix for that was to make the two tabs a partition - a torrent hidden from
 * the main list and shown only under Torrents. That traded one wrong number for a worse
 * problem: the main list silently omitted every torrent, so a queue of five downloads
 * showed four and nothing said why. Torrents are now *in* the main list, and the Torrents
 * tab is a filter over the same rows.
 */
class LibraryCountsTest {

    private fun torrent(id: String, category: DownloadCategory = DownloadCategory.PROGRAM) =
        DownloadItem(
            id = id,
            url = "magnet:?xt=urn:btih:$id",
            fileName = "$id.iso",
            source = DownloadSource.TORRENT,
            category = category
        )

    private fun http(id: String, category: DownloadCategory = DownloadCategory.VIDEO) =
        DownloadItem(id = id, url = "https://x/$id.mp4", fileName = "$id.mp4", category = category)

    /**
     * The main list holds everything.
     *
     * This is the change. A torrent is a download; a queue that hides some of its
     * downloads is a queue that is wrong about its own contents.
     */
    @Test
    fun theMainTabHoldsEveryDownloadIncludingTorrents() {
        val items = listOf(torrent("t1"), http("h1"), http("h2"))
        val shown = DownloadLibrary.scopedFor(items, torrentsOnly = false)
        assertEquals("a torrent is a download, so it belongs in the main list", 3, shown.size)
        assertTrue(shown.any { it.isTorrent })
    }

    @Test
    fun theTorrentsTabNarrowsToJustTorrents() {
        val items = listOf(torrent("t1"), torrent("t2"), http("h1"))
        val shown = DownloadLibrary.scopedFor(items, torrentsOnly = true)
        assertEquals(2, shown.size)
        assertTrue(shown.all { it.isTorrent })
    }

    /** The entry that switches to the Torrents tab has to say how many are over there. */
    @Test
    fun theTorrentsEntrySaysHowManyThereAre() {
        val items = listOf(torrent("t1"), torrent("t2"), torrent("t3"), http("h1"))
        assertEquals(3, DownloadLibrary.torrentCount(items))
        // And the main tab is now the same set, so the number is a filter rather than a
        // hint that something is being held back somewhere else.
        assertEquals(4, DownloadLibrary.scopedFor(items, torrentsOnly = false).size)
    }

    /**
     * The Torrents tab is a filter, not a second queue.
     *
     * The two numbers adding up to the whole queue was the property that made the tabs a
     * partition. It is still true - trivially - but the interesting property now is that
     * one is a subset of the other, so nothing can be in the Torrents tab and missing
     * from All Downloads.
     */
    @Test
    fun theTorrentsTabIsASubsetOfTheMainTabAndNotASecondQueue() {
        val items = listOf(torrent("t1"), torrent("t2"), http("h1"), http("h2"), http("h3"))
        val everything = DownloadLibrary.scopedFor(items, torrentsOnly = false)
        val onlyTorrents = DownloadLibrary.scopedFor(items, torrentsOnly = true)

        assertEquals(items.size, everything.size)
        assertTrue(
            "every torrent on the Torrents tab must also be in All Downloads",
            everything.containsAll(onlyTorrents)
        )
        assertTrue(
            "and All Downloads must hold more than the Torrents tab, or the main list " +
                "is still hiding something",
            everything.size > onlyTorrents.size
        )
    }

    @Test
    fun categoryCountsAgreeWithTheVisibleList() {
        val items = listOf(torrent("t1"), http("v1"), http("v2"))
        val query = LibraryQuery(category = LibraryCategory.VIDEOS)
        val visible = DownloadLibrary.visible(items, query)
        val counted = DownloadLibrary.countFor(
            DownloadLibrary.scopedFor(items, false),
            LibraryCategory.VIDEOS
        )
        assertEquals("a count above an empty list is the bug this exists for", visible.size, counted)
    }

    /**
     * A torrent is filed under its own category like anything else.
     *
     * Worth pinning down because the partition hid it: a torrent categorised as a Program
     * used to be reachable only from the Torrents tab, so "Programs" was empty while the
     * number beside it was not.
     */
    @Test
    fun aTorrentsCategoryCountIsReachableInTheMainList() {
        val items = listOf(torrent("t1"), torrent("t2"), http("v1"))
        val programs = DownloadLibrary.visible(
            items,
            LibraryQuery(category = LibraryCategory.PROGRAMS)
        )
        assertEquals(
            "both torrents are Programs and both must show under Programs",
            2,
            programs.size
        )
        assertEquals(
            2,
            DownloadLibrary.countFor(
                DownloadLibrary.scopedFor(items, false),
                LibraryCategory.PROGRAMS
            )
        )
    }

    /** Nothing changes for a queue with no torrents in it, which is most of them. */
    @Test
    fun aQueueWithNoTorrentsIsUnaffected() {
        val items = listOf(http("v1"), http("v2"), http("v3"))
        assertEquals(items, DownloadLibrary.scopedFor(items, torrentsOnly = false))
        assertTrue(DownloadLibrary.scopedFor(items, torrentsOnly = true).isEmpty())
        assertEquals(0, DownloadLibrary.torrentCount(items))
    }
}
