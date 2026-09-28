package com.downloadhub.core

import java.io.File

/**
 * The narrow surface the HTTP engine needs from the platform it runs on.
 *
 * The engine used to take a Room DAO, an Android `Context`-backed storage helper
 * and a DataStore repository directly, which pinned it to Android. These three
 * interfaces are the whole contract, so the same transfer code drives the Android
 * app and the Windows desktop app.
 */

/** Where a finished download ended up. */
data class PublishedTarget(val location: String)

/** Persists transfer progress. Implemented by Room on Android, JSON on desktop. */
interface DownloadStore {
    fun updateValidators(id: String, etag: String?, lastModified: String?, now: Long)
    fun updateMetadata(
        id: String,
        fileName: String,
        mimeType: String?,
        category: DownloadCategory,
        totalBytes: Long,
        now: Long
    )

    fun updateProgress(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        percent: Int,
        speedBytesPerSecond: Long,
        etaSeconds: Long,
        now: Long
    )

    fun updateOutputPath(id: String, location: String?, now: Long)
    fun setStatus(id: String, status: DownloadStatus, error: String?, now: Long)
}

/** Owns the scratch directory and moves finished files to the user's folder. */
interface WorkArea {
    fun workFile(id: String): File

    /**
     * Moves [source] to its final resting place. [destinationTreeUri] is the
     * Storage Access Framework tree on Android, or null when a plain path is used.
     *
     * [category] is the finished file's kind, so a platform that files by folder can
     * put it in one without having to re-guess from the extension.
     */
    fun publishFile(
        source: File,
        preferredName: String,
        destinationTreeUri: String?,
        category: DownloadCategory
    ): PublishedTarget

    /** Tells the platform a new file exists. A no-op off Android. */
    fun scan(file: File)
}

/** Settings the engine reads while transferring. */
interface TransferPolicyProvider {
    suspend fun policyFor(id: String): TransferPolicy
}
