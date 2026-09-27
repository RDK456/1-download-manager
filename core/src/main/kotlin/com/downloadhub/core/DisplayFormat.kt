package com.downloadhub.core

/**
 * Human-readable formatting shared by both builds, so a size or a "6 minutes ago"
 * looks identical on a phone and on Windows.
 */
object DisplayFormat {

    fun bytes(value: Long): String = when {
        value >= 1_000_000_000 -> String.format("%.2f GB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format("%.2f MB", value / 1_000_000.0)
        value >= 1_000 -> String.format("%.1f KB", value / 1_000.0)
        else -> "$value B"
    }

    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "" else "${bytes(value = bytesPerSecond)}/s"

    /** A countdown, or a dash when there is nothing to count. */
    fun timeLeft(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "-"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format("%d:%02d:%02d", h, m, s)
            m > 0 -> String.format("%d:%02d", m, s)
            else -> String.format("%ds", s)
        }
    }

    /**
     * Relative time, the way a download list reads best.
     *
     * Anything older than a week falls back to a date, because "63 days ago" helps
     * nobody.
     */
    fun timeAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return "-"
        val seconds = ((now - timestamp) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> plural(seconds / 60, "minute")
            seconds < 86_400 -> plural(seconds / 3600, "hour")
            seconds < 604_800 -> plural(seconds / 86_400, "day")
            seconds < 2_592_000 -> plural(seconds / 604_800, "week")
            else -> date(timestamp)
        }
    }

    private fun plural(value: Long, unit: String): String {
        val count = value.coerceAtLeast(1)
        return "$count $unit${if (count == 1L) "" else "s"} ago"
    }

    private fun date(timestamp: Long): String {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val month = java.text.DateFormatSymbols.getInstance()
            .shortMonths[calendar.get(java.util.Calendar.MONTH)]
        return "${calendar.get(java.util.Calendar.DAY_OF_MONTH)} $month ${calendar.get(java.util.Calendar.YEAR)}"
    }

    /** Status word shown in the Status column. */
    fun status(item: DownloadItem): String = when (item.status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.RESOLVING -> "Connecting"
        DownloadStatus.RUNNING -> "Downloading"
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.COMPLETED -> "Finished"
        DownloadStatus.FAILED -> item.errorMessage?.let { "Failed: $it" } ?: "Failed"
    }
}
