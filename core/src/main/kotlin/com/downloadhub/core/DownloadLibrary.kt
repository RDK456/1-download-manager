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

/**
 * What the download list can be narrowed to.
 *
 * These are the questions a person actually asks of a download manager: what is coming,
 * what is done, what did I stop, what broke. The previous set was Finished and Unfinished
 * plus a "Queues" heading, which answered none of those - there was no way to reach the
 * failed downloads, or everything that is actively running, except by sorting.
 *
 * Unfinished is deliberately gone rather than renamed. "Everything that is not finished"
 * is the whole list minus one entry, so it is All Downloads again with a different word
 * on it, and having both made the rail read as if it were listing things twice.
 */
enum class LibraryGroup(val label: String) {
    ALL("All Downloads"),
    DOWNLOADING("Downloading"),
    COMPLETED("Completed"),
    PAUSED("Paused"),
    FAILED("Failed");

    fun matches(item: DownloadItem): Boolean = when (this) {
        // Anything that will move without being asked to: running, resolving a link, or
        // sitting in the queue waiting for a slot. All three are "in progress" to the
        // person looking at it.
        DOWNLOADING -> item.status == DownloadStatus.RUNNING ||
            item.status == DownloadStatus.RESOLVING ||
            item.status == DownloadStatus.QUEUED

        COMPLETED -> item.status == DownloadStatus.COMPLETED
        PAUSED -> item.status == DownloadStatus.PAUSED
        FAILED -> item.status == DownloadStatus.FAILED
        ALL -> true
    }
}

/**
 * One entry in the sidebar.
 *
 * The rail is built from this rather than assembled inside each platform's composable, so
 * the order, the headings and the wording cannot drift between the two apps - which is
 * what happened when the Windows rail grew its own headings.
 */
sealed interface RailEntry {
    /** A status filter. */
    data class Status(val group: LibraryGroup) : RailEntry

    /** A file category. */
    data class Category(val category: LibraryCategory) : RailEntry

    /** Torrents only. A filter rather than a status, so it is its own kind. */
    data object Torrents : RailEntry

    /** A heading with nothing selectable under it. */
    data class Heading(val label: String) : RailEntry
}

/**
 * The sidebar, in order.
 *
 * The order is deliberate: the states first, because they are what gets looked at daily,
 * then the categories, which are for tidying up afterwards, then torrents, which is a
 * different kind of transfer rather than a kind of file.
 */
fun sidebarEntries(): List<RailEntry> = buildList {
    LibraryGroup.entries.forEach { add(RailEntry.Status(it)) }
    add(RailEntry.Heading("Categories"))
    // ALL is already up above as "All Downloads"; repeating it here under Categories
    // would be the same entry twice with two different counts.
    LibraryCategory.entries
        .filter { it != LibraryCategory.ALL }
        .forEach { add(RailEntry.Category(it)) }
    // No heading above Torrents. A heading reading "Torrents" above a row reading
    // "Torrents" is the same duplication the rail is being fixed for, and it is the one
    // the list itself would have let back in.
    add(RailEntry.Torrents)
}

/** How many downloads an entry holds, out of what the current tab is showing. */
fun railCount(entry: RailEntry, items: List<DownloadItem>): Int = when (entry) {
    is RailEntry.Status -> items.count(entry.group::matches)
    is RailEntry.Category -> items.count(entry.category::matches)
    RailEntry.Torrents -> items.count { it.isTorrent }
    is RailEntry.Heading -> 0
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
        // Only narrows when it is on. `torrentsOnly != item.isTorrent` would exclude
        // torrents from the main list, which is a queue silently missing downloads.
        if (torrentsOnly && !item.isTorrent) return false
        if (!category.matches(item)) return false
        if (!group.matches(item)) return false
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
     * The items the current tab is looking at.
     *
     * A torrent is a download like any other, so it belongs in All Downloads. It used to
     * be hidden from the main list entirely and shown only under Torrents, which made
     * the main list quietly wrong: a queue of five downloads showing four, with nothing
     * to say one was being held back.
     *
     * So the main tab is everything and the Torrents tab is a filter over it. The counts
     * still come from the same set the table is built from, or they describe rows that
     * cannot be seen - "Programs 2" above an empty list, which is what was happening
     * before.
     */
    fun scopedFor(items: List<DownloadItem>, torrentsOnly: Boolean): List<DownloadItem> =
        if (torrentsOnly) items.filter { it.isTorrent } else items

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

    fun countFor(items: List<DownloadItem>, group: LibraryGroup): Int =
        items.count(group::matches)

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
