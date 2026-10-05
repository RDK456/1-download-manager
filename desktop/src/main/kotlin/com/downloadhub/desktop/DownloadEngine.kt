package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
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

    /**
     * The app-wide speed cap, and the per-file ones.
     *
     * A single limiter for the whole app, held here and applied from the settings.
     * Previously a fresh `SpeedLimiter()` was constructed inside every download, so each
     * transfer had a private bucket that nothing ever set a limit on - the "speed limit"
     * setting did nothing on Windows, and the one in Settings was a control that looked
     * live and was not.
     */
    private val globalLimiter = com.downloadhub.core.SpeedLimiter()
    private val itemLimiters = java.util.concurrent.ConcurrentHashMap<String, com.downloadhub.core.SpeedLimiter>()

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
            val own = store.get(id)?.speedLimitBytesPerSecond ?: 0L
            val effective = com.downloadhub.core.TransferRules.effectiveSpeedLimit(own, current.effectiveDownloadLimit())
            return TransferPolicy(
                maxRetries = current.maxRetries,
                speedLimitBytesPerSecond = effective,
                useSpeedLimit = current.speedLimitEnabled || own > 0L,
                connections = current.connectionsPerDownload.coerceIn(1, 16),
                proxy = current.proxySetting().toProxy()
            )
        }
    }

    // --- queue control ------------------------------------------------------

    private var pumpJob: kotlinx.coroutines.Job? = null

    /** Starts every queued item that is not already transferring. */
    fun pump() {
        if (paused) return
        // One loop. Every caller used to launch another endless one, so each Options
        // save or queue start added a loop that never went away.
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (isActive) {
                val limit = settingsState.value.maxConcurrent.coerceIn(1, 16)
                // Re-read every pass, so changing the limit in Settings takes effect on
                // the next chunk rather than at the next restart.
                scope.launch { globalLimiter.setLimit(settingsState.value.effectiveDownloadLimit()) }
                slots.withLock {
                    val free = limit - running.size
                    if (free <= 0) return@withLock
                    val now = System.currentTimeMillis()
                    val all = store.snapshot()
                    val queueSettings = settingsState.value
                    val ordered = all
                          // Only plain HTTP belongs to this engine. A YouTube link is
                          // a web page, not a file: letting the HTTP downloader claim
                          // one saved the returned HTML into the download folder
                          // alongside the real video, as a stray file called "watch"
                          // - named after the last segment of the URL - and overwrote
                          // the row's size and type with the page's.
                          .filter {
                              it.source == DownloadSource.HTTP &&
                                  (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RESOLVING)
                          }
                          // A download told to start later keeps its place in the list
                          // and simply is not started yet. It comes back on its own when
                          // the time arrives, because this loop keeps running.
                          .filter { com.downloadhub.core.TransferRules.isStartable(it.startAfterEpochMillis, now) }
                          // Mapped before sorting so the queue order comes from the one
                          // rule in :core rather than a second copy of it here. Higher
                          // priority first, then the order they were added - before
                          // this the queue was insertion order only, so one large file
                          // added first held the slots while everything behind it waited.
                          .map { it.toCoreItem() }
                          .sortedWith(com.downloadhub.core.TransferRules.queueOrder())
                    // A stopped queue starts nothing, and a queue with its own limit
                    // counts everything of its own that is moving, torrents included.
                    val next = com.downloadhub.core.QueueRules.admit(
                        ordered = ordered,
                        queueOf = { it.queueId },
                        runningPerQueue = all.filter { it.status == DownloadStatus.RUNNING }
                            .groupingBy { it.queueId }.eachCount(),
                        isStarted = { queueSettings.queue(it).started },
                        limitOf = { queueSettings.queue(it).maxConcurrent },
                        free = free
                    )
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
                val own = item.speedLimitBytesPerSecond
                // Only build a limiter when the file has a limit of its own; otherwise
                // there is nothing for it to do.
                val itemLimiter = if (own > 0L) {
                    com.downloadhub.core.SpeedLimiter().also {
                        scope.launch { it.setLimit(own) }
                        itemLimiters[id] = it
                    }
                } else {
                    null
                }
                val downloader = com.downloadhub.core.HttpDownloader(
                    store = storeAdapter,
                    area = area,
                    policies = policies,
                    // The shared one, not a new bucket per download.
                    speedLimiter = globalLimiter,
                    itemSpeedLimiter = itemLimiter,
                    // The folder chosen in the Add Download window, until the download
                    // finishes and outputPath becomes the file itself.
                    destinationTreeUri = { item.outputPath?.takeIf { it.isNotBlank() && !java.io.File(it).isFile } }
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
                itemLimiters.remove(id)
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
