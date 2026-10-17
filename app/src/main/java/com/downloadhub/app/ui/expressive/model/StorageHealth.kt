package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadStatus

private const val MB = 1_000_000L

/** Below this much free space the phone itself starts to struggle, whatever is queued. */
const val FREE_SPACE_FLOOR = 300 * MB

/** Bytes still to fetch for everything that is running or waiting to run. Unknown sizes count as 0. */
fun remainingBytes(items: List<DownloadEntity>): Long = items
    .filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RESOLVING }
    .sumOf { (it.totalBytes - it.bytesDownloaded).coerceAtLeast(0L) }

/**
 * True when the storage notice should show: free space is under the floor, or under what
 * the active queue still needs plus a small reserve. [freeBytes] below zero means the
 * figure could not be read, which is not "low", so no notice rather than a false alarm.
 */
fun storageIsLow(freeBytes: Long, neededBytes: Long): Boolean {
    if (freeBytes < 0) return false
    return freeBytes < FREE_SPACE_FLOOR || (neededBytes > 0 && freeBytes < neededBytes + 100 * MB)
}
