package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.canPause
import com.downloadhub.app.data.model.canResume
import com.downloadhub.app.ui.formatBytes
import com.downloadhub.app.ui.formatEta
import com.downloadhub.app.ui.formatSpeed
import com.downloadhub.app.ui.progressFor

/** Things outside a download that hold it still without being its fault. */
data class RowConditions(val offline: Boolean = false, val lowStorage: Boolean = false)

/** Which progress bar a row draws. */
enum class BarStyle {
    /** Nothing worth showing (finished, or not started). */
    NONE,

    /** A straight bar: the transfer is not moving right now. */
    FLAT,

    /** A wavy bar at the real value: the transfer is moving. */
    WAVY,

    /** A wavy bar with no value: the size or length is not known yet. */
    WAVY_INDETERMINATE
}

/**
 * Everything a download row shows, worked out once and free of Compose, so what a row says
 * in each state is unit tested rather than read off a screenshot.
 */
data class DownloadRowModel(
    val id: String,
    val name: String,
    val source: DownloadSource,
    val shape: StateShapeKind,
    val progress: Float,
    val percent: Int,
    val headline: String,
    val detail: String,
    /** Speed and time left, only while transferring. Dashes when unknown, never "infinity". */
    val speed: String?,
    val eta: String?,
    val bar: BarStyle,
    val isError: Boolean,
    /** Held by the network or by space, not by the user and not by an error. */
    val isWaiting: Boolean,
    val canPause: Boolean,
    val canResume: Boolean
)

private const val DASH = "—"

/** A transfer that has reported no progress for this long is called stalled. */
const val STALLED_AFTER_MS = 30_000L

fun DownloadEntity.toRowModel(
    conditions: RowConditions = RowConditions(),
    now: Long = System.currentTimeMillis()
): DownloadRowModel {
    val moving = status == DownloadStatus.RUNNING || status == DownloadStatus.RESOLVING || status == DownloadStatus.QUEUED
    val waiting = moving && (conditions.lowStorage || conditions.offline)
    val progress = progressFor(this)
    val hasSize = totalBytes > 0
    // Whole numbers from the byte counts, as the old row did, so 29% never shows as 28%.
    val percent = when {
        status == DownloadStatus.COMPLETED -> 100
        hasSize -> (bytesDownloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
        else -> progressPercent.coerceIn(0, 100)
    }
    val ofTotal = if (hasSize) "$percent% of ${formatBytes(totalBytes)}" else "$percent%"

    val isTorrent = source == com.downloadhub.app.data.model.DownloadSource.TORRENT
    // Moving on paper, but nothing has arrived for a while: say so instead of showing a bar
    // that looks alive. A torrent with no one sending is the usual cause.
    val stalled = status == DownloadStatus.RUNNING && !waiting && speedBytesPerSecond <= 0 && updatedAt > 0 && now - updatedAt >= STALLED_AFTER_MS
    val seeding = isTorrent && status == DownloadStatus.COMPLETED && seedingSinceEpochMillis > 0 && seedingStoppedAtEpochMillis == 0L
    val headline = when {
        waiting && conditions.lowStorage -> "Paused: not enough space"
        waiting -> "Waiting for network"
        stalled && isTorrent -> "Stalled · no one is sending"
        stalled -> "Stalled · still trying"
        status == DownloadStatus.RUNNING -> "Downloading"
        status == DownloadStatus.RESOLVING && isTorrent -> "Fetching file list"
        status == DownloadStatus.RESOLVING -> "Resolving"
        seeding -> "Finished · seeding"
        status == DownloadStatus.QUEUED -> "Queued"
        status == DownloadStatus.PAUSED -> "Paused"
        status == DownloadStatus.COMPLETED -> "Finished"
        else -> friendlyError(errorMessage).short
    }
    val detail = when (status) {
        DownloadStatus.COMPLETED -> if (hasSize) formatBytes(totalBytes) else ""
        DownloadStatus.QUEUED -> if (progress > 0f) ofTotal else if (hasSize) formatBytes(totalBytes) else ""
        DownloadStatus.RESOLVING -> ""
        else -> ofTotal
    }
    val transferring = status == DownloadStatus.RUNNING && !waiting && !stalled
    val bar = when {
        status == DownloadStatus.COMPLETED -> BarStyle.NONE
        status == DownloadStatus.RESOLVING && !waiting -> BarStyle.WAVY_INDETERMINATE
        transferring && !hasSize && progressPercent <= 0 -> BarStyle.WAVY_INDETERMINATE
        transferring -> BarStyle.WAVY
        percent > 0 -> BarStyle.FLAT
        else -> BarStyle.NONE
    }
    return DownloadRowModel(
        id = id,
        name = fileName,
        source = source,
        shape = shapeKindFor(status, waiting = waiting || stalled),
        progress = progress,
        percent = percent,
        headline = headline,
        detail = detail,
        speed = if (transferring) formatSpeed(speedBytesPerSecond) else null,
        eta = if (transferring) (if (speedBytesPerSecond > 0 && etaSeconds >= 0) formatEta(etaSeconds) else DASH) else null,
        bar = bar,
        isError = status == DownloadStatus.FAILED,
        isWaiting = waiting || stalled,
        canPause = status.canPause,
        canResume = status.canResume
    )
}

/**
 * What a screen reader says for the whole row: one sentence instead of five fragments.
 * Speeds are spoken out ("per second") because "MB/s" is read letter by letter.
 */
fun DownloadRowModel.spoken(): String = buildString {
    append(name).append(", ").append(headline)
    if (bar == BarStyle.WAVY || bar == BarStyle.FLAT) append(", ").append(percent).append(" percent")
    if (speed != null && speed != DASH) append(", ").append(speed.replace("/s", " per second"))
    if (eta != null && eta != DASH) append(", ").append(eta).append(" left")
}
