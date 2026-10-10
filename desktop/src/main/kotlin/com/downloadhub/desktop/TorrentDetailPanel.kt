package com.downloadhub.desktop

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.List
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.OutlinedTextField
import java.io.File

/** The panes along the bottom of the download view. */
enum class TorrentTab(val label: String) {
    /**
     * What the download is and how it is doing: size, speed, peers, ratio, where it is.
     *
     * The only tab there is for everything. A download that is not a torrent has no
     * tracker list, no peer list, no web seeds and no file list, so the tabs for those
     * had nothing behind them and sat on screen regardless - four buttons out of five
     * that could only ever answer "nothing yet", and on an ordinary link that is the
     * whole story the pane has to tell.
     */
    GENERAL("Details"),

    /** Every file in a torrent, with its own progress and priority. Torrents only. */
    CONTENT("Content"),

    /** The torrent's trackers and what each last said, as in qBittorrent. Torrents only. */
    TRACKERS("Trackers"),

    /** Who it is connected to and how fast each is going. Torrents only. */
    PEERS("Peers");

    companion object {
        fun fromName(name: String?): TorrentTab =
            values().firstOrNull { it.name == name } ?: GENERAL

        /**
         * The tabs there is anything behind, for this kind of download.
         *
         * A torrent has a file list, so it gets Content as well as Details. An ordinary
         * download and a YouTube video are one file - or, for a playlist, one queue row
         * per video, which the list above already shows - so Details is the whole of it.
         */
        fun forDownload(isTorrent: Boolean): List<TorrentTab> =
            if (isTorrent) values().toList() else listOf(GENERAL)

        /**
         * The tab to actually show, never one this download has no tab for.
         *
         * Ticking a torrent and then an ordinary download would otherwise leave the pane
         * showing a file list with no file list tab to highlight - a pane displaying
         * something with no way back out of it.
         */
        fun forDownload(isTorrent: Boolean, wanted: TorrentTab): TorrentTab =
            if (wanted in forDownload(isTorrent)) wanted else GENERAL
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
    /**
     * Collapsed, the pane is only its tab strip and the list keeps the room. A click on a
     * tab opens the pane on it; a click on the tab that is already open closes it again.
     */
    expanded: Boolean = true,
    onExpandedChange: (Boolean) -> Unit = {},
    /** Sets one file's priority. Null where the pane cannot change it. */
    onFilePriority: ((Int, com.downloadhub.core.FilePriority) -> Unit)? = null,
    /** Bytes fetched per file, from the engine's last poll. */
    fileProgress: Map<Int, Long> = emptyMap(),
    /** Reads the Trackers and Peers tabs; called off the UI thread while one is open. */
    trackersOf: (String) -> List<com.downloadhub.core.TrackerRow> = { emptyList() },
    peersOf: (String) -> List<com.downloadhub.core.PeerRow> = { emptyList() },
    onAddTrackers: (List<String>) -> Unit = {},

    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        if (expanded) {
            PaneResizeHandle(height = paneHeight, onHeight = onPaneHeightChange)
        } else {
            HorizontalDivider(color = AppTheme.Palette.outlineVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // Only the tabs there is something behind, for this download.
        //
        // They used to all five be drawn and greyed out for anything that was not a
        // torrent. A greyed tab is still a tab: it says there is something here, it takes
        // up the width, and four of the five could only ever answer "nothing yet".
        TorrentTab.forDownload(item?.isTorrent == true).forEach { entry ->
            TorrentTabButton(
                label = entry.label,
                selected = expanded && entry == tab,
                enabled = item != null,
                onClick = {
                    if (expanded && entry == tab) {
                        onExpandedChange(false)
                    } else {
                        onTab(entry)
                        onExpandedChange(true)
                    }
                }
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

        if (expanded) HorizontalDivider(color = AppTheme.Palette.outlineVariant)

        if (expanded) Box(
            Modifier
                .fillMaxWidth()
                .height(paneHeight.dp)
                .background(AppTheme.Palette.surface)
        ) {
            when {
                // The pane is drawn only for a selected row, so there is always
                // something to describe. The guard is here for the moment there is not,
                // not as a way of turning away a download that is not a torrent.
                item == null -> PanelNote("Select a download to see its details.")
                tab == TorrentTab.TRACKERS -> TrackersTab(item.id, trackersOf, onAddTrackers)
                tab == TorrentTab.PEERS -> PeersTab(item.id, peersOf)
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
            .height(10.dp)
            .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
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
        // A grip, so the strip reads as something to take hold of rather than a rule.
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(
                    if (dragging) AppTheme.Palette.accent else AppTheme.Palette.muted.copy(alpha = 0.6f),
                    RoundedCornerShape(2.dp)
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

    /**
     * The height the download list is guaranteed whatever the pane is doing.
     *
     * Two rows plus the divider between them, so a list that still has some room
     * shows what it holds. The pane is capped against this rather than the other
     * way round: a detail pane exists to say more about a download, not to replace
     * the list of them, and on a short window an uncapped pane took the lot.
     */
    const val LIST_MIN_DP = 260f

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
            // What this download actually is, and what is actually happening to it.
            //
            // This pane used to refuse to open for anything that was not a torrent - "That
            // is not a torrent, so it has none of these" - and that was the whole of the
            // Details tab for every ordinary link and every YouTube video. So ticking a
            // finished download told you it was not a torrent, which is the one thing you
            // had not asked.
            //
            // And the fields it did have were a torrent's: seeds, peers, a share ratio
            // and an upload rate, all of which are zero or meaningless for a file being
            // fetched from a web server. They are shown for a torrent and left out
            // otherwise, and what is left out is replaced with what that kind of download
            // actually has instead.
            Row {
                Field("Name", item.fileName, label, value, Modifier.weight(1f))
                Field(
                    "Status",
                    item.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    label, value, Modifier.weight(1f)
                )
            }
            Row {
                Field("Kind", kindOf(item), label, value, Modifier.weight(1f))
                Field("Category", item.category.name.lowercase().replaceFirstChar { it.uppercase() },
                    label, value, Modifier.weight(1f))
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
                // A row that is finished has no speed, and saying 0 B/s about it is
                // noise rather than information - the status above already says it is
                // done. So the time it took is put in the space instead.
                if (item.status == DownloadStatus.COMPLETED && item.completedAt > 0L) {
                    Field(
                        "Took",
                        elapsedBetween(item.createdAt, item.completedAt),
                        label, value, Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            // --- what a torrent has, and nothing else does -----------------------
            if (item.isTorrent) {
                Row {
                    Field("Upload speed", DisplayFormat.bytes(item.uploadRate) + "/s",
                        label, value, Modifier.weight(1f))
                    Field("Uploaded", DisplayFormat.bytes(item.uploadedBytes),
                        label, value, Modifier.weight(1f))
                }
                Row {
                    Field("Seeds", item.seeds.toString(), label, value, Modifier.weight(1f))
                    Field("Peers", item.peerCount.toString(), label, value, Modifier.weight(1f))
                }
                Field("Ratio", ratioOf(item), label, value)
                // Only while it is actually sharing, or has stopped. A torrent that was
                // never seeded has nothing to report and zero would read as a fact.
                if (item.seedingSinceEpochMillis > 0L) {
                    Field(
                        "Sharing since",
                        timeOf(item.seedingSinceEpochMillis) +
                            if (item.seedingStoppedAtEpochMillis > 0L) {
                                ", stopped " + timeOf(item.seedingStoppedAtEpochMillis)
                            } else {
                                ""
                            },
                        label, value
                    )
                }
                if (item.shareRatioLimit > 0.0 || item.seedTimeLimitMinutes > 0) {
                    Field(
                        "Stop sharing at",
                        listOfNotNull(
                            if (item.shareRatioLimit > 0.0) "ratio ${item.shareRatioLimit}" else null,
                            if (item.seedTimeLimitMinutes > 0) "${item.seedTimeLimitMinutes} min" else null
                        ).joinToString(" or "),
                        label, value
                    )
                }
            }

            // --- what a YouTube download has --------------------------------------
            if (item.source == DownloadSource.YOUTUBE) {
                // Blank for anything not chosen, because a row reading "Quality: null"
                // is a bug report rather than a detail.
                item.quality?.takeIf { it.isNotBlank() }?.let {
                    Field("Quality", it, label, value)
                }
                item.audioFormat?.takeIf { it.isNotBlank() }?.let {
                    Field("Audio", it, label, value)
                }
                if (item.playlist) {
                    Field("Part of a playlist", "yes - one row per video", label, value)
                }
            }

            // --- where it came from, and where it went ---------------------------
            Field("Added on", timeOf(item.createdAt), label, value)
            if (item.completedAt > 0L) {
                Field("Finished on", timeOf(item.completedAt), label, value)
            }
            // Where the finished file is, falling back through the three places a path
            // can be. A finished download whose Save to reads "(not chosen yet)" is
            // wrong in the way that matters: the file exists somewhere.
            Field(
                "Save to",
                item.location ?: item.outputPath ?: "(not chosen yet)",
                label, value
            )
            item.speedLimitBytesPerSecond.takeIf { it > 0L }?.let {
                Field("Speed limit", DisplayFormat.bytes(it) + "/s", label, value)
            }
            item.startAfterEpochMillis.takeIf { it > 0L }?.let {
                Field("Scheduled for", timeOf(it), label, value)
            }
            Field("From", item.url, label, value)
            val error = item.errorMessage
            if (!error.isNullOrBlank()) Field("Error", error, label, MaterialTheme.colorScheme.error)
        }

        // Drawn by TrackersTab and PeersTab, which TorrentDetailPanel picks before here.
        TorrentTab.TRACKERS, TorrentTab.PEERS -> Unit

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

    /**
     * What kind of download this is, in the words the rest of the app uses.
     *
     * The Details tab opens for every download now, and this is the first thing worth
     * knowing about one: a magnet, a video pulled off YouTube and a zip from a web
     * server are fetched in entirely different ways, and the fields below differ to
     * match.
     */
    private fun kindOf(item: DownloadItem): String = when (item.source) {
        DownloadSource.TORRENT -> "Torrent"
        DownloadSource.YOUTUBE -> "YouTube"
        DownloadSource.HTTP -> if (item.url.startsWith("magnet:", ignoreCase = true)) {
            "Magnet"
        } else {
            "Link"
        }
    }

    /**
     * How long a download took, as a phrase rather than two timestamps to subtract.
     *
     * "Added 20:14:03, finished 20:41:55" is work for the reader; "27m 41s" is the
     * answer. Shown against a finished download, where a speed of 0 B/s has nothing
     * left to say.
     */
    private fun elapsedBetween(fromMillis: Long, toMillis: Long): String {
        if (fromMillis <= 0L || toMillis <= fromMillis) return "-"
        val seconds = (toMillis - fromMillis) / 1000L
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds % 60L}s"
            else -> "${seconds}s"
        }
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
        // Its own width only: it shares the strip with the message and the active count,
        // and filling the strip left those no room at all.
        modifier = modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The whole queue, not just the torrents.
        //
        // It counted torrents and only appeared on the Torrents tab, so on every other
        // tab there was no indication of anything at all - a strip that vanishes with a
        // tab is a strip nobody learns to read.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Lucide.List,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = muted
            )
            Text(" ${items.size}", fontSize = 10.sp, color = muted, maxLines = 1, softWrap = false)
        }
        // Plain readouts: the speed's LCD and graph live on the instrument panel above the
        // list, and showing them twice was clutter.
        Text("↓ " + DisplayFormat.bytes(strip.downloadRate) + "/s", fontSize = 11.sp, fontFamily = Mono, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
        Text("↑ " + DisplayFormat.bytes(strip.uploadRate) + "/s", fontSize = 11.sp, fontFamily = Mono, color = muted, maxLines = 1, softWrap = false)
        Text("SEEDS " + strip.seeds, fontSize = 11.sp, fontFamily = Mono, color = muted, maxLines = 1, softWrap = false)
        Text("PEERS " + strip.peers, fontSize = 11.sp, fontFamily = Mono, color = muted, maxLines = 1, softWrap = false)
    }
}
