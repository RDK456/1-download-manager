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
    /**
     * Everything that has all its bytes.
     *
     * Called Finished rather than Completed, because that is the word people use for a
     * download that is done with, and because a torrent is only ever finished - it has
     * no separate completed state to confuse it with.
     */
    FINISHED("Finished"),

    /**
     * Everything that has not, which is most of the queue at any moment.
     *
     * Its own row because "not finished" is the question actually being asked most of
     * the time, and listing every unfinished download meant reading the four status rows
     * and adding them up.
     */
    UNFINISHED("Unfinished"),
    PAUSED("Paused"),
    FAILED("Failed");

    fun matches(item: DownloadItem): Boolean = when (this) {
        // Anything that will move without being asked to: running, resolving a link, or
        // sitting in the queue waiting for a slot. All three are "in progress" to the
        // person looking at it.
        DOWNLOADING -> item.status == DownloadStatus.RUNNING ||
            item.status == DownloadStatus.RESOLVING ||
            item.status == DownloadStatus.QUEUED

        FINISHED -> item.status == DownloadStatus.COMPLETED
        // A subtraction rather than a list of statuses, so a download that fails in some
        // new way is unfinished rather than invisible.
        UNFINISHED -> item.status != DownloadStatus.COMPLETED
        PAUSED -> item.status == DownloadStatus.PAUSED
        FAILED -> item.status == DownloadStatus.FAILED
        ALL -> true
    }
}

/**
 * Which kind of download a list is looking at.
 *
 * A queue holds three quite different things that happen to be called downloads: a
 * torrent, a video pulled off YouTube, and an ordinary file from a link. They are
 * not the same to live with. A torrent brings a swarm and a file list and a share
 * limit; a YouTube download is chosen from a quality list and is really a video
 * with a thumbnail; an ordinary download is just bytes arriving at a path. Asked
 * for all at once they are a list nobody can read, and picking out the one kind you
 * wanted meant picking through all of them.
 *
 * So they get their own lists, and [ALL] still means everything - the split adds
 * ways to narrow, it does not take any away. A download is in exactly one of
 * TORRENT, YOUTUBE and NORMAL, and the three together are the whole queue, which is
 * what [LibraryCounts] is checked against.
 *
 * YouTube is separated from NORMAL rather than treated as a link that happens to
 * play: it arrived from somewhere else, it can be several files from one paste, and
 * it is downloaded by a different program. Grouping it with ordinary links left it
 * invisible in the only list people looked at.
 */
enum class LibraryKind(val label: String) {
    ALL("All Downloads"),
    TORRENT("Torrents"),
    YOUTUBE("YouTube"),
    NORMAL("Downloads");

    fun matches(item: DownloadItem): Boolean = matches(item.source.name)

    /**
     * The same rule, for a source that is not this module's [DownloadSource].
     *
     * Android keeps its own copy of the source enum - it has its own entity, its own
     * database and its own serialisation, and sharing one enum with this module would
     * mean the JVM one on an Android classpath. So the rule is expressed against the
     * name, which both copies spell the same way, rather than written out a second
     * time on each platform where the two could quietly disagree about which downloads
     * count as YouTube.
     */
    fun matches(sourceName: String): Boolean = when (this) {
        ALL -> true
        TORRENT -> sourceName == "TORRENT"
        YOUTUBE -> sourceName == "YOUTUBE"
        // The remainder, said as a subtraction rather than as its own test, so a
        // download arriving from somewhere new lands in a list rather than in
        // nowhere. A source added later and not added to this enum is a download
        // that exists and cannot be seen.
        NORMAL -> sourceName != "TORRENT" && sourceName != "YOUTUBE"
    }

    /** The three real kinds, for the rail - [ALL] is the row above them. */
    val isFilter: Boolean get() = this != ALL
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

    /**
     * A kind of download: torrents, YouTube, or ordinary downloads.
     *
     * Its own kind of entry because it is a third axis, not a fourth status or a
     * fifth category - it says where the bytes came from, while the others say how
     * far along they are and what the file is. It was [Torrents] alone, and the
     * other two kinds had nowhere to go.
     */
    data class Kind(val kind: LibraryKind) : RailEntry

    /**
     * Find something to download.
     *
     * Not a filter, which is why it is its own kind rather than a [Status] or a
     * [Category]: everything else in the rail narrows what is already in the list, and this
     * replaces the list with a search box. Folding it into the others would mean it either
     * counted downloads it does not contain, or was the one row in the rail whose count was
     * always zero for a reason nobody could see.
     */
    data object Search : RailEntry

    /**
     * A pasted YouTube link, listed as downloadables.
     *
     * Like [Search] it replaces the list rather than narrowing it: a playlist is
     * not in the queue yet, so no filter could show it. It sits beside Search
     * because the two are the two ways new things arrive - found versus pasted.
     */
    data object YouTube : RailEntry

    /** qBittorrent's RSS reader: feeds and their auto-download rules. */
    data object Rss : RailEntry

    /** Free books: Project Gutenberg, Open Library, the Internet Archive and Wikisource. */
    data object Books : RailEntry

    /** Public-domain films, cartoons and classic TV from the Internet Archive. */
    data object Movies : RailEntry

    /** Shareable live concerts, netlabel releases and LibriVox audiobooks. */
    data object Music : RailEntry

    /** Free, legally broadcast TV channels from the public iptv-org list. */
    data object Tv : RailEntry

    /** The whole Internet Archive, every media type, with one-click download of an item. */
    data object Archive : RailEntry

    /** The built-in player, for what has been downloaded. */
    data object Player : RailEntry

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
    LibraryGroup.entries.forEachIndexed { index, group ->
        add(RailEntry.Status(group))
        // Search sits directly under All Downloads, above the states.
        //
        // It is where you go to find something new, and All Downloads is where you go when
        // you already know what you have. Separating them by a heading would mean a heading
        // reading "Search" above a row reading "Search", which is the duplication this list
        // is arranged to avoid.
        if (index == 0) add(RailEntry.Search)
        // YouTube beside Search: found versus pasted, the two ways new things arrive.
        if (index == 0) add(RailEntry.YouTube)
        if (index == 0) add(RailEntry.Rss)
    }
    // The places to find something new, under one heading that folds away. Six of them
    // between All Downloads and the states made the rail a long scroll before Downloading.
    add(RailEntry.Heading("Discover"))
    add(RailEntry.Books)
    add(RailEntry.Movies)
    add(RailEntry.Music)
    add(RailEntry.Tv)
    add(RailEntry.Archive)
    add(RailEntry.Player)
    add(RailEntry.Heading("Categories"))
    // ALL is already up above as "All Downloads"; repeating it here under Categories
    // would be the same entry twice with two different counts.
    LibraryCategory.entries
        .filter { it != LibraryCategory.ALL }
        .forEach { add(RailEntry.Category(it)) }
    // The three kinds, under a heading, after the categories.
    //
    // The kinds used to be one unlabelled row at the very bottom called Torrents, with
    // nothing to say what it was a kind of and no way to ask for the other two. A
    // heading here is worth it because the rows under it do not repeat it: the heading
    // says "Kinds" and the rows say Torrents, YouTube and Downloads.
    //
    // All Downloads is not repeated here either. It is the top row, and it is the
    // whole queue - which is the point of the split, not a thing it takes away.
    add(RailEntry.Heading("Kinds"))
    LibraryKind.entries
        .filter { it.isFilter }
        .forEach { add(RailEntry.Kind(it)) }
}

/** How many downloads an entry holds, out of what the current tab is showing. */
fun railCount(entry: RailEntry, items: List<DownloadItem>): Int = when (entry) {
    is RailEntry.Status -> items.count(entry.group::matches)
    is RailEntry.Category -> items.count(entry.category::matches)
    // Counted over everything rather than over the already-narrowed list, or choosing
    // Torrents would leave YouTube and Downloads reading zero - and a count of zero
    // beside a row means "there are none", not "not the one you are looking at".
    is RailEntry.Kind -> items.count(entry.kind::matches)
    // Search holds no downloads: it is a box to type in, and a count beside it would be a
    // count of something it does not contain.
    RailEntry.Search -> 0
    // YouTube holds no downloads either: a pasted playlist is not in the queue yet.
    RailEntry.YouTube -> 0
    RailEntry.Rss, RailEntry.Books, RailEntry.Movies, RailEntry.Music, RailEntry.Tv, RailEntry.Archive, RailEntry.Player -> 0
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
    val kind: LibraryKind = LibraryKind.ALL,
    val sort: LibrarySort = LibrarySort.RECENT,
    /** Only this queue's downloads, when set. */
    val queueId: String? = null
) {
    /**
     * Whether this query is scoped to torrents alone.
     *
     * Kept as a read-only view of [kind] rather than a second field, so the two can
     * never disagree: a query saying "torrents only" and a query saying "torrents,
     * finished" were once separate booleans, and only one of them was consulted.
     */
    val torrentsOnly: Boolean get() = kind == LibraryKind.TORRENT

    fun matches(item: DownloadItem): Boolean {
        // Only narrows when it is on. `kind != ALL` with the branches the wrong way
        // round would exclude torrents from the main list, which is a queue silently
        // missing downloads.
        if (!kind.matches(item)) return false
        if (queueId != null && item.queueId != queueId) return false
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
        scopedFor(items, if (torrentsOnly) LibraryKind.TORRENT else LibraryKind.ALL)

    /**
     * The whole queue, or one kind of it.
     *
     * [LibraryKind.ALL] hands back the same list it was given rather than a filtered
     * copy, so a caller that is not filtering pays nothing.
     */
    fun scopedFor(items: List<DownloadItem>, kind: LibraryKind): List<DownloadItem> =
        if (kind == LibraryKind.ALL) items else items.filter(kind::matches)

    /** How many downloads of one kind there are, which is what the rail counts. */
    fun kindCount(items: List<DownloadItem>, kind: LibraryKind): Int =
        if (kind == LibraryKind.ALL) items.size else items.count(kind::matches)

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
