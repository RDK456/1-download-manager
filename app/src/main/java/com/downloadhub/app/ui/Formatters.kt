package com.downloadhub.app.ui

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadStatus
import java.util.Locale
import kotlin.math.abs

fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1000.0
        unit++
    } while (value >= 1000 && unit < units.lastIndex)
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "—" else "${formatBytes(bytesPerSecond)}/s"

fun formatEta(seconds: Long): String {    if (seconds < 0) return "estimating"
    val safe = seconds.coerceAtMost(99 * 60 * 60)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remaining = safe % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${remaining}s"
        else -> "${remaining}s"
    }
}

/** Media length for the thumbnail badge, e.g. `3:05` or `1:02:03`. */
fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remaining = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remaining)
    } else {
        "%d:%02d".format(minutes, remaining)
    }
}

fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.RESOLVING -> "Resolving"
    DownloadStatus.RUNNING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Needs attention"
}

fun progressFor(item: DownloadEntity): Float = when {
    item.status == DownloadStatus.COMPLETED -> 1f
    item.totalBytes > 0 -> (item.bytesDownloaded.toFloat() / item.totalBytes).coerceIn(0f, 1f)
    else -> (item.progressPercent.coerceIn(0, 100) / 100f)
}

fun progressLabel(item: DownloadEntity): String = when {
    item.status == DownloadStatus.COMPLETED -> "100%"
    item.totalBytes > 0 -> "${(item.bytesDownloaded * 100 / item.totalBytes).coerceIn(0, 100)}%"
    else -> "${item.progressPercent.coerceIn(0, 100)}%"
}

fun relativeTime(timestamp: Long): String {
    val seconds = abs(System.currentTimeMillis() - timestamp) / 1000
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}
