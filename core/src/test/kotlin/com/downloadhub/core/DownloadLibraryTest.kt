package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library view is the screen both builds share, so these pin the rules a user
 * would notice immediately: what lands in Finished, what the sidebar counts show,
 * and that sorting is stable rather than shuffling on every refresh.
 */
class DownloadLibraryTest {

    private fun item(
        id: String,
        name: String,
        status: DownloadStatus = DownloadStatus.QUEUED,
        category: DownloadCategory = DownloadCategory.FILE,
        size: Long = 0L,
        speed: Long = 0L,
        createdAt: Long = 0L,
        source: DownloadSource = DownloadSource.HTTP
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$name",
        fileName = name,
        source = source,
        status = status,
        category = category,
        bytesDownloaded = if (size > 0) size / 2 else 0L,
        totalBytes = size,
        speedBytesPerSecond = speed,
        createdAt = createdAt
    )

    private val sample = listOf(
        item("1", "setup.exe", DownloadStatus.COMPLETED, DownloadCategory.PROGRAM, createdAt = 100),
        item("2", "clip.mp4", DownloadStatus.RUNNING, DownloadCategory.VIDEO, size = 2_000_000_000, speed = 2_000_000, createdAt = 300),
        item("3", "song.mp3", DownloadStatus.COMPLETED, DownloadCategory.AUDIO, createdAt = 200),
        item("4", "archive.zip", DownloadStatus.PAUSED, DownloadCategory.COMPRESSED, createdAt = 400),
        item("5", "photo.jpg", DownloadStatus.FAILED, DownloadCategory.IMAGE, createdAt = 500),
        item("6", "doc.pdf", DownloadStatus.QUEUED, DownloadCategory.DOCUMENT, createdAt = 600)
    )

    @Test
    fun allShowsEverything() {
        assertEquals(6, DownloadLibrary.visible(sample, LibraryQuery()).size)
    }

    @Test
    fun aCategoryNarrowsToItsOwnFiles() {
        val videos = DownloadLibrary.visible(sample, LibraryQuery(category = LibraryCategory.VIDEOS))
        assertEquals(1, videos.size)
        assertEquals("clip.mp4", videos.first().fileName)
    }

    @Test
    fun completedHoldsOnlyCompletedItems() {
        val done = DownloadLibrary.visible(sample, LibraryQuery(group = LibraryGroup.COMPLETED))
        assertEquals(2, done.size)
        assertTrue(done.all { it.status == DownloadStatus.COMPLETED })
    }

    /**
     * The states partition the list.
     *
     * There is no "Unfinished" any more. It was every item that is not completed, which is
     * the whole list minus one entry - so it read as though the rail were listing things
     * twice, and it put the four failed and paused downloads in with the running ones
     * where nothing could reach them.
     */
    @Test
    fun theFourStatesPartitionTheList() {
        val states = listOf(
            LibraryGroup.DOWNLOADING,
            LibraryGroup.PAUSED,
            LibraryGroup.FAILED,
            LibraryGroup.COMPLETED
        )
        val total = states.sumOf { DownloadLibrary.visible(sample, LibraryQuery(group = it)).size }
        assertEquals(sample.size, total)
        assertEquals(sample.size, DownloadLibrary.visible(sample, LibraryQuery(group = LibraryGroup.ALL)).size)
    }

    @Test
    fun aFailedItemIsReachableRatherThanBuriedWithTheRest() {
        // It used to sit in "Unfinished" with everything else, where the only way to see it
        // was to sort the table.
        val failed = DownloadLibrary.visible(sample, LibraryQuery(group = LibraryGroup.FAILED))
        assertTrue(failed.any { it.status == DownloadStatus.FAILED })
        assertTrue(failed.all { it.status == DownloadStatus.FAILED })
    }

    @Test
    fun sidebarCountsMatchTheLists() {
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryCategory.VIDEOS))
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryCategory.PROGRAMS))
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryCategory.MUSIC))
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryCategory.PICTURES))
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryCategory.DOCUMENTS))
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryCategory.COMPRESSED))
        assertEquals(6, DownloadLibrary.countFor(sample, LibraryCategory.ALL))
        assertEquals(2, DownloadLibrary.countFor(sample, LibraryGroup.COMPLETED))
        assertEquals(1, DownloadLibrary.countFor(sample, LibraryGroup.FAILED))
    }

    @Test
    fun searchMatchesNameOrUrl() {
        assertEquals(1, DownloadLibrary.visible(sample, LibraryQuery(search = "clip")).size)
        assertEquals(1, DownloadLibrary.visible(sample, LibraryQuery(search = "example.com/song")).size)
        assertEquals(0, DownloadLibrary.visible(sample, LibraryQuery(search = "nothing")).size)
    }

    @Test
    fun searchIsCaseInsensitive() {
        assertEquals(1, DownloadLibrary.visible(sample, LibraryQuery(search = "SETUP.EXE")).size)
    }

    @Test
    fun aTorrentIsInTheDefaultListAndTheTorrentsTabNarrowsToIt() {
        val mixed = sample + item(
            "7", "ubuntu.torrent", DownloadStatus.COMPLETED,
            DownloadCategory.ARCHIVE, source = DownloadSource.TORRENT
        )
        // The default query used to exclude it: six of seven downloads on screen, with
        // nothing anywhere saying the seventh was being held back.
        assertEquals(7, DownloadLibrary.visible(mixed, LibraryQuery()).size)
        assertTrue(
            "a torrent is a download and belongs in the main list",
            DownloadLibrary.visible(mixed, LibraryQuery()).any { it.isTorrent }
        )
        // The Torrents tab narrows to it, rather than being the only place it can be seen.
        val torrents = DownloadLibrary.visible(mixed, LibraryQuery(torrentsOnly = true))
        assertEquals(1, torrents.size)
        assertEquals("ubuntu.torrent", torrents.first().fileName)
    }

    @Test
    fun defaultSortPutsTheNewestFirst() {
        val sorted = DownloadLibrary.visible(sample, LibraryQuery())
        assertEquals("doc.pdf", sorted.first().fileName)
    }

    @Test
    fun sortingByNameIsCaseInsensitive() {
        val names = listOf(
            item("a", "banana.txt"),
            item("b", "Apple.txt"),
            item("c", "cherry.txt")
        )
        val sorted = DownloadLibrary.visible(
            names,
            LibraryQuery(sort = LibrarySort(DownloadColumn.NAME, SortDirection.ASCENDING))
        )
        assertEquals(listOf("Apple.txt", "banana.txt", "cherry.txt"), sorted.map { it.fileName })
    }

    @Test
    fun sortingBySizePutsTheBiggestFirstWhenDescending() {
        val sorted = DownloadLibrary.visible(
            sample,
            LibraryQuery(sort = LibrarySort(DownloadColumn.SIZE, SortDirection.DESCENDING))
        )
        assertTrue(sorted.first().totalBytes >= sorted.last().totalBytes)
    }

    @Test
    fun sortingIsStableAcrossRepeatedCalls() {
        val query = LibraryQuery(sort = LibrarySort(DownloadColumn.NAME, SortDirection.ASCENDING))
        val first = DownloadLibrary.visible(sample, query).map { it.id }
        val second = DownloadLibrary.visible(sample, query).map { it.id }
        assertEquals("the list must not reshuffle on every refresh", first, second)
    }

    @Test
    fun statusSortPutsRunningFirst() {
        val sorted = DownloadLibrary.visible(
            sample,
            LibraryQuery(sort = LibrarySort(DownloadColumn.STATUS, SortDirection.ASCENDING))
        )
        assertEquals("clip.mp4", sorted.first().fileName)
    }

    @Test
    fun togglingASortFlipsDirection() {
        val sort = LibrarySort(DownloadColumn.SIZE, SortDirection.ASCENDING)
        assertEquals(SortDirection.DESCENDING, sort.toggled().direction)
        assertEquals(SortDirection.ASCENDING, sort.toggled().toggled().direction)
    }

    @Test
    fun togglingNameAlwaysStartsAscending() {
        val sort = LibrarySort(DownloadColumn.NAME, SortDirection.DESCENDING)
        assertEquals(
            "a name column should not stick on descending",
            SortDirection.ASCENDING,
            sort.toggled().direction
        )
    }

    @Test
    fun timeLeftNeedsAKnownSpeed() {
        assertEquals(null, DownloadLibrary.estimateSecondsLeft(item("1", "a.zip", DownloadStatus.RUNNING)))
        val known = item("2", "b.zip", DownloadStatus.RUNNING, size = 1_000, speed = 500)
        // Half of 1000 bytes remain, and 500 B/s clears 500 bytes in one second.
        assertEquals(1L, DownloadLibrary.estimateSecondsLeft(known))
    }

    @Test
    fun aPausedItemHasNoCountdown() {
        assertEquals(null, DownloadLibrary.estimateSecondsLeft(item("1", "a.zip", DownloadStatus.PAUSED, speed = 900)))
    }

    @Test
    fun theStatusBarCountsOnlyRunningSpeed() {
        // Only the running item contributes speed; a paused one contributes nothing.
        assertEquals(2_000_000L, DownloadLibrary.totalSpeed(sample))
        // Active means queued or running, so the queued doc.pdf counts too.
        assertEquals(2, DownloadLibrary.activeCount(sample))
    }

    // --- formatting ---------------------------------------------------------

    @Test
    fun sizesAreFormattedForPeople() {
        assertEquals("0 B", DisplayFormat.bytes(0))
        assertEquals("1.5 KB", DisplayFormat.bytes(1_500))
        assertEquals("1.00 MB", DisplayFormat.bytes(1_000_000))
        assertEquals("2.00 GB", DisplayFormat.bytes(2_000_000_000))
    }

    @Test
    fun theCountdownLooksLikeAClock() {
        assertEquals("-", DisplayFormat.timeLeft(null))
        assertEquals("9s", DisplayFormat.timeLeft(9))
        assertEquals("2:05", DisplayFormat.timeLeft(125))
        assertEquals("1:01:01", DisplayFormat.timeLeft(3_661))
    }

    @Test
    fun relativeTimesReadTheWayAListShould() {
        val now = 1_700_000_000_000L
        assertEquals("just now", DisplayFormat.timeAgo(now - 5_000, now))
        assertEquals("1 minute ago", DisplayFormat.timeAgo(now - 60_000, now))
        assertEquals("6 minutes ago", DisplayFormat.timeAgo(now - 360_000, now))
        assertEquals("1 hour ago", DisplayFormat.timeAgo(now - 3_600_000, now))
        assertEquals("2 days ago", DisplayFormat.timeAgo(now - 172_800_000, now))
        assertEquals("-", DisplayFormat.timeAgo(0, now))
    }

    @Test
    fun statusWordsAreShort() {
        assertEquals("Finished", DisplayFormat.status(item("1", "a", DownloadStatus.COMPLETED)))
        assertEquals("Downloading", DisplayFormat.status(item("2", "b", DownloadStatus.RUNNING)))
        assertEquals("Connecting", DisplayFormat.status(item("3", "c", DownloadStatus.RESOLVING)))
    }

    @Test
    fun everyCategoryHasALabel() {
        LibraryCategory.entries.forEach { category ->
            assertTrue("${category.name} has no label", category.label.isNotBlank())
        }
        DownloadColumn.entries.forEach { column ->
            assertTrue("${column.name} has no label", column.label.isNotBlank())
        }
    }
}
