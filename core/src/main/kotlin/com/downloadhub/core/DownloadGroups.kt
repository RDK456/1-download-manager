package com.downloadhub.core

import java.io.File

/**
 * Downloads queued together from one batch (they share a [DownloadItem.groupId]) are shown
 * as one row in the library, the way a torrent's files are one row. The files stay separate
 * downloads underneath, so each still resumes, retries and finishes on its own.
 */
object DownloadGroups {
    const val PREFIX = "group:"

    fun isGroupId(id: String) = id.startsWith(PREFIX)

    /** The list with each batch of two or more folded into one row, placed where its first file was. */
    fun collapse(items: List<DownloadItem>): List<DownloadItem> {
        val batches = items.filter { it.groupId.isNotBlank() }.groupBy { it.groupId }.filterValues { it.size > 1 }
        if (batches.isEmpty()) return items
        val shown = HashSet<String>()
        return items.mapNotNull { item ->
            val batch = batches[item.groupId] ?: return@mapNotNull item
            if (shown.add(item.groupId)) summary(batch) else null
        }
    }

    /** The files behind a row id from [collapse], or the one item for an ordinary row. */
    fun membersOf(items: List<DownloadItem>, rowId: String): List<DownloadItem> =
        if (isGroupId(rowId)) items.filter { it.groupId == rowId.removePrefix(PREFIX) } else items.filter { it.id == rowId }

    private fun summary(files: List<DownloadItem>): DownloadItem {
        val first = files.first()
        fun any(vararg s: DownloadStatus) = files.any { it.status in s }
        val status = when {
            any(DownloadStatus.RUNNING, DownloadStatus.RESOLVING) -> DownloadStatus.RUNNING
            any(DownloadStatus.QUEUED) -> DownloadStatus.QUEUED
            any(DownloadStatus.PAUSED) -> DownloadStatus.PAUSED
            any(DownloadStatus.FAILED) -> DownloadStatus.FAILED
            else -> DownloadStatus.COMPLETED
        }
        return first.copy(
            id = PREFIX + first.groupId,
            fileName = BatchLinks.commonFolderName(files.map { it.fileName }).ifBlank { "Batch" } + " (${files.size} files)",
            status = status,
            bytesDownloaded = files.sumOf { it.bytesDownloaded },
            totalBytes = files.sumOf { it.totalBytes },
            speedBytesPerSecond = files.sumOf { it.speedBytesPerSecond },
            errorMessage = files.firstNotNullOfOrNull { it.errorMessage },
            location = files.firstNotNullOfOrNull { it.location }?.let { File(it).parent },
            completedAt = if (status == DownloadStatus.COMPLETED) files.maxOf { it.completedAt } else 0L,
            createdAt = files.minOf { it.createdAt }
        )
    }
}
