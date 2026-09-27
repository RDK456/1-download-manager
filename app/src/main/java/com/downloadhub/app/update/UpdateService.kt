package com.downloadhub.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.app.Service
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.downloadhub.app.R
import com.downloadhub.app.ui.MainActivity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads the release APK as a foreground service.
 *
 * The update used to run in a ViewModel coroutine, which Android tears down with
 * the Activity: minimising the app or leaving the page cancelled the transfer and
 * the user never got the update. This service owns the download, holds a wake
 * lock, shows an ongoing notification, and finishes the install hand-off even if
 * the app was killed in the meantime, so the download survives until it is done.
 */
class UpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> {
                job?.cancel()
                stopEverything()
            }
            else -> stopEverything()
        }
        // The work is short and user-initiated; do not resurrect a dead transfer.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        val releaseName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (url.isBlank() || version.isBlank()) {
            stopEverything()
            return
        }
        // A second tap while a transfer is running must not start a rival copy.
        if (job?.isActive == true) return

        startForegroundCompat(buildNotification(0, 0L, 0L))
        acquireWakeLock()

        job = scope.launch {
            var result: Result<File>? = null
            runCatching {
                withContext(Dispatchers.IO) {
                    download(url, version, releaseName)
                }
            }.onSuccess { result = it }
                .onFailure { error ->
                    UpdateTransferState.fail(error.message ?: "Update download failed")
                }

            val file = result?.getOrNull()
            if (file == null) {
                stopEverything()
                return@launch
            }
            // Publish the finished file so a new process can offer the install.
            UpdateTransferState.complete(version, releaseName, file)
            notifyReady(version, releaseName)
            stopEverything()
        }
    }

    /**
     * Streams the APK to a part file and only renames it into place once the whole
     * body has arrived, so an interrupted transfer never leaves a truncated file
     * that would be offered for install.
     */
    private suspend fun download(url: String, version: String, releaseName: String): Result<File> {
        val directory = File(filesDir, "updates").apply { mkdirs() }
        val target = File(directory, "1-download-manager-$version.apk")
        val part = File(directory, "1-download-manager-$version.apk.part")
        if (target.isFile && target.length() > 0L) {
            // Already fetched by an earlier run; nothing to do.
            return Result.success(target)
        }

        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("Accept", APK_MIME_TYPE)
            setRequestProperty("User-Agent", "1-download-manager")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) return Result.failure(IllegalStateException("HTTP $status"))
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            var lastPublished = 0L
            UpdateTransferState.start(version, releaseName, total)
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastPublished >= PROGRESS_INTERVAL_MILLIS) {
                            lastPublished = now
                            val percent = if (total > 0) {
                                ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            UpdateTransferState.progress(downloaded, total)
                            notifyProgress(percent, downloaded, total)
                        }
                    }
                }
            }
            if (part.length() <= 0L) return Result.failure(IllegalStateException("The update was empty"))
            if (total > 0 && part.length() != total) {
                return Result.failure(IllegalStateException("The update download was incomplete"))
            }
            if (!part.renameTo(target)) {
                return Result.failure(IllegalStateException("Could not save the update"))
            }
            return Result.success(target)
        } finally {
            connection.disconnect()
        }
    }

    private fun notifyProgress(percent: Int, downloaded: Long, total: Long) {
        post(NOTIFICATION_ID, buildNotification(percent, downloaded, total))
    }

    private fun notifyReady(version: String, releaseName: String) {
        val notification = NotificationCompat.Builder(this, READY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Update ready to install")
            .setContentText("$releaseName has been downloaded")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$releaseName finished downloading while the app was in the background. Open the app to install it.")
            )
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(READY_ID, notification)
    }

    /**
     * Posts a notification, honouring the runtime permission.
     *
     * The foreground notification is posted by startForeground, which is allowed
     * without POST_NOTIFICATIONS, but the "update ready" alert is not: on Android 13
     * a denied permission would throw SecurityException here and take the service
     * down mid-transfer.
     */
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
    }

    private fun buildNotification(percent: Int, downloaded: Long, total: Long): Notification =
        NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading update")
            .setContentText(
                when {
                    total > 0 -> "$percent% • ${formatBytes(downloaded)} of ${formatBytes(total)}"
                    downloaded > 0 -> formatBytes(downloaded)
                    else -> "Starting…"
                }
            )
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, total <= 0L)
            .addAction(
                R.drawable.ic_stat_download,
                "Cancel",
                servicePendingIntent(ACTION_CANCEL)
            )
            .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, UpdateService::class.java).apply { this.action = action },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL,
                "App updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing progress while the app updates itself"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                READY_CHANNEL,
                "Update ready",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a downloaded update is waiting to be installed"
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(android.os.PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "1-download-manager:update"
        ).apply {
            setReferenceCounted(false)
            // Bounded so a wedged transfer cannot hold the CPU indefinitely.
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopEverything() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.downloadhub.app.action.UPDATE_START"
        const val ACTION_CANCEL = "com.downloadhub.app.action.UPDATE_CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION = "version"
        const val EXTRA_NAME = "name"
        private const val NOTIFICATION_ID = 4400
        private const val READY_ID = 4401
        private const val PROGRESS_CHANNEL = "app_update_progress"
        private const val READY_CHANNEL = "app_update_ready"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val PROGRESS_INTERVAL_MILLIS = 400L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 30L * 60L * 1000L

        fun start(context: Context, url: String, version: String, name: String) {
            val intent = Intent(context, UpdateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_NAME, name)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, UpdateService::class.java).apply {
                action = ACTION_CANCEL
            }
            runCatching { context.startService(intent) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
