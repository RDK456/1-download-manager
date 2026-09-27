package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.DownloadStore
import com.downloadhub.core.TransferPolicy
import com.downloadhub.core.TransferPolicyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Binds the shared [com.downloadhub.core.HttpDownloader] to the desktop queue.
 *
 * Owns concurrency, pause/resume and the settings the engine reads. Progress is
 * written through [DownloadStore] so the running UI and the engine never disagree.
 */
class DownloadEngine(
    private val store: DesktopStore,
    private val area: DesktopWorkArea,
    private val settingsState: StateFlow<DesktopSettings>,
    private val onChange: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val slots = Mutex()
    private val running = LinkedHashMap<String, Job>()

    /** Set while the user has asked for everything to stop. */
    @Volatile
    private var paused = false

    private val _busy = MutableStateFlow(0)
    val busy: StateFlow<Int> = _busy.asStateFlow()

    // --- core port adapters -------------------------------------------------

    private val storeAdapter = object : DownloadStore {
        override fun updateValidators(id: String, etag: String?, lastModified: String?, now: Long) {
            store.update(id) { it.copy(etag = etag, lastModified = lastModified) }
            onChange()
        }

        override fun updateMetadata(
            id: String,
            fileName: String,
            mimeType: String?,
            category: DownloadCategory,
            totalBytes: Long,
            now: Long
        ) {
            store.update(id) {
                it.copy(fileName = fileName, mimeType = mimeType, category = category, totalBytes = totalBytes)
            }
            onChange()
        }

        override fun updateProgress(
            id: String,
            bytesDownloaded: Long,
            totalBytes: Long,
            percent: Int,
            speedBytesPerSecond: Long,
            etaSeconds: Long,
            now: Long
        ) {
            store.update(id) {
                it.copy(
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    speedBytesPerSecond = speedBytesPerSecond
                )
            }
            onChange()
        }

        override fun updateOutputPath(id: String, location: String?, now: Long) {
            store.update(id) { it.copy(location = location) }
            onChange()
        }

        override fun setStatus(
            id: String,
            status: DownloadStatus,
            error: String?,
            now: Long
        ) {
            store.update(id) {
                it.copy(
                    status = status,
                    errorMessage = error,
                    // A finished or failed transfer has no meaningful speed.
                    speedBytesPerSecond = if (status.isSettled()) 0L else it.speedBytesPerSecond
                )
            }
            onChange()
        }
    }

    private val policies = object : TransferPolicyProvider {
        override suspend fun policyFor(id: String): TransferPolicy {
            val current = settingsState.value
            return TransferPolicy(
                maxRetries = current.maxRetries,
                speedLimitBytesPerSecond = current.speedLimitBytesPerSecond,
                useSpeedLimit = current.speedLimitEnabled
            )
        }
    }

    // --- queue control ------------------------------------------------------

    /** Starts every queued item that is not already transferring. */
    fun pump() {
        if (paused) return
        scope.launch {
            val limit = settingsState.value.maxConcurrent.coerceIn(1, 16)
            while (isActive) {
                slots.withLock {
                    val free = limit - running.size
                    if (free <= 0) return@withLock
                    val next = store.snapshot()
                        .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RESOLVING }
                        .take(free)
                    for (item in next) start(item.id)
                }
                delay(400)
            }
        }
    }

    private fun start(id: String) {
        if (running.containsKey(id)) return
        val item = store.get(id) ?: return
        store.update(id) { it.copy(status = DownloadStatus.RUNNING, errorMessage = null) }
        onChange()
        val job = scope.launch {
            try {
                val downloader = com.downloadhub.core.HttpDownloader(
                    store = storeAdapter,
                    area = area,
                    policies = policies,
                    speedLimiter = com.downloadhub.core.SpeedLimiter(),
                    destinationTreeUri = { null }
                )
                downloader.download(item.toCoreItem())
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    store.update(id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = error.message ?: error::class.simpleName
                        )
                    }
                }
            } finally {
                running.remove(id)
                _busy.value = running.size
                store.persist()
                onChange()
            }
        }
        running[id] = job
        _busy.value = running.size
    }

    /** Pauses one item, keeping its partial file so a resume continues. */
    fun pause(id: String) {
        running.remove(id)?.cancel()
        store.update(id) {
            if (it.status == DownloadStatus.COMPLETED) it
            else it.copy(status = DownloadStatus.PAUSED, speedBytesPerSecond = 0L)
        }
        _busy.value = running.size
        store.persist()
        onChange()
    }

    fun resume(id: String) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, errorMessage = null) }
        onChange()
        store.persist()
        start(id)
    }

    fun retry(id: String) = resume(id)

    fun pauseAll() {
        paused = true
        store.snapshot().filter { it.status.isLive() }.forEach { pause(it.id) }
    }

    fun resumeAll() {
        paused = false
        store.snapshot()
            .filter { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }
            .forEach { resume(it.id) }
    }

    fun remove(id: String) {
        running.remove(id)?.cancel()
        store.delete(id)
        store.persist()
        onChange()
    }

    fun close() {
        scope.cancel()
    }
}

private fun DownloadStatus.isSettled() = this == DownloadStatus.COMPLETED || this == DownloadStatus.FAILED
private fun DownloadStatus.isLive() =
    this == DownloadStatus.RUNNING || this == DownloadStatus.QUEUED || this == DownloadStatus.RESOLVING

private fun QueuedDownload.toCoreItem() = DownloadItem(
    id = id,
    url = url,
    fileName = fileName,
    source = source,
    status = status,
    category = category,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    errorMessage = errorMessage,
    location = location,
    etag = etag,
    lastModified = lastModified,
    mimeType = mimeType,
    quality = quality,
    audioFormat = audioFormat,
    playlist = playlist
)
