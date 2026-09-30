package com.downloadhub.app.download

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.downloadhub.app.DownloadHubApplication
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.isActive
import com.downloadhub.app.ui.classifyTorrent
import com.downloadhub.app.ui.largestFileIn
import com.downloadhub.app.data.local.DownloadDao
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.core.FileChoiceCodec
import com.downloadhub.core.TorrentEngine
import com.downloadhub.core.TransferRules
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the queue as a foreground service.
 *
 * Reliability notes: the service acquires a partial wake lock so transfers keep
 * running while the screen is off, only stops itself once the database shows no
 * queued or running rows, honours the Wi-Fi-only and concurrency settings, and
 * retries transient failures automatically.
 */
/**
 * How long a deferred download waits before looking at the clock again.
 *
 * A minute is deliberate: "start later" options are hours away, so checking more often
 * only costs wake-ups, and the row's own countdown is drawn from the same stored time.
 */
private const val SCHEDULED_RECHECK_MILLIS = 60_000L
class DownloadService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queuedIds = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val workers = mutableListOf<Job>()

    private lateinit var app: DownloadHubApplication
    private lateinit var httpDownloader: HttpDownloader
    private lateinit var youtubeDownloader: YoutubeDownloader
    private lateinit var torrentEngine: TorrentEngine
    private lateinit var networkMonitor: NetworkMonitor
    private var wakeLock: PowerManager.WakeLock? = null
    private var queueReady = false

    private val dao: DownloadDao get() = app.container.database.downloadDao()

    override fun onCreate() {
        super.onCreate()
        app = application as DownloadHubApplication
        val settings = app.container.settings
        httpDownloader = HttpDownloader(dao, app.container.storage, settings, app.container.speedLimiter)
        youtubeDownloader = app.container.youtubeDownloader
        // Named, because TorrentEngine also takes an optional native-library
        // directory and a trailing lambda would bind to the wrong one.
        torrentEngine = TorrentEngine(torrentRoot = { DownloadStorage(applicationContext).torrentRoot() })
        networkMonitor = app.container.networkMonitor

        // Post the ongoing notification immediately: without this Android can
        // kill a started service before it reaches the foreground.
        startForegroundCompat(DownloadNotifications.foreground(applicationContext, emptyList()))
        holdWakeLock(true)

        observeQueue(dao)
        observeSettings(settings)
        scope.launch {
            val maxConcurrent = settings.currentDownloadSettings().maxConcurrent
            startWorkers(maxConcurrent)
            dao.recoverInterrupted(System.currentTimeMillis())
            enqueueAll(dao.getByStatuses(ACTIVE_STATUSES))
            queueReady = true
            stopIfIdle()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> scope.launch { pauseAll() }
            ACTION_RESUME_ALL -> scope.launch { resumeAll() }
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { scope.launch { pause(it) } }
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { scope.launch { resume(it) } }
            ACTION_RETRY -> intent.getStringExtra(EXTRA_ID)?.let { scope.launch { retry(it) } }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { scope.launch { cancel(it) } }
        // One file of one torrent, by index. An index below zero arrives from a control
        // that was not wired up, and is refused rather than clamped: clamping would set a
        // priority on a file that does not exist.
        ACTION_SET_FILE_PRIORITY -> intent.getStringExtra(EXTRA_ID)?.let { id ->
            val fileIndex = intent.getIntExtra(EXTRA_FILE_INDEX, -1)
            val priority = com.downloadhub.core.FilePriority
                .fromOrdinal(intent.getIntExtra(EXTRA_PRIORITY, -1))
            scope.launch { setFilePriority(id, fileIndex, priority) }
        }
            ACTION_START -> intent.getStringArrayListExtra(EXTRA_IDS)?.forEach(::enqueue)
            ACTION_RECOVER -> scope.launch { enqueueAll(dao.getByStatuses(ACTIVE_STATUSES)) }
            else -> scope.launch { enqueueAll(dao.getByStatuses(ACTIVE_STATUSES)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        holdWakeLock(false)
        scope.cancel()
        if (::torrentEngine.isInitialized) runCatching { torrentEngine.shutdown() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Re-scales the worker pool and re-applies the speed limit when settings change. */
    private fun observeSettings(settings: com.downloadhub.app.data.SettingsRepository) {
        scope.launch {
            settings.downloadSettings.collectLatest { current ->
                app.container.speedLimiter.setLimit(current.speedLimitBytesPerSecond)
                syncWorkers(current.maxConcurrent)
            }
        }
    }

    private fun syncWorkers(maxConcurrent: Int) {
        if (workers.size < maxConcurrent) {
            repeat(maxConcurrent - workers.size) { startWorker() }
        } else if (workers.size > maxConcurrent) {
            // Extra workers simply drain the queue; they exit when idle.
            workers.filter { !it.isActive }.forEach { workers.remove(it) }
        }
    }

    private fun startWorkers(maxConcurrent: Int) {
        syncWorkers(maxConcurrent)
    }

    private fun startWorker() {
        val worker = scope.launch {
            for (id in queue) {
                val job = scope.launch { process(id) }
                activeJobs[id] = job
                job.join()
                activeJobs.remove(id)
                queuedIds.remove(id)
                stopIfIdle()
            }
        }
        workers += worker
    }

    private fun observeQueue(dao: DownloadDao) {
        scope.launch {
            dao.observeAll().collectLatest { items ->
                if (!queueReady) return@collectLatest
                val active = items.filter { it.status.isActive }
                if (active.isEmpty()) {
                    stopIfIdle()
                    return@collectLatest
                }
                holdWakeLock(true)
                if (DownloadNotifications.canNotify(applicationContext)) {
                    // notify() still needs POST_NOTIFICATIONS on API 33+; the
                    // runCatching guards the SecurityException if it is revoked
                    // between the check above and this call.
                    @Suppress("MissingPermission")
                    runCatching {
                        NotificationManagerCompat.from(applicationContext).notify(
                            DownloadNotifications.FOREGROUND_ID,
                            DownloadNotifications.foreground(applicationContext, active)
                        )
                    }
                }
            }
        }
    }

    private suspend fun process(id: String) {
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.QUEUED) return

        // A download told to start later waits here rather than disappearing from the
        // list, so it keeps its place and shows as pending. Re-queued a minute out so it
        // starts itself when its time arrives, with nothing for the user to touch.
        if (!TransferRules.isStartable(item.startAfterEpochMillis, System.currentTimeMillis())) {
            delay(SCHEDULED_RECHECK_MILLIS)
            enqueue(id)
            return
        }
        val settings = app.container.settings.currentDownloadSettings()
        if (settings.wifiOnly && !networkMonitor.isWifiOnly()) {
            // Hold the item until a Wi-Fi network is available again.
            dao.setStatus(
                id,
                DownloadStatus.PAUSED,
                "Waiting for an unmetered network",
                System.currentTimeMillis()
            )
            return
        }

        try {
            when (item.source) {
                DownloadSource.HTTP -> runHttp(item)
                DownloadSource.YOUTUBE -> runYoutube(item)
                DownloadSource.TORRENT -> runTorrent(item)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            handleFailure(item, error)
        }

        if (app.container.settings.currentDownloadSettings().autoRemoveCompleted) {
            dao.getById(id)?.takeIf { it.status == DownloadStatus.COMPLETED }?.let {
                app.container.repository.delete(it)
            }
        }
        stopIfIdle()
    }

    /** Retries transient failures up to the configured limit before giving up. */
    private suspend fun handleFailure(item: DownloadEntity, error: Throwable) {
        val settings = app.container.settings.currentDownloadSettings()
        val message = error.message?.takeIf { it.isNotBlank() }?.take(500) ?: "Download failed"
        val transient = error is IOException
        val attempts = dao.getById(item.id)?.retryCount ?: 0
        if (transient && attempts < settings.maxRetries) {
            dao.updateRetryCount(item.id, attempts + 1)
            dao.setStatus(
                item.id,
                DownloadStatus.QUEUED,
                "Retrying (${attempts + 1}/${settings.maxRetries})",
                System.currentTimeMillis()
            )
            delay(RETRY_DELAY_MILLIS)
            enqueue(item.id)
            return
        }
        dao.setStatus(item.id, DownloadStatus.FAILED, message, System.currentTimeMillis())
        dao.getById(item.id)?.let { DownloadNotifications.showFailed(applicationContext, it) }
    }

    private suspend fun runHttp(item: DownloadEntity) {
        val started = dao.transitionStatus(
            item.id,
            DownloadStatus.QUEUED,
            DownloadStatus.RUNNING,
            null,
            System.currentTimeMillis()
        )
        if (started == 0 || dao.getById(item.id)?.status != DownloadStatus.RUNNING) return
        httpDownloader.download(item)
        dao.getById(item.id)?.let { DownloadNotifications.showFinished(applicationContext, it) }
    }

    private suspend fun runYoutube(item: DownloadEntity) {
        val started = dao.transitionStatus(
            item.id,
            DownloadStatus.QUEUED,
            DownloadStatus.RESOLVING,
            null,
            System.currentTimeMillis()
        )
        if (started == 0 || dao.getById(item.id)?.status != DownloadStatus.RESOLVING) return
        youtubeDownloader.download(item, scope)
        dao.getById(item.id)?.let { DownloadNotifications.showFinished(applicationContext, it) }
    }

    private suspend fun runTorrent(item: DownloadEntity) {
        var current = item
        if (current.torrentFilePath.isNullOrBlank() && current.url.startsWith("http", ignoreCase = true)) {
            val metadataFile = fetchTorrentMetadata(current)
            dao.updateTorrentInfo(current.id, null, metadataFile, System.currentTimeMillis())
            current = dao.getById(current.id) ?: current
        }

        val started = dao.transitionStatus(
            current.id,
            DownloadStatus.QUEUED,
            DownloadStatus.RUNNING,
            null,
            System.currentTimeMillis()
        )
        if (started == 0 || dao.getById(current.id)?.status != DownloadStatus.RUNNING) return
        holdWakeLock(true)
        var snapshot = torrentEngine.start(current.toCoreItem())
            ?: throw IOException("The torrent engine could not start this item")
        while (scope.isActive && current.status != DownloadStatus.PAUSED) {
            currentCoroutineContext().ensureActive()
            snapshot = torrentEngine.poll(current.toCoreItem()) ?: snapshot
            val currentStatus = dao.getById(current.id)?.status
            if (currentStatus != DownloadStatus.RUNNING) return@runTorrent

            val total = snapshot.totalBytes
            val percent = snapshot.percent.coerceIn(0, 100)
            dao.updateProgress(
                current.id,
                snapshot.bytesDownloaded,
                total,
                percent,
                snapshot.downloadRate,
                if (total > 0 && snapshot.downloadRate > 0) {
                    (total - snapshot.bytesDownloaded).coerceAtLeast(0) / snapshot.downloadRate
                } else {
                    -1
                },
                System.currentTimeMillis()
            )
            // Per file, for the file list. Published rather than stored; see [fileProgress].
            if (snapshot.fileProgress.isNotEmpty()) {
                fileProgress.value = fileProgress.value + (current.id to
                    snapshot.fileProgress.mapIndexed { index, bytes -> index to bytes }.toMap())
            }
            snapshot.name?.takeIf { it.isNotBlank() && it != current.fileName }?.let { name ->
                val safeName = LinkParser.sanitizeFileName(name)
                dao.updateMetadata(
                    current.id,
                    safeName,
                    current.mimeType,
                    current.category,
                    total,
                    System.currentTimeMillis()
                )
                current = dao.getById(current.id) ?: current
            }
            if (!snapshot.infoHash.isNullOrBlank() && snapshot.infoHash != current.torrentInfoHash) {
                dao.updateTorrentInfo(current.id, snapshot.infoHash, current.torrentFilePath, System.currentTimeMillis())
                current = dao.getById(current.id) ?: current
            }
            snapshot.error?.takeIf { it.isNotBlank() }?.let { throw IOException(it) }
            if (snapshot.isFinished) {
                torrentEngine.pause(current.toCoreItem())
                val sourceDirectory = current.outputPath
                    ?.let { path -> File(path) }
                    ?.takeIf { it.exists() }
                    ?: throw IOException("Torrent payload is missing from staging")
                val published = app.container.storage.publishDirectory(
                    source = sourceDirectory,
                    preferredName = current.fileName,
                    destinationTreeUri = app.container.settings.currentDestinationTreeUri()
                )
                val now = System.currentTimeMillis()
                val finalTotal = if (total > 0) total else snapshot.bytesDownloaded
                dao.updateOutputPath(current.id, published.location, now)
                // Now that the payload is on disk we know what it actually is.
                refreshTorrentMetadata(current, published.location, finalTotal, now)
                dao.updateProgress(
                    current.id,
                    snapshot.bytesDownloaded,
                    finalTotal,
                    100,
                    0,
                    -1,
                    now
                )
                dao.setStatus(current.id, DownloadStatus.COMPLETED, null, now)
                if (!published.location.startsWith("content:")) {
                    app.container.storage.scan(File(published.location))
                }
                dao.getById(current.id)?.let { DownloadNotifications.showFinished(applicationContext, it) }
                break
            }
            delay(750)
        }
    }

    /**
     * After a torrent lands, re-derive the real file name and category from the
     * published payload, so the category filter and previews mean something for
     * torrents too.
     */
    private suspend fun refreshTorrentMetadata(
        item: DownloadEntity,
        location: String,
        total: Long,
        now: Long
    ) {
        val file = location.takeIf { !it.startsWith("content:") }?.let { File(it) }
        val payload = when {
            file == null -> null
            file.isDirectory -> largestFileIn(file)
            file.isFile -> file
            else -> null
        }
        val refined = item.copy(outputPath = location, fileName = payload?.name ?: item.fileName)
        val category = classifyTorrent(refined)
        val displayName = if (file?.isDirectory == true) {
            LinkParser.sanitizeFileName(item.fileName)
        } else {
            LinkParser.sanitizeFileName(payload?.name ?: item.fileName)
        }
        val mime = payload?.extension?.lowercase()?.takeIf { it.isNotEmpty() }
        dao.updateMetadata(item.id, displayName, mime, category, total, now)
    }

    private suspend fun fetchTorrentMetadata(item: DownloadEntity): String {
        val file = app.container.storage.workFile(item.id)
        return withContext(Dispatchers.IO) {
            val connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", item.userAgent ?: "DownloadHub/1.0")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("Torrent metadata server returned HTTP ${connection.responseCode}")
                }
                val declaredSize = connection.contentLengthLong
                if (declaredSize > MAX_TORRENT_METADATA_BYTES) {
                    throw IOException("Torrent metadata is too large")
                }
                var total = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_TORRENT_METADATA_BYTES) {
                                throw IOException("Torrent metadata is too large")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                file.absolutePath
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun pause(id: String) {
        val item = dao.getById(id) ?: return
        dao.setStatus(id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
        if (item.source == DownloadSource.YOUTUBE) {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(id)
        }
        if (item.source == DownloadSource.TORRENT) torrentEngine.pause(item.toCoreItem())
        activeJobs[id]?.cancel()
    }

    /**
     * Sets one file's priority: stored first, then applied to the engine.
     *
     * Stored before applied, not the other way round. The reverse order leaves the row
     * claiming something the engine has not done yet, and if the apply then fails the row
     * keeps a change that was never made - which is the harder of the two to notice.
     */
    private suspend fun setFilePriority(
        id: String,
        fileIndex: Int,
        priority: com.downloadhub.core.FilePriority
    ) {
        val item = dao.getById(id) ?: return
        if (item.source != DownloadSource.TORRENT) return
        if (fileIndex < 0) return

        val merged = FileChoiceCodec.decodePriorities(item.torrentFilePriorities) +
            (fileIndex to priority)
        dao.updateTorrentFileChoices(
            id = id,
            selected = item.torrentSelectedFiles,
            priorities = FileChoiceCodec.encodePriorities(merged),
            updatedAt = System.currentTimeMillis()
        )

        // Applied from the row as it now stands, so the engine sees the same map the
        // database does rather than a half-updated copy.
        val saved = dao.getById(id) ?: return
        torrentEngine.setFilePriorities(saved.toCoreItem())
    }

    private suspend fun resume(id: String) {
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.FAILED) return
        activeJobs[id]?.join()
        queuedIds.remove(id)
        dao.updateRetryCount(id, 0)
        dao.setStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        enqueue(id)
    }

    private suspend fun retry(id: String) {
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.FAILED && item.status != DownloadStatus.PAUSED) return
        activeJobs[id]?.join()
        queuedIds.remove(id)
        dao.updateRetryCount(id, 0)
        dao.setStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        enqueue(id)
    }

    private suspend fun cancel(id: String) {
        val item = dao.getById(id) ?: return
        if (item.source == DownloadSource.YOUTUBE) {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(id)
        }
        if (item.source == DownloadSource.TORRENT) torrentEngine.remove(item.toCoreItem(), deleteFiles = true)
        activeJobs[id]?.cancel()
        app.container.storage.deleteWork(id)
        app.container.storage.deleteOutput(item.outputPath)
        app.container.thumbnailCache.discard(item.thumbnailPath, item.thumbnailUrl)
        if (item.torrentFilePath != null) File(item.torrentFilePath).delete()
        dao.deleteById(id)
    }

    private suspend fun pauseAll() {
        dao.getByStatuses(ACTIVE_STATUSES).forEach { pause(it.id) }
    }

    private suspend fun resumeAll() {
        dao.getByStatuses(listOf(DownloadStatus.PAUSED)).forEach { item ->
            dao.updateRetryCount(item.id, 0)
            dao.setStatus(item.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
            enqueue(item.id)
        }
    }

    /**
     * Puts pending items into the queue in the order they should be started.
     *
     * Higher priority first, then the order they were added - both decided by :core's
     * TransferRules rather than by whatever the database happened to return, so the
     * phone and the desktop agree. Before this it was insertion order only, so one
     * large download added first took a slot and everything behind it waited.
     */

    private fun enqueueAll(items: List<DownloadEntity>) {
        items.sortedWith(TransferRules.queueOrderBy(
            priorityRank = { it.priorityRank },
            createdAt = { it.createdAt },
            id = { it.id }
        )).forEach { enqueue(it.id) }
    }

    private fun enqueue(id: String) {
        if (queuedIds.add(id)) queue.trySend(id)
    }

    /**
     * Stops only when the database agrees the queue is empty. Checking the rows
     * (not just the in-memory sets) avoids stopping between a job finishing and
     * the next one being picked up.
     */
    private fun stopIfIdle() {
        if (!queueReady || activeJobs.isNotEmpty() || queuedIds.isNotEmpty()) return
        scope.launch {
            // Give a just-finished transition a moment to settle.
            repeat(4) {
                delay(250)
                if (activeJobs.isNotEmpty() || queuedIds.isNotEmpty()) return@launch
                if (dao.getByStatuses(ACTIVE_STATUSES).isNotEmpty()) return@launch
            }
            if (activeJobs.isEmpty() && queuedIds.isEmpty() &&
                dao.getByStatuses(ACTIVE_STATUSES).isEmpty()
            ) {
                holdWakeLock(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Keeps the CPU awake while transfers run, so the screen being off or the app
     * being in the background is harmless.
     *
     * A missing WAKE_LOCK permission throws here. That used to be swallowed by a
     * runCatching, which left downloads to stall in Doze, so it is now logged and
     * the caller can see the failure.
     */
    private fun holdWakeLock(acquire: Boolean) {
        val power = applicationContext.getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: power
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        try {
            if (acquire) {
                if (!lock.isHeld) {
                    lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                    Log.i(TAG, "Download wake lock acquired")
                }
            } else if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Download wake lock released")
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Cannot hold a wake lock; background transfers may stall", error)
        }
    }

    /**
     * Android 15 caps how long a `dataSync` foreground service may run and then
     * calls this. The queue is paused with a readable reason so the next launch
     * resumes it, instead of leaving rows stuck in RUNNING.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        scope.launch {
            Log.w(TAG, "Foreground service time budget exhausted; pausing the queue")
            dao.getByStatuses(ACTIVE_STATUSES).forEach { item ->
                dao.setStatus(
                    item.id,
                    DownloadStatus.PAUSED,
                    "Paused by Android after the background time limit",
                    System.currentTimeMillis()
                )
            }
        }
        holdWakeLock(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotifications.FOREGROUND_ID, notification)
        }
    }

    companion object {
/**
         * Bytes fetched per file, per download.
         *
         * Published rather than stored, and this comment is why. It changes every second
         * for every running torrent and is worth nothing after a restart, so putting it in
         * the database would mean a few hundred numbers per torrent rewritten continuously,
         * for figures that are all zero again by the time they are next read. A flow in the
         * service's own process is where they belong.
         *
         * Keyed by download id, and only for torrents: an HTTP download has no files.
         */
        val fileProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Map<Int, Long>>>(emptyMap())

        /**
         * Sets one file of one torrent's priority.
         *
         * A companion function because the screen cannot reach the service instance. The
         * intent is the only channel to it, and it is the same channel pause and resume
         * already use, so there is one way in rather than two.
         */
        fun requestFilePriority(
            context: Context,
            id: String,
            fileIndex: Int,
            priority: com.downloadhub.core.FilePriority
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_SET_FILE_PRIORITY
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_FILE_INDEX, fileIndex)
                putExtra(EXTRA_PRIORITY, priority.ordinal)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Drops a removed download's readings, so nothing is left behind for its id. */
        fun forgetFileProgress(id: String) {
            fileProgress.value = fileProgress.value - id
        }
        const val ACTION_START = "com.downloadhub.app.action.START"
        const val ACTION_PAUSE = "com.downloadhub.app.action.PAUSE"
        const val ACTION_RESUME = "com.downloadhub.app.action.RESUME"
        const val ACTION_RETRY = "com.downloadhub.app.action.RETRY"
        const val ACTION_CANCEL = "com.downloadhub.app.action.CANCEL"
        const val ACTION_SET_FILE_PRIORITY = "com.downloadhub.app.action.SET_FILE_PRIORITY"
        const val EXTRA_FILE_INDEX = "fileIndex"
        const val EXTRA_PRIORITY = "filePriority"
        const val ACTION_PAUSE_ALL = "com.downloadhub.app.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.downloadhub.app.action.RESUME_ALL"
        const val ACTION_RECOVER = "com.downloadhub.app.action.RECOVER"
        const val EXTRA_ID = "download_id"
        const val EXTRA_IDS = "download_ids"
        private const val MAX_TORRENT_METADATA_BYTES = 10L * 1024L * 1024L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val WAKE_LOCK_TAG = "1-download-manager:downloads"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 3L * 60L * 60L * 1000L
        private const val TAG = "DownloadService"

        private val ACTIVE_STATUSES = listOf(
            DownloadStatus.QUEUED,
            DownloadStatus.RESOLVING,
            DownloadStatus.RUNNING
        )

        fun start(context: Context, ids: List<String> = emptyList()) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_IDS, ArrayList(ids))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun action(context: Context, action: String, id: String? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                this.action = action
                if (id != null) putExtra(EXTRA_ID, id)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
