package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.OutlinedTextField
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
 *
 * [paneHeight] is the height of the pane and [onPaneHeightChange] resizes it. It was a
 * fixed 168 dp, and at that height the Content tab's own furniture - the count line, the
 * filter box, the column heading - took 148 of it, leaving room for exactly one row of
 * twelve. The tab said "12 of 12 files are being fetched" over a list showing one, which
 * reads as a bug in the torrent rather than a bug in the pane.
 */
@Composable
fun TorrentDetailPanel(
    item: DownloadItem?,
    tab: TorrentTab,
    onTab: (TorrentTab) -> Unit,
    paneHeight: Float,
    onPaneHeightChange: (Float) -> Unit,
    /** Sets one file's priority. Null where the pane cannot change it. */
    onFilePriority: ((Int, com.downloadhub.core.FilePriority) -> Unit)? = null,
    /** Bytes fetched per file, from the engine's last poll. */
    fileProgress: Map<Int, Long> = emptyMap(),

    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        PaneResizeHandle(height = paneHeight, onHeight = onPaneHeightChange)
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
            // Speed, right-aligned, because it is the number being watched. It is given a
            // fixed width and no more, because a row that overflows its pane clips the
            // right-hand end of the last thing in it, and that is the speed - the one
            // number on this strip anybody is watching.
            Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    if (item != null) {
                        DisplayFormat.bytes(item.speedBytesPerSecond) + "/s"
                    } else {
                        ""
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = AppTheme.Palette.muted
                )
            }
        }

        HorizontalDivider(color = AppTheme.Palette.outlineVariant)

        Box(
            Modifier
                .fillMaxWidth()
                .height(paneHeight.dp)
                .background(AppTheme.Palette.surface)
        ) {
            when {
                item == null -> PanelNote("Select a torrent to see its details.")
                item.source != DownloadSource.TORRENT ->
                    PanelNote("That is not a torrent, so it has none of these.")
                else -> TorrentTabContent(item, tab, onFilePriority, fileProgress)
            }
        }
    }
}

/**
 * The grab strip above the tab bar, for making the pane taller or shorter.
 *
 * Deltas rather than positions, and that is the whole reason this works where the old
 * hand-rolled frame did not. A pointer event's position is relative to the element that
 * received it, and this strip *moves* as it is dragged - so reading a position each frame
 * reads the pointer sitting still inside a strip that has moved to meet it, and the pane
 * never changes size. The screen position at the moment of the press is kept instead, and
 * every move is the distance from there. That needs nothing from the window manager and
 * cannot be thrown off by the strip moving.
 *
 * The target is 7 dp against a 1 dp line, for the same reason the column handles are 14:
 * a one-pixel drag target is a drag target nobody finds.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun PaneResizeHandle(height: Float, onHeight: (Float) -> Unit) {
    var pressedAt by remember { mutableStateOf(0L) }
    var heightAtPress by remember { mutableStateOf(height) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .onPointerEvent(PointerEventType.Press) { event ->
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                if (mouse != null && mouse.button == java.awt.event.MouseEvent.BUTTON1) {
                    pressedAt = mouse.yOnScreen.toLong()
                    heightAtPress = height
                    dragging = true
                    event.changes.forEach { it.consume() }
                }
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                if (!dragging) return@onPointerEvent
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent ?: return@onPointerEvent
                val movedDp = (pressedAt - mouse.yOnScreen).toFloat() / density
                onHeight((heightAtPress + movedDp).coerceIn(PANE_MIN_DP, PANE_MAX_DP))
                event.changes.forEach { it.consume() }
            }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (dragging) {
                    dragging = false
                    event.changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    if (dragging) AppTheme.Palette.accent else AppTheme.Palette.outlineVariant
                )
        )
    }
}

/** Short enough to be cramped and tall enough to hold a heading and two rows. */
const val PANE_MIN_DP = 110f

/** Past this it is the main list that has stopped being usable. */
const val PANE_MAX_DP = 900f

/** Where the pane starts, which is tall enough for a heading and about six rows. */
const val PANE_DEFAULT_DP = 300f

@Composable
private fun TorrentTabContent(
    item: DownloadItem,
    tab: TorrentTab,
    onFilePriority: ((Int, com.downloadhub.core.FilePriority) -> Unit)?,
    fileProgress: Map<Int, Long>
) {
    // The pane's own filter, so a twelve-file list can be narrowed to the one file being
    // looked for. It was a fixed empty string, which is why the filter box beside it did
    // nothing at all.
    var filter by remember(item.id) { mutableStateOf("") }

    /**
     * Which files are ticked in the Content tab.
     *
     * Not the pre-download selection: that was decided before this torrent started and
     * cannot change now. These ticks only say what a toolbar action applies to, which is
     * why the bar says "Set priority on 3" when three are ticked.
     */
    var ticked by remember(item.id) { mutableStateOf<Set<Int>>(emptySet()) }
    val label = AppTheme.Palette.muted
    val value = AppTheme.Palette.onSurface
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
            // The .torrent when there is one, and the saved file list when there is
            // not.
            //
            // A magnet has no .torrent file, and libtorrent4j cannot write one from
            // the metadata it fetches, so a torrent that arrived as a magnet - every
            // torrent found by searching - had no file list here at all even though
            // the pre-download dialog had just drawn one from the same metadata. The
            // list is saved as it arrives and read back here.
            val meta = item.torrentFilePath?.let(::File)
                ?.takeIf { it.isFile }
                ?.let { runCatching { com.downloadhub.core.TorrentParser.parse(it) }.getOrNull() }
                ?: com.downloadhub.core.TorrentMetainfoStore.read(AppPaths.home, item.id)
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
                Column(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                    // The count and the filter share one line.
                    //
                    // They were three lines: the count, then a gap, then the filter box,
                    // then the column heading. At the pane's old height of 168 dp that was
                    // 148 px of furniture over a list with room for one row, so a twelve
                    // file torrent said "12 of 12 files are being fetched" above a single
                    // line of it. The filter is narrow and the count short, so they fit
                    // together and the list gets the rest.
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            buildString {
                                append("${selected.size} of ${meta.files.size} files")
                                // Said only while some are ticked: a user who cannot see
                                // what a toolbar action is about to apply to has no way
                                // of telling whether they ticked what they meant to.
                                if (ticked.isNotEmpty()) append("  ·  ${ticked.size} ticked")
                            },
                            fontSize = 11.sp,
                            color = AppTheme.Palette.muted,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            singleLine = true,
                            placeholder = {
                                Text("Filter files", fontSize = 10.sp, color = AppTheme.Palette.faint)
                            },
                            modifier = Modifier.width(150.dp)
                        )
                    }
                    ContentFileList(
                        rows = contentRowsFor(meta, filter),
                        filter = filter,
                        onFilter = { filter = it },
                        // The ticks choose which files a toolbar priority applies to.
                        // They are not the pre-download selection and cannot change it -
                        // that was decided before this torrent started - so they are held
                        // here rather than written back.
                        selected = ticked,
                        onSelectionChange = { chosen -> ticked = chosen },
                        onSelectAll = { ticked = meta.files.map { it.index }.toSet() },
                        onSelectNone = { ticked = emptySet() },
                        downloadedBytes = fileProgress,
                        filePriorities = item.torrentFilePriorities
                            .mapValues { (_, ordinal) ->
                                com.downloadhub.core.FilePriority.fromOrdinal(ordinal)
                            },
                        onFilePriority = onFilePriority,
                        // Its own toolbar is suppressed: the filter is above, on the
                        // count's line, and a second one would be two filters each
                        // filtering.
                        chrome = false
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
