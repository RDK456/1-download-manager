package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidebar's numbers have to describe rows you can actually see.
 *
 * Found by loading two torrents and finding "Programs 2" sitting above an empty list:
 * the torrent tab hides one class of item entirely, but the counts were taken from
 * everything. A count that cannot be reached is worse than no count, because it says a
 * download is there when it is not on the screen.
 */
class LibraryCountsTest {

    private fun torrent(id: String, category: DownloadCategory = DownloadCategory.PROGRAM) =
        DownloadItem(id = id, url = "magnet:?xt=urn:btih:$id", fileName = "$id.iso", source = DownloadSource.TORRENT, category = category)

    private fun http(id: String, category: DownloadCategory = DownloadCategory.VIDEO) =
        DownloadItem(id = id, url = "https://x/$id.mp4", fileName = "$id.mp4", category = category)

    @Test
    fun theMainTabCountsOnlyWhatItShows() {
        val items = listOf(torrent("t1"), http("h1"), http("h2"))
        val shown = DownloadLibrary.scopedFor(items, torrentsOnly = false)
        assertEquals("the torrent must not be counted in the main tab", 2, shown.size)
    }

    @Test
    fun theTorrentsTabCountsOnlyWhatItShows() {
        val items = listOf(torrent("t1"), torrent("t2"), http("h1"))
        val shown = DownloadLibrary.scopedFor(items, torrentsOnly = true)
        assertEquals(2, shown.size)
    }

    /** The entry that switches to the Torrents tab has to say how many are over there. */
    @Test
    fun theTorrentsEntrySaysHowManyThereAreEvenWhileHidden() {
        val items = listOf(torrent("t1"), torrent("t2"), torrent("t3"), http("h1"))
        assertEquals(3, DownloadLibrary.torrentCount(items))
        // And while the main tab is showing, that number is how you know to switch.
        assertEquals(1, DownloadLibrary.scopedFor(items, torrentsOnly = false).size)
    }

    /** The two numbers have to add up to the whole queue, or a row is lost between them. */
    @Test
    fun theTwoTabsBetweenThemAccountForEverything() {
        val items = listOf(torrent("t1"), torrent("t2"), http("h1"), http("h2"), http("h3"))
        assertEquals(
            items.size,
            DownloadLibrary.scopedFor(items, torrentsOnly = false).size +
                DownloadLibrary.scopedFor(items, torrentsOnly = true).size
        )
    }

    @Test
    fun categoryCountsAgreeWithTheVisibleList() {
        val items = listOf(torrent("t1"), http("v1"), http("v2"))
        val query = LibraryQuery(category = LibraryCategory.VIDEOS)
        val visible = DownloadLibrary.visible(items, query)
        val counted = DownloadLibrary.countFor(DownloadLibrary.scopedFor(items, false), LibraryCategory.VIDEOS)
        assertEquals("a count above an empty list is the bug this exists for", visible.size, counted)
    }

    /** Nothing changes for a queue with no torrents in it, which is most of them. */
    @Test
    fun aQueueWithNoTorretsIsUnaffected() {
        val items = listOf(http("v1"), http("v2"), http("v3"))
        assertEquals(items, DownloadLibrary.scopedFor(items, torrentsOnly = false))
        assertTrue(DownloadLibrary.scopedFor(items, torrentsOnly = true).isEmpty())
        assertEquals(0, DownloadLibrary.torrentCount(items))
    }
}
