package com.downloadhub.app.data.local

import androidx.room.Entity
import androidx.room.Index
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus

@Entity(
    tableName = "downloads",
    indices = [Index(value = ["status"]), Index(value = ["source"])]
)
data class DownloadEntity(
    @androidx.room.PrimaryKey val id: String,
    val source: DownloadSource,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val category: DownloadCategory,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long,
    val outputPath: String?,
    val torrentFilePath: String?,
    val torrentInfoHash: String?,
    val userAgent: String?,
    val contentDisposition: String?,
    val etag: String?,
    val lastModified: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val quality: String? = null,
    val audioFormat: String? = null,
    val thumbnailUrl: String? = null,
    val thumbnailPath: String? = null,
    val durationSeconds: Long? = null,
    val retryCount: Int = 0,
    // --- per-download settings; the rules live in core's TransferRules -------

    /** Queue order. Higher goes first. */
    val priorityRank: Int = 2,
    /** This item's own speed cap in bytes per second. Zero means use the global one. */
    val speedLimitBytesPerSecond: Long = 0L,
    /** Do not start before this time, in epoch milliseconds. */
    val startAfterEpochMillis: Long = 0L,
    /** Stop seeding once this much has been uploaded per byte downloaded. Zero is off. */
    val shareRatioLimit: Double = 0.0,
    /** Stop seeding this many minutes after finishing. Zero is off. */
    val seedTimeLimitMinutes: Int = 0,
    /** When it began seeding, so a time limit has something to count from. */
    val seedingSinceEpochMillis: Long = 0L,
    /** When sharing stopped, if it did. Zero means it has not. */
    val seedingStoppedAtEpochMillis: Long = 0L
)
