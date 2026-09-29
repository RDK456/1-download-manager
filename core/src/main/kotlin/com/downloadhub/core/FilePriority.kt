package com.downloadhub.core

/**
 * A torrent file's download priority, in the order qBittorrent offers it.
 *
 * The same four words qBittorrent uses, because a user who has set priorities in another
 * client should not have to learn a new scale. "Skip" is the one that matters most and
 * had no equivalent anywhere in this app: libtorrent's `IGNORE` is how it is told to
 * leave a file's pieces alone, which is the difference between "I want three of these
 * forty files" and "I want all of them slowly".
 *
 * The ranks are libtorrent's own `lt::priority_t` values, not an ordering invented here,
 * because they go straight into `torrent_handle::file_priority`.
 */
enum class FilePriority(val label: String, val libtorrentRank: Int) {
    /** Do not download this file, and do not offer its pieces to anyone. */
    SKIP("Skip", 0),

    /** Taken only after everything at normal priority. */
    LOW("Low", 1),

    /** The default, and what a file gets when nobody has said otherwise. */
    NORMAL("Normal", 4),

    /** Chosen first among the wanted files. */
    HIGH("High", 7),

    /**
     * Chosen first, and its pieces are raised to the top of the piece queue too.
     *
     * There are only four values in libtorrent and three of them are already spoken for,
     * so "Maximum" cannot be another rank. What makes it different is the piece queue:
     * a file at HIGH competes with every other wanted file for the pieces they share,
     * and a file at MAXIMUM takes those pieces ahead of all of them. That is the whole
     * difference and it is a real one - it is what "download this one now" means.
     */
    MAXIMUM("Maximum", 7);

    /**
     * Whether this priority also raises the file's pieces.
     *
     * False for [HIGH] and true for [MAXIMUM], which have the same file rank and differ
     * only here.
     */
    val raisesPieces: Boolean get() = this == MAXIMUM

    companion object {
        /** An unknown stored value is Normal, never Skip: losing a file by accident is worse. */
        fun fromOrdinal(ordinal: Int?): FilePriority =
            entries.firstOrNull { it.ordinal == ordinal } ?: NORMAL

        /** Every priority at once, for "set all". */
        fun all(): Map<Int, FilePriority> = emptyMap()
    }
}

/**
 * The priority a given file index actually has.
 *
 * The pre-download selection wins over a per-file choice, and that is deliberate. A file
 * the user unticked in the dialog is not wanted, and offering a per-file priority control
 * for it would be a control that says something other than what it does. So a deselected
 * file is [FilePriority.SKIP] whatever is in the map - and a chosen priority of SKIP is
 * how a file gets *out* of the selection afterwards.
 */
fun effectiveFilePriority(
    selected: Set<Int>,
    chosen: Map<Int, FilePriority>,
    index: Int
): FilePriority {
    // An empty selection means "every file", which is what a torrent added without the
    // dialog - or a magnet whose list arrived after it was queued - carries.
    if (selected.isNotEmpty() && index !in selected) return FilePriority.SKIP
    return chosen[index] ?: FilePriority.NORMAL
}

/**
 * What to tell libtorrent: the per-file ranks, and the piece queue if anything asked for
 * the pieces to be raised.
 */
data class FilePriorityPlan(
    /** One rank per file, in file order. */
    val filePriorities: IntArray,
    /**
     * One rank per piece, or null when no file asked for its pieces to be raised.
     *
     * Null rather than an all-default array because sending one would reset a piece
     * order the user set elsewhere, and because building it is the expensive half.
     */
    val piecePriorities: IntArray?,
    /** The files whose pieces were raised, for a log line and for tests. */
    val boostedFiles: List<Int>
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is FilePriorityPlan &&
                filePriorities.contentEquals(other.filePriorities) &&
                piecePriorities.contentEquals(other.piecePriorities) &&
                boostedFiles == other.boostedFiles)

    override fun hashCode(): Int =
        (filePriorities.contentHashCode() * 31 + (piecePriorities?.contentHashCode() ?: 0)) * 31 +
            boostedFiles.hashCode()
}

/**
 * Turns per-file priorities into the two arrays libtorrent takes.
 *
 * Pure, and it is the only place the piece arithmetic lives, because it is the part that
 * is easy to get subtly wrong: a piece is shared by the file before it and the file after
 * it, so "the pieces of file 7" is a range computed from the sizes of files 0 to 6, and
 * getting the boundary off by one silently reorders a queue rather than breaking it.
 *
 * [fileSizes] are in torrent order and are what makes the offsets; [pieceCount] and
 * [pieceLength] come from the same metainfo. A torrent's last file is usually not a whole
 * number of pieces long, so the end of a file's range is the piece its *last byte* is in.
 */
fun planFilePriorities(
    fileSizes: List<Long>,
    pieceCount: Int,
    pieceLength: Long,
    chosen: Map<Int, FilePriority>
): FilePriorityPlan {
    if (fileSizes.isEmpty()) {
        return FilePriorityPlan(IntArray(0), null, emptyList())
    }
    val files = IntArray(fileSizes.size) { FilePriority.NORMAL.libtorrentRank }
    val boosted = ArrayList<Int>()
    var anyBoost = false

    fileSizes.forEachIndexed { index, size ->
        val priority = chosen[index] ?: FilePriority.NORMAL
        files[index] = priority.libtorrentRank
        // A file with no bytes has no pieces, so asking to raise them asks for nothing and
        // would send an all-default piece queue - which clears a piece order set elsewhere.
        // Only a file that actually occupies the torrent counts.
        if (priority.raisesPieces && size > 0) {
            boosted += index
            anyBoost = true
        }
    }

    if (!anyBoost || pieceCount <= 0 || pieceLength <= 0) {
        return FilePriorityPlan(files, null, boosted)
    }

    val pieces = IntArray(pieceCount) { DEFAULT_PIECE_RANK }
    // The offset of each file, which is the sum of everything before it.
    var offset = 0L
    fileSizes.forEachIndexed { index, size ->
        val priority = chosen[index] ?: FilePriority.NORMAL
        if (priority.raisesPieces && size > 0) {
            val firstPiece = (offset / pieceLength).toInt()
            val lastPiece = ((offset + size - 1) / pieceLength).toInt()
            for (piece in firstPiece..lastPiece) {
                if (piece in 0 until pieceCount) pieces[piece] = TOP_PIECE_RANK
            }
        }
        offset += size
    }
    return FilePriorityPlan(files, pieces, boosted)
}

/** libtorrent's `DEFAULT` piece priority. */
private const val DEFAULT_PIECE_RANK = 4

/** libtorrent's `TOP_PRIORITY` piece priority. */
private const val TOP_PIECE_RANK = 7
