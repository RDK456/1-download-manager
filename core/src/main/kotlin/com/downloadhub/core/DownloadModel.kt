package com.downloadhub.core

/**
 * The shared download model.
 *
 * These types are deliberately plain Kotlin with no Android annotations so the
 * Windows desktop build and the Android app agree on what a download is. Android
 * keeps its own Room entity and maps onto these; the desktop build persists JSON.
 */

enum class DownloadSource {
    HTTP,
    YOUTUBE,
    TORRENT
}

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

enum class DownloadCategory {
    PROGRAM,
    COMPRESSED,
    FILE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    IMAGE,
    ARCHIVE,
    OTHER
}

/** Whether a transfer is currently allowed to consume bandwidth. */
enum class DownloadActivity { ACTIVE, IDLE }

/** A single item in the queue, as the engines see it. */
data class DownloadItem(
    val id: String,
    val url: String,
    val fileName: String,
    val source: DownloadSource = DownloadSource.HTTP,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val category: DownloadCategory = DownloadCategory.OTHER,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val errorMessage: String? = null,
    /** Absolute path of the finished file, once published. */
    val location: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val mimeType: String? = null,
    val userAgent: String? = null,
    /** Media quality ceiling for YouTube, ignored by the HTTP engine. */
    val quality: String? = null,
    val audioFormat: String? = null,
    val playlist: Boolean = false,
    val createdAt: Long = 0L,
    /** Absolute path of the saved .torrent file, when one was loaded from disk. */
    val torrentFilePath: String? = null,
    /** Hex info hash, learned once libtorrent has resolved the magnet. */
    val torrentInfoHash: String? = null,
    /** Where a finished torrent is published. */
    val outputPath: String? = null,
    // --- per-download settings; the rules live in TransferRules ---------------

    /** How urgently this item is given a slot. Orders the queue. */
    val priority: DownloadPriority = DownloadPriority.NORMAL,
    /**
     * This item's own cap, in bytes per second. Zero means "whatever the app-wide
     * limit says". It can lower the global limit but never raise past it.
     */
    val speedLimitBytesPerSecond: Long = 0L,
    /** Do not start before this time. Zero means start whenever there is a slot. */
    val startAfterEpochMillis: Long = 0L,
    /** Stop seeding once this much has been uploaded per byte downloaded. Zero is off. */
    val shareRatioLimit: Double = 0.0,
    /** Stop seeding this many minutes after finishing. Zero is off. */
    val seedTimeLimitMinutes: Int = 0,
    /** When a finished torrent began seeding, so a time limit has something to count. */
    val seedingSinceEpochMillis: Long = 0L,
    /** When sharing stopped, if it did. Zero means it has not. */
    val seedingStoppedAtEpochMillis: Long = 0L,
    // --- choices made in the pre-download dialog ---------------------------------

    /**
     * Which files inside this torrent to fetch, by their index.
     *
     * Empty means every file, and that is the norm: a magnet has no file list until
     * peers answer, and a plain link has none at all. A non-empty list has to survive a
     * restart or the torrent quietly reverts to downloading everything.
     */
    val torrentSelectedFiles: List<Int> = emptyList(),
    /** Fetch files in order. Helps where earlier parts are needed to open the rest. */
    val torrentSequential: Boolean = false,
    /** Fetch the first and last pieces first, which is what video needs to start. */
    val torrentFirstLastPiecesFirst: Boolean = false,
    /** Folder under the save directory that the torrent's files go in. */
    val torrentContentFolder: String = "",
    // --- torrent readings, for the detail pane ------------------------------------
    // Zero for an HTTP download: it has no peers and uploads nothing, and counting it
    // in a torrent client's peer and upload figures is what makes those figures
    // meaningless.

    /** Bytes per second being uploaded. */
    val uploadRate: Long = 0L,
    /** Peers that have the whole file. */
    val seeds: Int = 0,
    /** Peers that have some of it. */
    val peerCount: Int = 0,
    /** Bytes uploaded over the torrent's whole life, which a share ratio is measured against. */
    val uploadedBytes: Long = 0L,
    /** When it finished downloading. */
    val completedAt: Long = 0L
) {

    /**
     * How many of this torrent's files are being fetched.
     *
     * Blank selection means everything, so the count is the file count rather than zero.
     * Shown in the content pane so the number is about files rather than an item count
     * that means something else.
     */
    val selectedTorrentFileCount: Int
        get() = if (torrentSelectedFiles.isEmpty()) 1 else torrentSelectedFiles.size
    /** The sharing limits for this item, taken from its two limit fields. */
    val shareLimits: ShareLimits
        get() = ShareLimits(ratioLimit = shareRatioLimit, seedTimeLimitMinutes = seedTimeLimitMinutes)
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED ||
            status == DownloadStatus.RESOLVING ||
            status == DownloadStatus.RUNNING

    val isTorrent: Boolean get() = source == DownloadSource.TORRENT
}

/** Per-item settings the HTTP engine needs while transferring. */
data class TransferPolicy(
    val maxRetries: Int = 2,
    val speedLimitBytesPerSecond: Long = 0L,
    val useSpeedLimit: Boolean = false
)
