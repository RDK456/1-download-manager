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

    /**
     * This torrent's file choices, as text.
     *
     * Two text columns rather than a table of files: a selection is a list of indices and
     * a priority is an index and a number, both of which fit a short string, and a
     * four-hundred-file season is about three kilobytes. Read with [FileChoiceCodec],
     * which is where the format is written down and tested.
     *
     * Null means the torrent was never given any choice, which is different from an empty
     * selection: an empty selection means every file, and null means the same thing for a
     * torrent added before this existed.
     */
    val torrentSelectedFiles: String? = null,
    /** Per-file priority as `index:ordinal`, read with [FileChoiceCodec]. */
    val torrentFilePriorities: String? = null,
    val userAgent: String?,
    val contentDisposition: String?,
    val etag: String?,
    val lastModified: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val quality: String? = null,
    val audioFormat: String? = null,
    /**
     * The streams the quality picker named, as yt-dlp format ids.
     *
     * A height cannot say what the picker offers - 1080p60 and 1080p are the same
     * height and not the same download - so the row carries the ids themselves. Null
     * for everything queued before the picker existed, which keeps downloading by
     * height ceiling exactly as it did.
     */
    val streamFormatId: String? = null,
    /** The audio half of the pair; null for an audio-only row, which has just one. */
    val streamAudioFormatId: String? = null,
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
    val seedingStoppedAtEpochMillis: Long = 0L,
    // --- added in version 8 ---------------------------------------------------
    /** Which named queue starts it; see core's QueueRules. */
    val queueId: String = "main",
    /** Extra headers as "Name: value" lines, cookies, and a login, for an HTTP link. */
    val requestHeaders: String? = null,
    val cookies: String? = null,
    val username: String? = null,
    val password: String? = null
)
