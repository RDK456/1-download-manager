package com.downloadhub.app.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.downloadhub.app.R
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.ui.MainActivity
import java.util.Locale

/**
 * Notification plumbing.
 *
 * Two channels: a quiet "progress" channel for the ongoing, non-dismissable
 * foreground notification, and a high-importance "complete" channel so finished
 * and failed downloads actually alert. Progress updates use `setOnlyAlertOnce`
 * so a fast download does not buzz on every tick.
 */
object DownloadNotifications {
    const val FOREGROUND_ID = 4100
    private const val FINISHED_ID = 4200
    private const val FAILED_ID = 4300
    private const val PROGRESS_CHANNEL = "download_progress"
    private const val COMPLETE_CHANNEL = "download_complete"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val progress = manager.getNotificationChannel(PROGRESS_CHANNEL)
        if (progress == null || progress.importance != NotificationManager.IMPORTANCE_LOW) {
            manager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL,
                    context.getString(R.string.notification_channel_downloads),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Ongoing progress and queue controls for active downloads"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        val complete = manager.getNotificationChannel(COMPLETE_CHANNEL)
        if (complete == null || complete.importance != NotificationManager.IMPORTANCE_HIGH) {
            manager.createNotificationChannel(
                NotificationChannel(
                    COMPLETE_CHANNEL,
                    context.getString(R.string.notification_channel_complete),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a download finishes or fails"
                    enableVibration(true)
                }
            )
        }
    }

    fun foreground(context: Context, active: List<DownloadEntity>): Notification {
        val running = active.count {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.RESOLVING
        }
        val queued = active.size - running
        val first = active.firstOrNull()
        val percent = first?.progressPercent?.coerceIn(0, 100) ?: 0
        val title = when {
            active.isEmpty() -> "Preparing downloads"
            active.size == 1 -> first?.fileName ?: "Download"
            else -> "$running active" + if (queued > 0) ", $queued queued" else ""
        }
        val text = first?.let {
            when {
                it.status == DownloadStatus.QUEUED -> "Waiting in queue"
                it.totalBytes > 0 -> buildString {
                    append(formatBytes(it.bytesDownloaded))
                    append(" of ")
                    append(formatBytes(it.totalBytes))
                    if (it.speedBytesPerSecond > 0) {
                        append(" • ")
                        append(formatBytes(it.speedBytesPerSecond))
                        append("/s")
                    }
                }
                else -> "${it.progressPercent}% • ${it.source.name.lowercase(Locale.US)}"
            }
        } ?: "Starting queue"

        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context, first?.id))
            // Ongoing + no dismiss: Android will not let it be swiped away, which
            // is what makes the transfer feel like it survives leaving the app.
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent, first?.totalBytes == 0L)
            .addAction(
                R.drawable.ic_stat_download,
                context.getString(R.string.notification_pause_all),
                serviceAction(context, DownloadService.ACTION_PAUSE_ALL)
            )
        if (active.size > 1) {
            builder.setSubText("${active.size} in queue")
        } else {
            first?.let { item ->
                builder.setSubText(
                    item.category.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
                )
            }
        }
        return builder.build()
    }

    fun showFinished(context: Context, item: DownloadEntity) {
        val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(item.fileName)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${item.fileName}\n${formatBytes(item.bytesDownloaded)} saved"
                )
            )
            .setContentIntent(contentIntent(context, item.id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(
                R.drawable.ic_stat_download,
                "Open",
                contentIntent(context, item.id)
            )
            .build()
        notify(context, FINISHED_ID + item.id.hashCode(), notification)
    }

    fun showFailed(context: Context, item: DownloadEntity) {
        val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download needs attention")
            .setContentText(item.errorMessage ?: item.fileName)
            .setContentIntent(contentIntent(context, item.id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .addAction(
                R.drawable.ic_stat_download,
                "Retry",
                serviceAction(context, DownloadService.ACTION_RETRY, item.id)
            )
            .build()
        notify(context, FAILED_ID + item.id.hashCode(), notification)
    }

    private fun contentIntent(context: Context, id: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_DOWNLOAD_ID, id)
        }
        return PendingIntent.getActivity(
            context,
            id?.hashCode() ?: 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceAction(
        context: Context,
        action: String,
        id: String? = null
    ): PendingIntent = PendingIntent.getService(
        context,
        (action + id.orEmpty()).hashCode(),
        Intent(context, DownloadService::class.java).apply {
            this.action = action
            if (id != null) putExtra(DownloadService.EXTRA_ID, id)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
