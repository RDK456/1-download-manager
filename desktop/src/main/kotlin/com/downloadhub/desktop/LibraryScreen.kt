package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadColumn
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadLibrary
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.LibraryCategory
import com.downloadhub.core.LibraryGroup
import com.downloadhub.core.LibraryQuery
import com.downloadhub.core.LibrarySort
import com.downloadhub.core.SortDirection
import java.io.File
import kotlin.math.abs

/**
 * The Windows library window.
 *
 * Laid out the way a desktop download manager should be: a fixed category rail on
 * the left, a toolbar of icon-and-label actions, a sortable column table in the
 * middle and a live status bar along the bottom. All of the filtering, sorting and
 * counting comes from :core, so the phone and the desktop cannot disagree.
 */
@Composable
fun LibraryScreen(
    state: DesktopUiState,
    actions: DesktopActions,
    onOpenAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit
) {
    var category by remember { mutableStateOf(LibraryCategory.ALL) }
    var group by remember { mutableStateOf(LibraryGroup.ALL) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // Set when Delete is pressed, so the dialog can ask what should happen to the
    // files rather than the app assuming.
    var deleting by remember { mutableStateOf(emptySet<String>()) }

    val all = state.items.map { it.toCoreItem() }
    val query = LibraryQuery(
        category = category,
        group = group,
        search = search,
        torrentsOnly = state.torrentsTab,
        sort = sort
    )
    val visible = DownloadLibrary.visible(all, query)

    Surface(modifier = Modifier.fillMaxSize(), color = state.palette.background) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(
                version = state.appVersion,
                onCheckUpdates = actions.checkForUpdates,
                onOpenAdd = onOpenAdd,
                onOpenSettings = onOpenSettings,
                onPauseAll = actions.pauseAll,
                onResumeAll = actions.resumeAll,
                onQuit = onQuit,
                extensionRoot = java.io.File(state.extensionPath),
                pairingToken = state.settings.captureToken
            )
            Row(Modifier.weight(1f)) {
                CategoryRail(
                    items = all,
                    category = category,
                    group = group,
                    torrentsOnly = state.torrentsTab,
                    onCategory = { selected = emptySet(); category = it; group = LibraryGroup.ALL },
                    onGroup = { selected = emptySet(); group = it; category = LibraryCategory.ALL },
                    onToggleTorrents = { actions.setTorrentsTab(!state.torrentsTab) }
                )
                VerticalRule()
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    LibraryToolbar(
                        hasSelection = selected.isNotEmpty(),
                        activeCount = DownloadLibrary.activeCount(all),
                        onNew = onOpenAdd,
                        onResume = { selected.forEach { actions.resume(it) } },
                        onPause = { selected.forEach { actions.pause(it) } },
                        onStartQueue = actions.resumeAll,
                        onStopQueue = actions.pauseAll,
                        onStopAll = actions.pauseAll,
                        onDelete = { deleting = selected; selected = emptySet() },
                        onOpenFolder = actions.openDownloadFolder,
                        onSettings = onOpenSettings,
                        search = search,
                        onSearch = { search = it; selected = emptySet() }
                    )
                    ColumnHeader(sort, onSort = { sort = it })
                    HorizontalDivider(color = state.palette.outline.copy(alpha = 0.5f))
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Nothing here. Use New Download to add one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = state.palette.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            items(visible, key = { it.id }) { item ->
                                DownloadRow(
                                    item = item,
                                    palette = state.palette,
                                    checked = item.id in selected,
                                    onToggle = {
                                        selected = if (item.id in selected) {
                                            selected - item.id
                                        } else {
                                            selected + item.id
                                        }
                                    },
                                    onPause = { actions.pause(item.id) },
                                    onResume = { actions.resume(item.id) },
                                    onRetry = { actions.retry(item.id) },
                                    onOpen = { openOutput(item.location) }
                                )
                                HorizontalDivider(
                                    color = state.palette.outline.copy(alpha = 0.25f),
                                    thickness = 1.dp
                                )
                            }
                        }
                    }
                    StatusBar(state, all)
                }
            }
        }

        // Asking is the point. "Remove from the list" and "delete the file" are both
        // things people mean, and the second is not undoable, so the app does not
        // choose on the user's behalf.
        if (deleting.isNotEmpty()) {
            DeleteChoiceDialog(
                count = deleting.size,
                // A running download has no file on disk yet, so the question would
                // be meaningless for it.
                canDeleteFiles = state.items.any {
                    it.id in deleting && it.status == DownloadStatus.COMPLETED
                },
                onKeepFiles = {
                    deleting.forEach { actions.removeSelectingFiles(it, false) }
                    deleting = emptySet()
                },
                onDeleteFiles = {
                    deleting.forEach { actions.removeSelectingFiles(it, true) }
                    deleting = emptySet()
                },
                onDismiss = { deleting = emptySet() }
            )
        }
    }
}

/**
 * Asks what "delete" should mean.
 *
 * Two answers people actually want - tidy the list but keep the file, or take the file
 * too - plus the implicit third, cancelling, which leaving the dialog open provides.
 */
@Composable
private fun DeleteChoiceDialog(
    count: Int,
    canDeleteFiles: Boolean,
    onKeepFiles: () -> Unit,
    onDeleteFiles: () -> Unit,
    onDismiss: () -> Unit
) {
    val subject = if (count == 1) "this download" else "these $count downloads"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $subject?") },
        text = {
            Text(
                if (canDeleteFiles) {
                    "Remove $subject from the list, or remove " +
                        (if (count == 1) "it" else "them") + " and delete the " +
                        (if (count == 1) "file" else "files") + " from disk?"
                } else {
                    "Remove $subject from the list? Nothing has been written to disk yet."
                }
            )
        },
        confirmButton = {
            if (canDeleteFiles) {
                androidx.compose.material3.TextButton(onClick = onDeleteFiles) {
                    Text("Delete " + if (count == 1) "file" else "files")
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onKeepFiles) {
                Text("Just remove from list")
            }
        }
    )
}

@Composable
private fun VerticalRule() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Color(0xFF2C3639))
    )
}

@Composable
private fun CategoryRail(
    items: List<DownloadItem>,
    category: LibraryCategory,
    group: LibraryGroup,
    torrentsOnly: Boolean,
    onCategory: (LibraryCategory) -> Unit,
    onGroup: (LibraryGroup) -> Unit,
    onToggleTorrents: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(230.dp)
            .fillMaxHeight()
            .background(Color(0xFF161C1F))
            .padding(vertical = 6.dp)
    ) {
        RailRow("All", if (torrentsOnly) 0 else items.size, category == LibraryCategory.ALL && group == LibraryGroup.ALL && !torrentsOnly) {
            onCategory(LibraryCategory.ALL)
        }
        LibraryCategory.entries.filter { it != LibraryCategory.ALL }.forEach { entry ->
            RailRow(
                entry.label,
                DownloadLibrary.countFor(items, entry),
                category == entry,
                icon = LibraryCategoryIcons.of(entry)
            ) {
                onCategory(entry)
            }
        }
        Spacer(Modifier.height(10.dp))
        GroupHeader("Finished")
        RailRow(
            LibraryGroup.FINISHED.label,
            DownloadLibrary.countFor(items, LibraryGroup.FINISHED),
            group == LibraryGroup.FINISHED
        ) { onGroup(LibraryGroup.FINISHED) }
        GroupHeader("Unfinished")
        RailRow(
            LibraryGroup.UNFINISHED.label,
            DownloadLibrary.countFor(items, LibraryGroup.UNFINISHED),
            group == LibraryGroup.UNFINISHED
        ) { onGroup(LibraryGroup.UNFINISHED) }
        Spacer(Modifier.height(10.dp))
        GroupHeader("Queues")
        RailRow("Main", if (torrentsOnly) 0 else items.size, !torrentsOnly && group == LibraryGroup.QUEUES) {
            onGroup(LibraryGroup.QUEUES)
        }
        RailRow("Torrents", if (torrentsOnly) items.size else 0, torrentsOnly) {
            onToggleTorrents()
        }
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF6E7B7D),
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun RailRow(
    label: String,
    count: Int,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF22302E) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            // Every category gets one, so the rail can be picked out at a glance
            // instead of read. All and the groups below have none, so they keep the
            // original indent and the two kinds of row stay distinguishable.
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color(0xFF34D399) else Color(0xFF7E8C8E),
                modifier = Modifier
                    .size(15.dp)
                    .padding(end = 0.dp)
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color(0xFF34D399) else Color(0xFFD6DEDF),
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count",
            fontSize = 11.sp,
            color = if (selected) Color(0xFF34D399) else Color(0xFF6E7B7D)
        )
    }
}

@Composable
private fun LibraryToolbar(
    hasSelection: Boolean,
    activeCount: Int,
    onNew: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onStartQueue: () -> Unit,
    onStopQueue: () -> Unit,
    onStopAll: () -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onSettings: () -> Unit,
    search: String,
    onSearch: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A2124))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ToolbarButton("New Download", Icons.Default.Add, highlighted = true, onClick = onNew)
        ToolbarButton("Resume", Icons.Default.PlayArrow, enabled = hasSelection, onClick = onResume)
        ToolbarButton("Pause", DlmIcons.Pause, enabled = hasSelection, onClick = onPause)
        ToolbarButton("Start Queue", Icons.Default.PlayArrow, enabled = activeCount > 0, onClick = onStartQueue)
        ToolbarButton("Stop Queue", DlmIcons.Stop, enabled = activeCount > 0, onClick = onStopQueue)
        ToolbarButton("Stop All", DlmIcons.Stop, enabled = activeCount > 0, onClick = onStopAll)
        ToolbarButton("Delete", Icons.Default.Delete, enabled = hasSelection, onClick = onDelete)
      // Sits next to the search box rather than with the transfer actions: it is about
      // the destination, not about the queue. Kept short because the toolbar is
      // already at its limit and the folder icon carries most of the meaning.
      ToolbarButton("Downloads", DlmIcons.FolderOpen, onClick = onOpenFolder)
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            singleLine = true,
            placeholder = { Text("Search in the list", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(15.dp)) },
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(260.dp)
        )
        ToolbarButton("Settings", Icons.Default.Settings, onClick = onSettings)
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> Color(0xFF4A5759)
        highlighted -> Color(0xFF0B1A14)
        else -> Color(0xFFB4C0C2)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    if (highlighted && enabled) Color(0xFF34D399) else Color.Transparent,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = tint)
        }
        Text(
            label,
            fontSize = 10.sp,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ColumnHeader(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161C1F))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(26.dp))
        ColumnHeaderCell("Name", 320.dp, sort, DownloadColumn.NAME, onSort)
        ColumnHeaderCell("Size", 90.dp, sort, DownloadColumn.SIZE, onSort)
        ColumnHeaderCell("Status", 110.dp, sort, DownloadColumn.STATUS, onSort)
        ColumnHeaderCell("Speed", 95.dp, sort, DownloadColumn.SPEED, onSort)
        ColumnHeaderCell("Time Left", 90.dp, sort, DownloadColumn.TIME_LEFT, onSort)
        ColumnHeaderCell("Date Added", 110.dp, sort, DownloadColumn.DATE_ADDED, onSort)
    }
}

@Composable
private fun ColumnHeaderCell(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    sort: LibrarySort,
    column: DownloadColumn,
    onSort: (LibrarySort) -> Unit
) {
    val active = sort.column == column
    Row(
        modifier = Modifier
            .width(width)
            .clickable {
                onSort(
                    if (active) sort.toggled() else LibrarySort(column, SortDirection.ASCENDING)
                )
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Color(0xFF34D399) else Color(0xFF8A9799)
        )
        if (active) {
            Icon(
                if (sort.direction == SortDirection.ASCENDING) DlmIcons.ArrowUpward
                else DlmIcons.ArrowDownward,
                null,
                Modifier.size(11.dp),
                tint = Color(0xFF34D399)
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    palette: androidx.compose.material3.ColorScheme,
    checked: Boolean,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit
) {
    val running = item.status == DownloadStatus.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) Color(0xFF1C2A28) else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(26.dp)
                .height(16.dp)
                .background(
                    if (checked) Color(0xFF34D399) else Color(0xFF1A2124),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Default.Check, null, Modifier.size(11.dp), tint = Color(0xFF0B1A14))
        }

        Column(Modifier.width(320.dp).padding(end = 6.dp)) {
            Text(
                item.fileName,
                fontSize = 12.sp,
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (running) {
                Spacer(Modifier.height(3.dp))
                LinearProgressIndicator(
                    progress = { item.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF34D399),
                    trackColor = Color(0xFF222C2F)
                )
            } else {
                Text(
                    item.category.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontSize = 10.sp,
                    color = palette.onSurfaceVariant
                )
            }
        }

        Cell(DisplayFormat.bytes(item.totalBytes), 90.dp, palette)
        Cell(DisplayFormat.status(item), 110.dp, palette, colour = statusColour(item.status, palette))
        Cell(DisplayFormat.speed(item.speedBytesPerSecond), 95.dp, palette)
        Cell(DisplayFormat.timeLeft(DownloadLibrary.estimateSecondsLeft(item)), 90.dp, palette)
        Cell(DisplayFormat.timeAgo(item.createdAt), 110.dp, palette)

        when (item.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.RESOLVING ->
                IconButton(16.dp, DlmIcons.Pause, "Pause", onPause)

            DownloadStatus.PAUSED -> IconButton(16.dp, Icons.Default.PlayArrow, "Resume", onResume)
            DownloadStatus.FAILED -> IconButton(16.dp, Icons.Default.Refresh, "Retry", onRetry)
            DownloadStatus.COMPLETED -> IconButton(16.dp, DlmIcons.FolderOpen, "Open", onOpen)
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    palette: androidx.compose.material3.ColorScheme,
    colour: Color? = null
) {
    Text(
        text,
        fontSize = 11.sp,
        color = colour ?: palette.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 4.dp)
    )
}

@Composable
private fun IconButton(
    size: androidx.compose.ui.unit.Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Icon(icon, description, Modifier.size(size), tint = Color(0xFF8A9799))
}

private fun statusColour(status: DownloadStatus, palette: androidx.compose.material3.ColorScheme): Color =
    when (status) {
        DownloadStatus.COMPLETED -> Color(0xFF34D399)
        DownloadStatus.RUNNING -> Color(0xFF34D399)
        DownloadStatus.FAILED -> palette.error
        else -> palette.onSurfaceVariant
    }

@Composable
private fun StatusBar(state: DesktopUiState, all: List<DownloadItem>) {
    Surface(color = Color(0xFF161C1F)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "${DownloadLibrary.activeCount(all)} active",
                fontSize = 11.sp,
                color = Color(0xFF8A9799)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                DisplayFormat.speed(DownloadLibrary.totalSpeed(all)),
                fontSize = 11.sp,
                color = Color(0xFF8A9799)
            )
            if (state.message != null) {
                Spacer(Modifier.width(16.dp))
                Text(
                    state.message,
                    fontSize = 11.sp,
                    color = Color(0xFF34D399)
                )
            }
        }
    }
}

internal fun QueuedDownload.toCoreItem() = DownloadItem(
    id = id,
    url = url,
    fileName = fileName,
    source = source,
    status = status,
    category = category,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    errorMessage = errorMessage,
    location = location,
    quality = quality,
    audioFormat = audioFormat,
    playlist = playlist,
    createdAt = createdAt,
    torrentFilePath = torrentFilePath,
    torrentInfoHash = torrentInfoHash,
    outputPath = outputPath
)
