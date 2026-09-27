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
    val createdAt: Long = 0L
) {
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
