package com.downloadhub.core

/**
 * The information architecture of the library screen, shared by both builds.
 *
 * The Android and Windows apps render the same sidebar categories, the same columns
 * and the same grouping rules; only the pixels differ. Keeping the rules here means
 * a change to sorting or to what counts as "Finished" cannot land on one platform
 * only, and it is testable without a device or a window.
 */

/** Sidebar categories, named the way a person thinks about their files. */
enum class LibraryCategory(val label: String, val categories: Set<DownloadCategory>) {
    ALL("All", DownloadCategory.entries.toSet()),
    COMPRESSED("Compressed", setOf(DownloadCategory.COMPRESSED)),
    PROGRAMS("Programs", setOf(DownloadCategory.PROGRAM)),
    VIDEOS("Videos", setOf(DownloadCategory.VIDEO)),
    MUSIC("Music", setOf(DownloadCategory.AUDIO)),
    PICTURES("Pictures", setOf(DownloadCategory.IMAGE)),
    DOCUMENTS("Documents", setOf(DownloadCategory.DOCUMENT));

    fun matches(item: DownloadItem): Boolean = item.category in categories
}

/**
 * The subfolder a finished file of this category is filed under.
 *
 * The library is grouped by category, so the folder the bytes land in should agree
 * with it - otherwise the sidebar says "Videos" and the folder says "Other", and the
 * person has to learn two different arrangements of the same idea.
 *
 * The names come from [LibraryCategory] rather than the enum, because those are the
 * words already on screen. A category with no sidebar entry of its own - a plain
 * file, an archive libtorrent reported separately - goes to "Other" rather than
 * inventing a folder nobody can find.
 */
fun DownloadCategory.destinationFolder(): String = when (this) {
    DownloadCategory.COMPRESSED, DownloadCategory.ARCHIVE -> LibraryCategory.COMPRESSED.label
    DownloadCategory.PROGRAM -> LibraryCategory.PROGRAMS.label
    DownloadCategory.VIDEO -> LibraryCategory.VIDEOS.label
    DownloadCategory.AUDIO -> LibraryCategory.MUSIC.label
    DownloadCategory.IMAGE -> LibraryCategory.PICTURES.label
    DownloadCategory.DOCUMENT -> LibraryCategory.DOCUMENTS.label
    else -> "Other"
}

/** The collapsible groups under the categories, plus the queue entry. */
enum class LibraryGroup(val label: String) {
    ALL("All"),
    FINISHED("Finished"),
    UNFINISHED("Unfinished"),
    QUEUES("Queues"),
    MAIN("Main");

    fun matches(item: DownloadItem): Boolean = when (this) {
        FINISHED -> item.status == DownloadStatus.COMPLETED
        UNFINISHED -> item.status != DownloadStatus.COMPLETED
        // ALL, QUEUES and MAIN do not narrow by state; MAIN is the one queue.
        else -> true
    }
}

/** Sortable columns, in the order they appear. */
enum class DownloadColumn(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    STATUS("Status"),
    SPEED("Speed"),
    TIME_LEFT("Time Left"),
    DATE_ADDED("Date Added")
}

/** Which way a column is sorted. */
enum class SortDirection { ASCENDING, DESCENDING }

data class LibrarySort(val column: DownloadColumn, val direction: SortDirection) {
    fun toggled(): LibrarySort =
        if (column == DownloadColumn.NAME) copy(direction = SortDirection.ASCENDING)
        else copy(direction = if (direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING)

    companion object {
        /** Newest first, which is what a queue wants to show by default. */
        val RECENT = LibrarySort(DownloadColumn.DATE_ADDED, SortDirection.DESCENDING)
    }
}

/** Everything that narrows the visible list. */
data class LibraryQuery(
    val category: LibraryCategory = LibraryCategory.ALL,
    val group: LibraryGroup = LibraryGroup.ALL,
    val search: String = "",
    val torrentsOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.RECENT
) {
    fun matches(item: DownloadItem): Boolean {
        if (torrentsOnly != item.isTorrent) return false
        if (!category.matches(item)) return false
        if (group != LibraryGroup.ALL && group != LibraryGroup.MAIN && !group.matches(item)) return false
        if (search.isNotBlank()) {
            val needle = search.trim()
            if (!item.fileName.contains(needle, ignoreCase = true) &&
                !item.url.contains(needle, ignoreCase = true)
            ) {
                return false
            }
        }
        return true
    }
}

/** The whole library view: filter, sort, and the counts the sidebar shows. */
object DownloadLibrary {

        /**
     * The items the current torrent tab is looking at.
     *
     * The tab is a filter, not a second list: a torrent is hidden entirely while the
     * main tab is showing, and a non-torrent is hidden while the Torrents tab is. The
     * sidebar counts have to come from the same set, or they describe rows that cannot
     * be seen - "Programs 2" above an empty list, which is exactly what was happening.
     */
    fun scopedFor(items: List<DownloadItem>, torrentsOnly: Boolean): List<DownloadItem> =
        items.filter { it.isTorrent == torrentsOnly }

    /** How many torrents there are, which is what the Torrents entry exists to say. */
    fun torrentCount(items: List<DownloadItem>): Int = items.count { it.isTorrent }
fun visible(items: List<DownloadItem>, query: LibraryQuery): List<DownloadItem> =
        items.filter(query::matches).sortedWith(comparator(query.sort))

    private fun comparator(sort: LibrarySort): Comparator<DownloadItem> {
        val base: Comparator<DownloadItem> = when (sort.column) {
            // Text compares case-insensitively so "apple" and "Banana" interleave.
            DownloadColumn.NAME -> Comparator { a, b -> a.fileName.compareTo(b.fileName, ignoreCase = true) }
            DownloadColumn.SIZE -> compareBy { it.totalBytes }
            DownloadColumn.SPEED -> compareBy { it.speedBytesPerSecond }
            DownloadColumn.TIME_LEFT -> compareBy { estimateSecondsLeft(it) }
            // Status sorts by urgency: running first, then queued, then the rest.
            DownloadColumn.STATUS -> compareBy { statusRank(it.status) }
            DownloadColumn.DATE_ADDED -> compareBy { it.createdAt }
        }
        val directed = if (sort.direction == SortDirection.ASCENDING) base else base.reversed()
        // A stable tiebreak keeps the list from reshuffling between refreshes.
        return directed.thenBy { it.fileName.lowercase() }
    }

    private fun statusRank(status: DownloadStatus): Int = when (status) {
        DownloadStatus.RUNNING -> 0
        DownloadStatus.RESOLVING -> 1
        DownloadStatus.QUEUED -> 2
        DownloadStatus.PAUSED -> 3
        DownloadStatus.FAILED -> 4
        DownloadStatus.COMPLETED -> 5
    }

    fun countFor(items: List<DownloadItem>, category: LibraryCategory): Int =
        items.count { it.category in category.categories }

    fun countFor(items: List<DownloadItem>, group: LibraryGroup): Int = when (group) {
        LibraryGroup.FINISHED -> items.count { it.status == DownloadStatus.COMPLETED }
        LibraryGroup.UNFINISHED -> items.count { it.status != DownloadStatus.COMPLETED }
        else -> items.size
    }

    /** Seconds remaining, or null when it cannot be known yet. */
    fun estimateSecondsLeft(item: DownloadItem): Long? {
        if (item.status != DownloadStatus.RUNNING) return null
        if (item.speedBytesPerSecond <= 0) return null
        val remaining = item.totalBytes - item.bytesDownloaded
        if (remaining <= 0) return null
        return remaining / item.speedBytesPerSecond
    }

    fun totalSpeed(items: List<DownloadItem>): Long =
        items.filter { it.status == DownloadStatus.RUNNING }.sumOf { it.speedBytesPerSecond }

    fun activeCount(items: List<DownloadItem>): Int = items.count { it.isActive }
}
