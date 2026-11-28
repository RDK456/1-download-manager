package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.downloadhub.core.DownloadPriority
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One download's own settings.
 *
 * Modelled on the same two apps the rest of this came from: AB Download Manager lets a
 * single download carry its own speed limit and start time rather than only inheriting
 * the global ones, and qBittorrent orders its queue by a per-item priority and stops a
 * finished torrent sharing once it has given back enough.
 *
 * All of it was missing here, and the cost was concrete: one large file could take every
 * slot and saturate the connection with nothing the user could do about it, the queue
 * could not be reordered, and a finished torrent seeded for ever with no way to say
 * otherwise.
 */
@Composable
fun DownloadOptionsDialog(
    item: DownloadItem,
    globalSpeedLimitBytesPerSecond: Long,
    onSave: (priorityRank: Int, speedLimit: Long, startAfter: Long, ratio: Double, minutes: Int) -> Unit,
    onDismiss: () -> Unit,
    /** Headers, cookies and login for an ordinary download; not asked for otherwise. */
    onSaveRequest: (com.downloadhub.core.HttpRequestOptions) -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    /** Torrent panes, shown as tabs of this window; unused for an ordinary link. */
    trackersOf: (String) -> List<com.downloadhub.core.TrackerRow> = { emptyList() },
    peersOf: (String) -> List<com.downloadhub.core.PeerRow> = { emptyList() },
    onAddTrackers: (List<String>) -> Unit = {},
    onFilePriority: ((Int, com.downloadhub.core.FilePriority) -> Unit)? = null,
    fileProgress: Map<Int, Long> = emptyMap()
) {
    var tab by remember(item.id) { mutableStateOf(TorrentTab.GENERAL) }
    var speedTab by remember(item.id) { mutableStateOf(false) }
    var showDetails by remember(item.id) { mutableStateOf(true) }
    // Closes itself when the download finishes while it is open; one opened on a finished
    // download stays.
    val openedAs = remember(item.id) { item.status }
    androidx.compose.runtime.LaunchedEffect(item.status) {
        if (openedAs != com.downloadhub.core.DownloadStatus.COMPLETED &&
            item.status == com.downloadhub.core.DownloadStatus.COMPLETED
        ) {
            kotlinx.coroutines.delay(1_500)
            onDismiss()
        }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // Its own window, as IDM and AB Download Manager show a download, rather than a popup
    // inside the main one.
    val percent = if (item.totalBytes > 0) "${(item.bytesDownloaded * 100 / item.totalBytes).toInt()}% " else ""
    val dialogState = androidx.compose.ui.window.rememberDialogState(size = androidx.compose.ui.unit.DpSize(620.dp, FULL_HEIGHT))
    // With the details hidden there is only a progress bar: the window shrinks to fit it.
    val compact = !showDetails && !speedTab && tab == TorrentTab.GENERAL
    androidx.compose.ui.window.DialogWindow(
        onCloseRequest = onDismiss,
        title = "$percent${item.fileName}",
        state = dialogState,
        resizable = true
    ) {
        androidx.compose.runtime.LaunchedEffect(window) {
            window.iconImages = AppArtwork.windowIcons()
            ForegroundWindow.raise(window)
        }
        androidx.compose.runtime.LaunchedEffect(window, compact) {
            window.minimumSize = java.awt.Dimension(460, if (compact) COMPACT_HEIGHT.value.toInt() else 420)
            window.setSize(window.width, (if (compact) COMPACT_HEIGHT else FULL_HEIGHT).value.toInt())
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(
                    glassBackdrop(
                        androidx.compose.material3.MaterialTheme.colorScheme.surface,
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    )
                )
                .padding(16.dp)
        ) {
            Text(item.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            // Tabs, as IDM lays a download out: its status, its own limits, and for a
            // torrent its files, peers and trackers.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !speedTab && tab == TorrentTab.GENERAL, onClick = { speedTab = false; tab = TorrentTab.GENERAL }, label = { Text("Download status") })
                FilterChip(selected = speedTab, onClick = { speedTab = true }, label = { Text("Speed limiter") })
                if (item.isTorrent) {
                    listOf(TorrentTab.CONTENT, TorrentTab.PEERS, TorrentTab.TRACKERS).forEach { entry ->
                        FilterChip(
                            selected = !speedTab && tab == entry,
                            onClick = { speedTab = false; tab = entry },
                            label = { Text(if (entry == TorrentTab.CONTENT) "Files" else entry.label) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .then(if (compact) Modifier else Modifier.weight(1f))
                    .fillMaxWidth()
                    .liquidGlass(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                when {
                    speedTab -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        SpeedAndPriority(item, onSave)
                    }
                    tab == TorrentTab.TRACKERS -> TrackersTab(item.id, trackersOf, onAddTrackers)
                    tab == TorrentTab.PEERS -> PeersTab(item.id, peersOf)
                    tab == TorrentTab.CONTENT -> TorrentTabContent(item, tab, onFilePriority, fileProgress, null)
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        DownloadInfo(item, showDetails)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val status = item.status
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!speedTab && tab == TorrentTab.GENERAL) {
                    TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "<< Hide details" else "Show details >>") }
                }
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.url)) }) {
                    Text("Copy link")
                }
                if (item.location != null || item.outputPath != null) {
                    TextButton(onClick = onOpenFolder) { Text("Open folder") }
                }
                when (status) {
                    com.downloadhub.core.DownloadStatus.PAUSED, com.downloadhub.core.DownloadStatus.FAILED ->
                        androidx.compose.material3.OutlinedButton(onClick = onResume) { Text("Resume") }
                    com.downloadhub.core.DownloadStatus.COMPLETED ->
                        androidx.compose.material3.OutlinedButton(onClick = onOpenFile) { Text("Open file") }
                    else -> androidx.compose.material3.OutlinedButton(onClick = onPause) { Text("Pause") }
                }
                androidx.compose.material3.Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

private val FULL_HEIGHT = 560.dp
private val COMPACT_HEIGHT = 270.dp

/** Live status block, the way IDM and AB Download Manager show a download in progress. */
@Composable
private fun DownloadInfo(item: DownloadItem, showDetails: Boolean = true) {
    val total = item.totalBytes
    val fraction = if (total > 0) (item.bytesDownloaded.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
    val left = if (item.speedBytesPerSecond > 0 && total > item.bytesDownloaded) {
        (total - item.bytesDownloaded) / item.speedBytesPerSecond
    } else {
        null
    }
    if (total > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text("${(fraction * 100).toInt()}%", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
    }
    if (!showDetails) return
    SpeedGraph(item)
    SegmentMap(item)
    val lines = listOfNotNull(
        "Status" to DisplayFormat.status(item),
        "Size" to if (total > 0) DisplayFormat.bytes(total) else "Unknown",
        "Downloaded" to DisplayFormat.bytes(item.bytesDownloaded),
        (if (item.speedBytesPerSecond > 0) "Speed" to DisplayFormat.speed(item.speedBytesPerSecond) else null),
        (if (item.status == com.downloadhub.core.DownloadStatus.RUNNING) "Time left" to DisplayFormat.timeLeft(left) else null),
        "Resume" to when {
            item.source != com.downloadhub.core.DownloadSource.HTTP -> "Yes"
            item.etag != null || item.lastModified != null -> "Yes"
            else -> "Unknown"
        },
        runCatching { java.net.URI(item.url).host }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "Server" to it },
        "Save to" to (item.location ?: item.outputPath ?: "Default folder"),
        "Link" to item.url
    )
    Spacer(Modifier.height(8.dp))
    // Selectable, so the link can be copied out of the window.
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column {
            lines.forEach { (label, value) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(label, fontSize = 12.sp, color = AppTheme.Palette.muted, modifier = Modifier.width(92.dp))
                    Text(value, fontSize = 12.sp, maxLines = 3, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    ChecksumCheck(item)
}

/** This download's own speed cap, queue priority and start time (IDM's "Speed Limiter" tab). */
@Composable
private fun SpeedAndPriority(
    item: DownloadItem,
    onSave: (Int, Long, Long, Double, Int) -> Unit
) {
    var kb by remember(item.id) { mutableStateOf(if (item.speedLimitBytesPerSecond > 0) (item.speedLimitBytesPerSecond / 1024).toString() else "") }
    var rank by remember(item.id) { mutableStateOf(item.priority.rank) }
    var start by remember(item.id) { mutableStateOf(startAfterPresetFor(item.startAfterEpochMillis)) }
    val startedAs = remember(item.id) { startAfterPresetFor(item.startAfterEpochMillis) }
    OutlinedTextField(
        value = kb,
        onValueChange = { kb = it.filter(Char::isDigit) },
        singleLine = true,
        label = { Text("Limit this download to (KB/s, blank = no limit)") },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Section("Priority")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DownloadPriority.entries.forEach { level ->
            FilterChip(selected = rank == level.rank, onClick = { rank = level.rank }, label = { Text(shortLabel(level)) })
        }
    }
    Spacer(Modifier.height(12.dp))
    Section("Start")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StartPreset.entries.forEach { preset ->
            FilterChip(selected = start == preset, onClick = { start = preset }, label = { Text(preset.label, fontSize = 11.sp) })
        }
    }
    Spacer(Modifier.height(12.dp))
    androidx.compose.material3.Button(onClick = {
        val limit = (kb.toLongOrNull() ?: 0L) * 1024L
        val startAt = if (start == startedAs) item.startAfterEpochMillis else start.epochMillisFrom(System.currentTimeMillis())
        onSave(rank, limit, startAt, item.shareRatioLimit, item.seedTimeLimitMinutes)
    }) { Text("Save") }
}

/** The last minute of transfer rate, sampled each second while the download runs. */
@Composable
private fun SpeedGraph(item: DownloadItem) {
    if (item.status != com.downloadhub.core.DownloadStatus.RUNNING) return
    val samples = remember(item.id) { androidx.compose.runtime.mutableStateListOf<Long>() }
    val latest by androidx.compose.runtime.rememberUpdatedState(item.speedBytesPerSecond)
    androidx.compose.runtime.LaunchedEffect(item.id) {
        while (true) {
            samples.add(latest)
            if (samples.size > 60) samples.removeAt(0)
            kotlinx.coroutines.delay(1_000)
        }
    }
    if (samples.size < 2) return
    val line = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val peak = (samples.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val step = size.width / 59f
        val path = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { i, v ->
            val x = size.width - (samples.size - 1 - i) * step
            val y = size.height - (v / peak) * size.height * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
    }
    Spacer(Modifier.height(6.dp))
}

/** One bar per connection of a split download, filled as its range arrives. */
@Composable
private fun SegmentMap(item: DownloadItem) {
    if (item.source != com.downloadhub.core.DownloadSource.HTTP ||
        item.status != com.downloadhub.core.DownloadStatus.RUNNING
    ) return
    val state by androidx.compose.runtime.produceState<com.downloadhub.core.SegmentState?>(null, item.id) {
        val file = com.downloadhub.core.SegmentState.fileFor(AppPaths.workDir.resolve("part-${item.id}"))
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.downloadhub.core.SegmentState.read(file)
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val segments = state?.segments ?: return
    Text("Connections: ${segments.size}", fontSize = 11.sp, color = AppTheme.Palette.muted)
    Spacer(Modifier.height(3.dp))
    Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { segment ->
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (segment.done.toFloat() / segment.length).coerceIn(0f, 1f) },
                modifier = Modifier.weight(segment.length.toFloat()).height(10.dp)
            )
        }
    }
    // Per-connection table, as IDM lists each connection under its progress bar.
    Spacer(Modifier.height(4.dp))
    segments.forEachIndexed { index, segment ->
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text("${index + 1}", fontSize = 11.sp, color = AppTheme.Palette.muted, modifier = Modifier.width(28.dp))
            Text(DisplayFormat.bytes(segment.done), fontSize = 11.sp, modifier = Modifier.width(96.dp))
            Text(
                if (segment.isComplete) "Finished" else "Receiving data...",
                fontSize = 11.sp,
                color = AppTheme.Palette.muted
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** Compares a finished file with a hash the site published (MD5, SHA-1, SHA-256 or SHA-512). */
@Composable
private fun ChecksumCheck(item: DownloadItem) {
    val file = item.location?.let(::File)?.takeIf {
        item.status == com.downloadhub.core.DownloadStatus.COMPLETED && it.isFile
    } ?: return
    var expected by remember(item.id) { mutableStateOf("") }
    var result by remember(item.id) { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = expected,
        onValueChange = { expected = it; result = "" },
        singleLine = true,
        label = { Text("Verify checksum (paste MD5 / SHA hash)") },
        modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = expected.isNotBlank(),
            onClick = {
                result = "Checking..."
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { com.downloadhub.core.Checksum.matches(file, expected) } }
                    result = when {
                        ok.isFailure -> "Could not read the file"
                        ok.getOrNull() == null -> "Not a valid hash"
                        ok.getOrNull() == true -> "Match - the file is intact"
                        else -> "Mismatch - the file differs"
                    }
                }
            }
        ) { Text("Verify") }
        Text(result, fontSize = 11.sp)
    }
}

@Composable
private fun Section(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.Palette.muted,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/**
 * The words on a priority chip.
 *
 * Five full labels do not fit across the dialog at a readable size, so the two middles
 * are abbreviated. The tooltip in the row still spells them out.
 */
private fun shortLabel(level: DownloadPriority): String = when (level) {
    DownloadPriority.LOW -> "Low"
    DownloadPriority.BELOW_NORMAL -> "Below"
    DownloadPriority.NORMAL -> "Normal"
    DownloadPriority.ABOVE_NORMAL -> "Above"
    DownloadPriority.HIGH -> "High"
}

/**
 * When a download should start, as four choices rather than a date picker.
 *
 * A full date-and-time picker is a lot of UI for something used as "not right now". These
 * cover the reasons somebody defers a download, and each one is unambiguous.
 */
enum class StartPreset(val label: String) {
    ASAP("Straight away"),
    HOUR("In 1 hour"),
    SIX_HOURS("In 6 hours"),
    TOMORROW("Tomorrow morning");

    /** Epoch milliseconds for this preset, or 0 for "as soon as possible". */
    fun epochMillisFrom(nowMillis: Long): Long = when (this) {
        ASAP -> 0L
        HOUR -> nowMillis + 60L * 60L * 1000L
        SIX_HOURS -> nowMillis + 6L * 60L * 60L * 1000L
        // 08:00 local time tomorrow.
        TOMORROW -> {
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = nowMillis
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 8)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        }
    }
}

/**
 * Which preset a stored time corresponds to, so reopening the dialog shows what is set.
 *
 * Anything that does not match a preset - a time set by an older build, or one that has
 * already passed - shows as "straight away" rather than pretending to be one of the
 * options. Showing a time in the past as "in 1 hour" would be worse than not matching.
 */
fun startAfterPresetFor(epochMillis: Long): StartPreset {
    if (epochMillis <= 0L) return StartPreset.ASAP
    val now = now()
    val remaining = epochMillis - now
    return when {
        remaining <= 0L -> StartPreset.ASAP
        else -> StartPreset.entries.firstOrNull { preset ->
            preset != StartPreset.ASAP &&
                kotlin.math.abs(preset.epochMillisFrom(now) - epochMillis) < PRESET_TOLERANCE_MILLIS
        } ?: StartPreset.ASAP
    }
}

/** How close a stored time has to be to count as a preset. */
private const val PRESET_TOLERANCE_MILLIS = 60_000L

private fun describeStart(preset: StartPreset): String = when (preset) {
    StartPreset.ASAP -> "straight away"
    StartPreset.HOUR -> "in an hour"
    StartPreset.SIX_HOURS -> "in six hours"
    StartPreset.TOMORROW -> "tomorrow at 8:00"
}

private fun trimDouble(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

/** Overridable so the dialog can be reasoned about without depending on the clock. */
internal var now: () -> Long = { System.currentTimeMillis() }
