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

object DownloadNotifications {
    const val FOREGROUND_ID = 4100
    private const val FINISHED_ID = 4200
    private const val FAILED_ID = 4300
    private const val PROGRESS_CHANNEL = "download_progress"
    private const val COMPLETE_CHANNEL = "download_complete"
    private const val TORRENT_CHANNEL = "torrent_activity"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and queue controls for active downloads"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                COMPLETE_CHANNEL,
                context.getString(R.string.notification_channel_complete),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                TORRENT_CHANNEL,
                context.getString(R.string.notification_channel_torrent),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    fun foreground(context: Context, active: List<DownloadEntity>): Notification {
        val running = active.count { it.status == DownloadStatus.RUNNING }
        val queued = active.size - running
        val first = active.firstOrNull()
        val percent = first?.progressPercent?.coerceIn(0, 100) ?: 0
        val title = when {
            active.isEmpty() -> "Preparing downloads"
            active.size == 1 -> first?.fileName ?: "Download"
            else -> "$running active" + if (queued > 0) ", $queued queued" else ""
        }
        val text = first?.let {
            if (it.totalBytes > 0) {
                "${formatBytes(it.bytesDownloaded)} of ${formatBytes(it.totalBytes)}"
            } else {
                "${it.progressPercent}% • ${it.source.name.lowercase(Locale.US)}"
            }
        } ?: "Starting queue"

        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(context, first?.id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent, first?.totalBytes == 0L)
            .addAction(
                R.drawable.ic_stat_download,
                context.getString(R.string.notification_pause_all),
                serviceAction(context, DownloadService.ACTION_PAUSE_ALL)
            )

        active.firstOrNull()?.let { item ->
            builder.setSubText(item.category.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() })
        }
        return builder.build()
    }

    fun showFinished(context: Context, item: DownloadEntity) {
        val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(item.fileName)
            .setContentIntent(contentIntent(context, item.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
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
            .setCategory(NotificationCompat.CATEGORY_ERROR)
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

    private fun serviceAction(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, DownloadService::class.java).setAction(action),
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
