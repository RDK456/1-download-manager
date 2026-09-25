package com.downloadhub.app.download

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.downloadhub.app.DownloadHubApplication
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.isActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

class DownloadService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queuedIds = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private lateinit var app: DownloadHubApplication
    private lateinit var httpDownloader: HttpDownloader
    private lateinit var youtubeDownloader: YoutubeDownloader
    private lateinit var torrentEngine: TorrentEngine
    private var queueReady = false

    override fun onCreate() {
        super.onCreate()
        app = application as DownloadHubApplication
        val dao = app.container.database.downloadDao()
        httpDownloader = HttpDownloader(dao, app.container.storage, app.container.settings)
        youtubeDownloader = app.container.youtubeDownloader
        torrentEngine = TorrentEngine(applicationContext)

        startForegroundCompat(
            DownloadNotifications.foreground(applicationContext, emptyList())
        )
        observeQueue(dao)
        repeat(3) {
            scope.launch {
                for (id in queue) {
                    val job = scope.launch(start = CoroutineStart.LAZY) { process(id) }
                    activeJobs[id] = job
                    job.start()
                    job.join()
                    activeJobs.remove(id)
                    queuedIds.remove(id)
                    stopIfIdle()
                }
            }
        }
        scope.launch {
            dao.recoverInterrupted(System.currentTimeMillis())
            dao.getByStatuses(
                listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING)
            ).forEach { enqueue(it.id) }
            queueReady = true
            if (dao.getByStatuses(listOf(DownloadStatus.QUEUED)).isEmpty()) {
                stopIfIdle()
            }
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
            ACTION_START -> intent.getStringArrayListExtra(EXTRA_IDS)?.forEach(::enqueue)
            ACTION_RECOVER -> scope.launch {
                app.container.database.downloadDao().getByStatuses(
                    listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING)
                ).forEach { enqueue(it.id) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (::torrentEngine.isInitialized) runCatching { torrentEngine.shutdown() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeQueue(dao: com.downloadhub.app.data.local.DownloadDao) {
        scope.launch {
            dao.observeAll().collectLatest { items ->
                if (!queueReady) return@collectLatest
                val active = items.filter { it.status.isActive }
                if (active.isEmpty()) {
                    stopIfIdle()
                } else if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
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
        val dao = app.container.database.downloadDao()
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.QUEUED) return

        try {
            when (item.source) {
                DownloadSource.HTTP -> runHttp(item)
                DownloadSource.YOUTUBE -> runYoutube(item)
                DownloadSource.TORRENT -> runTorrent(item)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.takeIf { it.isNotBlank() }?.take(500) ?: "Download failed"
            dao.setStatus(id, DownloadStatus.FAILED, message, System.currentTimeMillis())
            dao.getById(id)?.let { DownloadNotifications.showFailed(applicationContext, it) }
        }
    }

    private suspend fun runHttp(item: DownloadEntity) {
        val dao = app.container.database.downloadDao()
        val started = dao.transitionStatus(
            item.id,
            DownloadStatus.QUEUED,
            DownloadStatus.RUNNING,
            null,
            System.currentTimeMillis()
        )
        if (started == 0 || dao.getById(item.id)?.status != DownloadStatus.RUNNING) return
        httpDownloader.download(item)
    }

    private suspend fun runYoutube(item: DownloadEntity) {
        val dao = app.container.database.downloadDao()
        val started = dao.transitionStatus(
            item.id,
            DownloadStatus.QUEUED,
            DownloadStatus.RESOLVING,
            null,
            System.currentTimeMillis()
        )
        if (started == 0 || dao.getById(item.id)?.status != DownloadStatus.RESOLVING) return
        youtubeDownloader.download(item, scope)
    }

    private suspend fun runTorrent(item: DownloadEntity) {
        val dao = app.container.database.downloadDao()
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
        var snapshot = torrentEngine.start(current)
            ?: throw IOException("The torrent engine could not start this item")
        while (scope.isActive && current.status != DownloadStatus.PAUSED) {
            currentCoroutineContext().ensureActive()
            snapshot = torrentEngine.poll(current) ?: snapshot
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
                torrentEngine.pause(current)
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
        val dao = app.container.database.downloadDao()
        val item = dao.getById(id) ?: return
        dao.setStatus(id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
        if (item.source == DownloadSource.YOUTUBE) {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(id)
        }
        if (item.source == DownloadSource.TORRENT) torrentEngine.pause(item)
        activeJobs[id]?.cancel()
    }

    private suspend fun resume(id: String) {
        val dao = app.container.database.downloadDao()
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.PAUSED && item.status != DownloadStatus.FAILED) return
        activeJobs[id]?.join()
        queuedIds.remove(id)
        dao.setStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        enqueue(id)
    }

    private suspend fun retry(id: String) {
        val dao = app.container.database.downloadDao()
        val item = dao.getById(id) ?: return
        if (item.status != DownloadStatus.FAILED && item.status != DownloadStatus.PAUSED) return
        activeJobs[id]?.join()
        queuedIds.remove(id)
        dao.setStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        enqueue(id)
    }

    private suspend fun cancel(id: String) {
        val dao = app.container.database.downloadDao()
        val item = dao.getById(id) ?: return
        if (item.source == DownloadSource.YOUTUBE) {
            com.yausername.youtubedl_android.YoutubeDL.getInstance().destroyProcessById(id)
        }
        if (item.source == DownloadSource.TORRENT) torrentEngine.remove(item, deleteFiles = true)
        activeJobs[id]?.cancel()
        app.container.storage.deleteWork(id)
        app.container.storage.deleteOutput(item.outputPath)
        if (item.torrentFilePath != null) File(item.torrentFilePath).delete()
        dao.deleteById(id)
    }

    private suspend fun pauseAll() {
        val dao = app.container.database.downloadDao()
        dao.getByStatuses(
            listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING)
        ).forEach { pause(it.id) }
    }

    private suspend fun resumeAll() {
        val dao = app.container.database.downloadDao()
        dao.getByStatuses(listOf(DownloadStatus.PAUSED)).forEach { item ->
            dao.setStatus(item.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
            enqueue(item.id)
        }
    }

    private fun enqueue(id: String) {
        if (queuedIds.add(id)) queue.trySend(id)
    }

    private fun stopIfIdle() {
        if (queueReady && activeJobs.isEmpty() && queuedIds.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        const val ACTION_START = "com.downloadhub.app.action.START"
        const val ACTION_PAUSE = "com.downloadhub.app.action.PAUSE"
        const val ACTION_RESUME = "com.downloadhub.app.action.RESUME"
        const val ACTION_RETRY = "com.downloadhub.app.action.RETRY"
        const val ACTION_CANCEL = "com.downloadhub.app.action.CANCEL"
        const val ACTION_PAUSE_ALL = "com.downloadhub.app.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.downloadhub.app.action.RESUME_ALL"
        const val ACTION_RECOVER = "com.downloadhub.app.action.RECOVER"
        const val EXTRA_ID = "download_id"
        const val EXTRA_IDS = "download_ids"
        private const val MAX_TORRENT_METADATA_BYTES = 10L * 1024L * 1024L

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
