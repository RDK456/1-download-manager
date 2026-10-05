package com.downloadhub.app.download

import com.downloadhub.app.data.SettingsRepository
import com.downloadhub.app.data.local.DownloadDao
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory as AppCategory
import com.downloadhub.app.data.model.DownloadStatus as AppStatus
import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.DownloadStore
import com.downloadhub.core.PublishedTarget
import com.downloadhub.core.TransferPolicy
import com.downloadhub.core.TransferPolicyProvider
import com.downloadhub.core.WorkArea
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * The phone's HTTP downloads, run by the shared engine in :core.
 *
 * This used to be a separate copy of that engine, so the phone never got what was added
 * to the shared one: several connections per file with resumable segments, per-download
 * headers, cookies and login. Now it is three small adapters onto Room, the phone's
 * storage and its settings, and the transfer itself is the same code the desktop runs.
 */
class HttpDownloader(
    private val dao: DownloadDao,
    private val storage: DownloadStorage,
    private val settings: SettingsRepository,
    private val speedLimiter: SpeedLimiter
) {
    // The shared engine's store is not suspending; Room's is. The engine calls it from
    // its own IO thread, so blocking there for a single-row update is fine.
    private val store = object : DownloadStore {
        override fun updateValidators(id: String, etag: String?, lastModified: String?, now: Long) =
            runBlocking { dao.updateValidators(id, etag, lastModified, now) }

        override fun updateMetadata(id: String, fileName: String, mimeType: String?, category: DownloadCategory, totalBytes: Long, now: Long) =
            runBlocking { dao.updateMetadata(id, fileName, mimeType, AppCategory.valueOf(category.name), totalBytes, now) }

        override fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long, percent: Int, speedBytesPerSecond: Long, etaSeconds: Long, now: Long) =
            runBlocking { dao.updateProgress(id, bytesDownloaded, totalBytes, percent, speedBytesPerSecond, etaSeconds, now) }

        override fun updateOutputPath(id: String, location: String?, now: Long) =
            runBlocking { dao.updateOutputPath(id, location, now) }

        override fun setStatus(id: String, status: DownloadStatus, error: String?, now: Long) =
            runBlocking { dao.setStatus(id, AppStatus.valueOf(status.name), error, now) }
    }

    private val area = object : WorkArea {
        override fun workFile(id: String): File = storage.workFile(id)

        override fun publishFile(source: File, preferredName: String, destinationTreeUri: String?, category: DownloadCategory): PublishedTarget =
            PublishedTarget(storage.publishFile(source, preferredName, destinationTreeUri, category).location)

        override fun scan(file: File) = storage.scan(file)
    }

    private val policies = object : TransferPolicyProvider {
        override suspend fun policyFor(id: String): TransferPolicy {
            val current = settings.currentDownloadSettings()
            return TransferPolicy(
                maxRetries = current.maxRetries,
                speedLimitBytesPerSecond = current.speedLimitBytesPerSecond,
                useSpeedLimit = current.isSpeedLimited,
                connections = current.connectionsPerDownload,
                proxy = settings.currentAdvanced().proxy.toProxy()
            )
        }
    }

    suspend fun download(item: DownloadEntity) {
        val treeUri = settings.currentDestinationTreeUri()
        com.downloadhub.core.HttpDownloader(
            store = store,
            area = area,
            policies = policies,
            speedLimiter = speedLimiter,
            destinationTreeUri = { treeUri }
        ).download(item.toCoreItem())
    }
}
