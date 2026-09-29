package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import java.io.File

/** The panes along the bottom of the torrent view. */
enum class TorrentTab(val label: String) {
    GENERAL("General"),
    TRACKERS("Trackers"),
    PEERS("Peers"),
    HTTP_SOURCES("HTTP Sources"),
    CONTENT("Content");

    companion object {
        fun fromName(name: String?): TorrentTab =
            values().firstOrNull { it.name == name } ?: GENERAL
    }
}

/** The one row of live numbers underneath the list. */
data class TorrentStatusStrip(
    val downloadRate: Long,
    val uploadRate: Long,
    val seeds: Int,
    val peers: Int,
    val torrentCount: Int
) {
    companion object {
        /**
         * Read from the queue, so the numbers cannot drift from what the list shows.
         *
         * Torrents only. Counting HTTP downloads in a torrent client's "seeds" line is
         * what makes such a number mean nothing.
         */
        fun from(items: List<DownloadItem>): TorrentStatusStrip {
            val torrents = items.filter { it.source == DownloadSource.TORRENT }
            return TorrentStatusStrip(
                downloadRate = torrents.filter { it.status == DownloadStatus.RUNNING }
                    .sumOf { it.speedBytesPerSecond },
                uploadRate = torrents.sumOf { it.uploadRate },
                seeds = torrents.sumOf { it.seeds },
                peers = torrents.sumOf { it.peerCount },
                torrentCount = torrents.size
            )
        }
    }
}

/**
 * The tab bar and the pane under it, shown on the Torrents tab.
 *
 * Only on that tab: putting it on the main list too would be two thirds of the window given
 * over to one download, on a screen meant for the list of all of them.
 *
 * What is here is what this app can actually show. The trackers, peers and HTTP-sources
 * panes say they have no data rather than showing zeroes: a pane of zeros reads as "nothing
 * is happening", which is a different claim from "this app does not collect it".
 */
@Composable
fun TorrentDetailPanel(
    item: DownloadItem?,
    tab: TorrentTab,
    onTab: (TorrentTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TorrentTab.entries.forEach { entry ->
                TorrentTabButton(
                    label = entry.label,
                    selected = entry == tab,
                    // Greyed out rather than hidden for something that is not a torrent: a
                    // tab that vanishes changes the pane's shape depending on the
                    // selection, which is disorienting in a way a greyed-out tab is not.
                    enabled = item != null && item.source == DownloadSource.TORRENT,
                    onClick = { onTab(entry) }
                )
            }
            Spacer(Modifier.weight(1f))
            // Speed, right-aligned, because it is the number being watched.
            Text(
                if (item != null) DisplayFormat.bytes(item.speedBytesPerSecond) + "/s" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(
            Modifier
                .fillMaxWidth()
                .height(168.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            when {
                item == null -> PanelNote("Select a torrent to see its details.")
                item.source != DownloadSource.TORRENT ->
                    PanelNote("That is not a torrent, so it has none of these.")
                else -> TorrentTabContent(item, tab)
            }
        }
    }
}

@Composable
private fun TorrentTabContent(item: DownloadItem, tab: TorrentTab) {
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val value = MaterialTheme.colorScheme.onSurface
    when (tab) {
        TorrentTab.GENERAL -> Column(Modifier.padding(10.dp)) {
            Row {
                Field("Name", item.fileName, label, value, Modifier.weight(1f))
                Field(
                    "Status",
                    item.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    label, value, Modifier.weight(1f)
                )
            }
            Field("Size", DisplayFormat.bytes(item.totalBytes), label, value)
            Field(
                "Downloaded",
                DisplayFormat.bytes(item.bytesDownloaded) + " (" + item.progressPercent + "%)",
                label, value
            )
            Row {
                Field(
                    "Download speed",
                    DisplayFormat.bytes(item.speedBytesPerSecond) + "/s",
                    label, value, Modifier.weight(1f)
                )
                Field("Upload speed", DisplayFormat.bytes(item.uploadRate) + "/s", label, value, Modifier.weight(1f))
            }
            Row {
                Field("Seeds", item.seeds.toString(), label, value, Modifier.weight(1f))
                Field("Peers", item.peerCount.toString(), label, value, Modifier.weight(1f))
            }
            Field("Ratio", ratioOf(item), label, value)
            Field("Added on", timeOf(item.createdAt), label, value)
            Field("Save to", item.outputPath ?: "(not chosen yet)", label, value)
            val error = item.errorMessage
            if (!error.isNullOrBlank()) Field("Error", error, label, MaterialTheme.colorScheme.error)
        }

        TorrentTab.TRACKERS -> PanelNote(
            "This app does not collect the tracker list yet.\n\n" +
                "A torrent's trackers come out of its .torrent file, and reading them is a " +
                "separate job from downloading."
        )

        TorrentTab.PEERS -> PanelNote(
            "This app does not collect the peer list yet.\n\n" +
                "${item.seeds} seeds and ${item.peerCount} peers are connected, but not who " +
                "they are."
        )

        TorrentTab.HTTP_SOURCES -> PanelNote(
            "Nothing yet.\n\nWeb seed addresses come from a torrent's url-list, and are only " +
                "used when the swarm cannot supply a file by itself."
        )

        TorrentTab.CONTENT -> {
            // Re-read from the .torrent rather than keeping the whole metainfo in every
            // queue row. The file is already on disk - it is copied there when the torrent
            // is added - and a nine-thousand-file metainfo in a JSON queue is a queue that
            // is slow to read for a list the user only opens occasionally.
            val meta = item.torrentFilePath?.let(::File)
                ?.takeIf { it.isFile }
                ?.let { runCatching { com.downloadhub.core.TorrentParser.parse(it) }.getOrNull() }
            if (meta == null || meta.files.isEmpty()) {
                PanelNote(
                    "The file list is not available for this one.\n\nA magnet only gets a " +
                        "file list once it has connected, and a torrent whose .torrent file " +
                        "is gone cannot show one either."
                )
            } else {
                val selected = meta.files
                    .filter { item.torrentSelectedFiles.isEmpty() || it.index in item.torrentSelectedFiles }
                    .map { it.index }
                    .toSet()
                Column(Modifier.padding(6.dp)) {
                    Text(
                        "${selected.size} of ${meta.files.size} files are being fetched",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    ContentFileList(
                        rows = contentRowsFor(meta, ""),
                        filter = "",
                        onFilter = {},
                        selected = selected,
                        onSelectionChange = { },
                        onSelectAll = {},
                        onSelectNone = {},
                        // Read-only: this reports what was chosen in the pre-download
                        // dialog, it is not an editor. A tick box that unticks itself and
                        // changes nothing is worse than a plain tick.
                        readOnly = true
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    text: String,
    labelColour: androidx.compose.ui.graphics.Color,
    valueColour: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    Row(modifier.padding(vertical = 1.dp)) {
        Text(label, fontSize = 10.sp, color = labelColour, modifier = Modifier.width(110.dp))
        Text(text, fontSize = 10.sp, color = valueColour, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PanelNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(14.dp)) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TorrentTabButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .padding(top = 4.dp, end = 4.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(4.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                selected -> accent
                enabled -> muted
                else -> muted.copy(alpha = 0.45f)
            }
        )
    }
}

/** Share ratio, or a dash when nothing has been shared yet. */
private fun ratioOf(item: DownloadItem): String = if (item.bytesDownloaded > 0) {
    String.format("%.2f", item.uploadedBytes.toDouble() / item.bytesDownloaded)
} else {
    "-"
}

private fun timeOf(epochMillis: Long): String = if (epochMillis <= 0L) {
    "-"
} else {
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime().toString().replace('T', ' ')
}

/** The strip below everything: totals, and how many torrents are running. */
@Composable
fun TorrentStatusBar(items: List<DownloadItem>, modifier: Modifier = Modifier) {
    val strip = TorrentStatusStrip.from(items)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${strip.torrentCount} torrents", fontSize = 10.sp, color = muted)
        Text(
            "↓ " + DisplayFormat.bytes(strip.downloadRate) + "/s",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text("↑ " + DisplayFormat.bytes(strip.uploadRate) + "/s", fontSize = 10.sp, color = muted)
        Text("Seeds " + strip.seeds, fontSize = 10.sp, color = muted)
        Text("Peers " + strip.peers, fontSize = 10.sp, color = muted)
    }
}
