package com.downloadhub.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.downloadhub.app.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun getByStatuses(statuses: List<DownloadStatus>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE source = 'TORRENT' ORDER BY updatedAt DESC")
    fun observeTorrents(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadEntity)

    @Update
    suspend fun update(item: DownloadEntity)

    @Delete
    suspend fun delete(item: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE downloads
        SET status = :newStatus,
            errorMessage = :errorMessage,
            speedBytesPerSecond = 0,
            etaSeconds = -1,
            updatedAt = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """
    )
    suspend fun transitionStatus(
        id: String,
        expectedStatus: DownloadStatus,
        newStatus: DownloadStatus,
        errorMessage: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE downloads
        SET status = :status,
            errorMessage = :errorMessage,
            speedBytesPerSecond = 0,
            etaSeconds = -1,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun setStatus(
        id: String,
        status: DownloadStatus,
        errorMessage: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE downloads
        SET bytesDownloaded = :bytesDownloaded,
            totalBytes = :totalBytes,
            progressPercent = :progressPercent,
            speedBytesPerSecond = :speedBytesPerSecond,
            etaSeconds = :etaSeconds,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        progressPercent: Int,
        speedBytesPerSecond: Long,
        etaSeconds: Long,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE downloads
        SET fileName = :fileName,
            mimeType = :mimeType,
            category = :category,
            totalBytes = :totalBytes,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: String,
        fileName: String,
        mimeType: String?,
        category: com.downloadhub.app.data.model.DownloadCategory,
        totalBytes: Long,
        updatedAt: Long
    )

    @Query("UPDATE downloads SET outputPath = :outputPath, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateOutputPath(id: String, outputPath: String?, updatedAt: Long)

    @Query(
        """
        UPDATE downloads
        SET thumbnailUrl = :thumbnailUrl,
            thumbnailPath = :thumbnailPath,
            durationSeconds = :durationSeconds,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateThumbnail(
        id: String,
        thumbnailUrl: String?,
        thumbnailPath: String?,
        durationSeconds: Long?,
        updatedAt: Long
    )

    @Query(
        "UPDATE downloads SET torrentInfoHash = :infoHash, torrentFilePath = :filePath, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateTorrentInfo(id: String, infoHash: String?, filePath: String?, updatedAt: Long)

    /**
     * Records a torrent's file selection and per-file priorities together.
     *
     * One writer rather than two, because the two are changed together and two statements
     * can half-apply: a selection saved without its priorities leaves a file the user
     * asked to skip downloading normally, and nothing would say so.
     *
     * Both are the encoded text forms from [com.downloadhub.core.FileChoiceCodec], so the
     * format is written down and tested in one place rather than in a query string.
     */
    @Query(
        "UPDATE downloads SET torrentSelectedFiles = :selected, " +
                "torrentFilePriorities = :priorities, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateTorrentFileChoices(
        id: String,
        selected: String?,
        priorities: String?,
        updatedAt: Long
    )

    @Query("UPDATE downloads SET etag = :etag, lastModified = :lastModified, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateValidators(id: String, etag: String?, lastModified: String?, updatedAt: Long)

    @Query("UPDATE downloads SET queueId = :queueId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateQueue(id: String, queueId: String, updatedAt: Long)

    /** A deleted queue's downloads go back to Main rather than being stranded. */
    @Query("UPDATE downloads SET queueId = 'main' WHERE queueId = :queueId")
    suspend fun returnToMainQueue(queueId: String)

    @Query(
        """
        UPDATE downloads
        SET requestHeaders = :headers, cookies = :cookies, username = :username, password = :password, updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateRequest(id: String, headers: String?, cookies: String?, username: String?, password: String?, updatedAt: Long)

    /** Bumps the retry counter used by the automatic retry policy. */
    @Query("UPDATE downloads SET retryCount = :retryCount WHERE id = :id")
    suspend fun updateRetryCount(id: String, retryCount: Int)

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL, updatedAt = :updatedAt WHERE status IN ('RUNNING', 'RESOLVING')")
    suspend fun recoverInterrupted(updatedAt: Long)

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RESOLVING', 'RUNNING')")
    fun observeActiveCount(): Flow<Int>
}
