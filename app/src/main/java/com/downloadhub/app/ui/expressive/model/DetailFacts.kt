package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.label
import com.downloadhub.app.ui.formatBytes
import com.downloadhub.app.ui.formatEta
import com.downloadhub.app.ui.formatSpeed

/** One labelled figure in the details sheet. [wide] facts (paths) take a full row. */
data class Fact(val label: String, val value: String, val wide: Boolean = false, val mono: Boolean = true)

private fun sourceName(source: DownloadSource) = when (source) {
    DownloadSource.HTTP -> "Direct link"
    DownloadSource.YOUTUBE -> "YouTube"
    DownloadSource.TORRENT -> "Torrent"
}

/**
 * What the details sheet lists, in order. Only what is known is shown: an unknown size is
 * "Unknown", not a blank, and speed and time left appear only while bytes are moving.
 */
fun DownloadEntity.facts(): List<Fact> = buildList {
    val transferring = status == DownloadStatus.RUNNING
    if (transferring) {
        add(Fact("Speed", formatSpeed(speedBytesPerSecond)))
        add(Fact("Time left", if (speedBytesPerSecond > 0 && etaSeconds >= 0) formatEta(etaSeconds) else "—"))
    }
    add(Fact("Size", if (totalBytes > 0) formatBytes(totalBytes) else "Unknown"))
    add(Fact("Saved so far", formatBytes(bytesDownloaded)))
    add(Fact("Source", sourceName(source), mono = false))
    add(Fact("Category", category.label, mono = false))
    if (source == DownloadSource.YOUTUBE) {
        val q = MediaQuality.fromValue(quality)
        add(Fact("Quality", if (q.isAudioOnly) "Audio · ${AudioFormat.fromValue(audioFormat).label}" else q.label, mono = false))
    }
    mimeType?.takeIf { it.isNotBlank() }?.let { add(Fact("Format", it)) }
    outputPath?.takeIf { it.isNotBlank() }?.let { add(Fact("Saved to", it, wide = true)) }
    if (source == DownloadSource.HTTP) add(Fact("Link", url, wide = true))
}

/** Which main action the sheet leads with for a status. */
enum class PrimaryAction { PAUSE, RESUME, RETRY, OPEN }

fun primaryActionFor(status: DownloadStatus): PrimaryAction = when (status) {
    DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING -> PrimaryAction.PAUSE
    DownloadStatus.PAUSED -> PrimaryAction.RESUME
    DownloadStatus.FAILED -> PrimaryAction.RETRY
    DownloadStatus.COMPLETED -> PrimaryAction.OPEN
}
