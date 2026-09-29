package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadColumn
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadLibrary
import java.awt.event.MouseEvent
import com.downloadhub.core.DownloadPriority
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
 *
 * The layout reads the window size so it can give things up in a defined order as the
 * window narrows, rather than clipping the right-hand end off.
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
    /** Which download has its options dialog open, if any. */
    var optionsFor by remember { mutableStateOf<String?>(null) }
    /** Which download has its right-click menu open, if any. */
    var contextFor by remember { mutableStateOf<String?>(null) }
    /** Which download is being renamed. */
    var renaming by remember { mutableStateOf<String?>(null) }
    /** Which download is being given a different folder. */
    var relocating by remember { mutableStateOf<String?>(null) }
    /** Which bottom pane is open on the torrents tab. */
    var detailTab by remember { mutableStateOf(TorrentTab.GENERAL) }

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
            // Sized from the window rather than from fixed widths. The table used to
            // need 1071 dp before it stopped fitting - 230 of sidebar plus 841 of
            // columns - and the toolbar 982, so a window narrower than the one the app
            // opens at lost its right-hand columns and its last three buttons.
            //
            // The sidebar and the table and the toolbar each decide separately, because
            // they give things up in different orders: the sidebar narrows first, the
            // table drops columns from the right, and the toolbar drops its search box
            // before its captions - and never the buttons themselves.
            BoxWithConstraints(Modifier.weight(1f)) {
                val sidebar = sidebarWidthFor(maxWidth.value)
                // What is left for everything beside the sidebar and its rule.
                val contentDp = maxWidth.value - sidebar.value - 1f
                val table = tableLayoutFor(contentDp)
                val toolbar = toolbarLayoutFor(contentDp)
                Row(Modifier.fillMaxSize()) {
                    CategoryRail(
                        items = all,
                        category = category,
                        group = group,
                        torrentsOnly = state.torrentsTab,
                        width = sidebar,
                        compact = table.narrowSidebar,
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
                            layout = toolbar,
                            search = search,
                            onSearch = { search = it; selected = emptySet() }
                        )
                        ColumnHeader(sort, onSort = { sort = it }, layout = table)
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
                                        layout = table,
                                        checked = item.id in selected,
                                        onToggle = {
                                            // Selecting a row is what the bottom pane
                                            // follows, so it shows the download that was
                                            // last touched rather than an arbitrary one.
                                            selected = if (item.id in selected) {
                                                selected - item.id
                                            } else {
                                                selected + item.id
                                            }
                                        },
                                        onPause = { actions.pause(item.id) },
                                        onResume = { actions.resume(item.id) },
                                        onRetry = { actions.retry(item.id) },
                                        onOpen = { actions.revealDownload(item.location) },
                                        onOptions = { optionsFor = item.id },
                                        onContext = {
                                            contextFor = item.id
                                            selected = setOf(item.id)
                                        }
                                    )
                                    HorizontalDivider(
                                        color = state.palette.outline.copy(alpha = 0.25f),
                                        thickness = 1.dp
                                    )
                                }
                        }
                    }

                    // Only on the Torrents tab: on the main list this would be two
                    // thirds of the window given over to one download.
                    if (state.torrentsTab) {
                        TorrentDetailPanel(
                            item = all.firstOrNull { it.id in selected }
                                ?: all.firstOrNull { it.source == com.downloadhub.core.DownloadSource.TORRENT },
                            tab = detailTab,
                            onTab = { detailTab = it }
                        )
                        TorrentStatusBar(all)
                    }

                    StatusBar(state, all)
                }
                }
            }
        }

        // Asking is the point. "Remove from the list" and "delete the file" are both
        // things people mean, and the second is not undoable, so the app does not
        // choose on the user's behalf.
        // One download's own settings. Kept inside the window like every other dialog:
        // a dialog composed outside it is what produced "Failed to launch JVM" on the
        // first button press.
        val optionsItem = optionsFor?.let { id -> all.firstOrNull { it.id == id } }
        if (optionsItem != null) {
            DownloadOptionsDialog(
                item = optionsItem,
                globalSpeedLimitBytesPerSecond = state.settings.speedLimitBytesPerSecond,
                onSave = { rank, speed, startAfter, ratio, minutes ->
                    actions.setItemOptions(optionsItem.id, rank, speed, startAfter, ratio, minutes)
                    optionsFor = null
                },
                onDismiss = { optionsFor = null }
            )
        }

        if (deleting.isNotEmpty()) {
            // Anything with bytes on disk - a finished file or a half-fetched partial - has
            // something the tick box can be about. Only a download that has not written
            // anything yet does not.
            val onDisk = state.items.filter {
                it.id in deleting &&
                    (it.status == DownloadStatus.COMPLETED || it.bytesDownloaded > 0L)
            }
            DeleteChoiceDialog(
                count = deleting.size,
                canDeleteFiles = onDisk.isNotEmpty(),
                // One name when there is a single item: "Remove 'ubuntu.iso'?" says far
                // more than "Remove this download?", and this is the one dialog where
                // naming it prevents removing the wrong row.
                subject = if (deleting.size == 1) {
                    "'" + (onDisk.firstOrNull()?.fileName
                        ?: state.items.firstOrNull { it.id in deleting }?.fileName
                        ?: "it") + "'"
                } else {
                    "these ${deleting.size} downloads"
                },
                // Starts on whatever the user chose in settings, so the common case needs
                // no thought and the unusual one is one tick away.
                deleteFilesDefault = state.settings.deleteCacheWhenRemoved,
                onConfirm = { deleteFiles ->
                    deleting.forEach { actions.removeSelectingFiles(it, deleteFiles) }
                    deleting = emptySet()
                },
                onDismiss = { deleting = emptySet() }
            )
        }

        // The right-click menu, the rename box and the relocation box. All three look
        // their item up in `all` rather than holding a copy, so a row that has since been
        // removed closes its dialog instead of acting on a stale snapshot of itself.
        all.firstOrNull { it.id == contextFor }?.let { item ->
            DownloadContextMenu(
                item = item,
                hasContentFiles = item.location != null || item.bytesDownloaded > 0L,
                onAction = { action ->
                    contextFor = null
                    when (action) {
                        ContextAction.Pause -> actions.pause(item.id)
                        ContextAction.Resume -> actions.resume(item.id)
                        ContextAction.ForceStart -> actions.retry(item.id)
                        ContextAction.Options -> optionsFor = item.id
                        ContextAction.SetLocation -> relocating = item.id
                        ContextAction.Rename -> renaming = item.id
                        ContextAction.OpenFolder -> actions.revealDownload(item.location)
                        // The clipboard is the one part of this that can fail silently,
                        // and a menu item that does nothing is worse than a message.
                        ContextAction.CopyMagnet -> actions.copyToClipboard(magnetLinkFor(item))
                        ContextAction.ExportTorrent -> actions.exportTorrent(item.id)
                        // Always offered, never enabled: see ContextAction.
                        ContextAction.AutomaticManagement -> Unit
                        ContextAction.Remove -> {
                            deleting = setOf(item.id)
                            contextFor = null
                        }
                    }
                },
                onDismiss = { contextFor = null }
            )
        }

        all.firstOrNull { it.id == renaming }?.let { item ->
            RenameDownloadDialog(
                currentName = item.fileName,
                onConfirm = { name ->
                    actions.renameDownload(item.id, name)
                    renaming = null
                },
                onDismiss = { renaming = null }
            )
        }

        all.firstOrNull { it.id == relocating }?.let { item ->
            SetLocationDialog(
                currentDirectory = item.outputPath ?: state.settings.downloadDir,
                onConfirm = { path ->
                    actions.setDownloadLocation(item.id, path)
                    relocating = null
                },
                onDismiss = { relocating = null }
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
/**
 * The remove confirmation.
 *
 * One button, not two. The earlier version asked "remove it, or remove it and delete the
 * files?" and answered with two buttons, which puts a destructive choice beside a safe one
 * and makes the safe one the unusual shape. Modelled on qBittorrent's instead: one
 * "Remove" button, and a tick box that says what else will happen.
 *
 * The tick box is the part that matters. Removing a download that never finished has two
 * reasonable outcomes - the half-fetched file is wanted again, or it is not - and guessing
 * wrong either strands a partial file nobody can account for, or throws away work the
 * user came back for. So it is asked, and it starts on the user's own default.
 */
@Composable
private fun DeleteChoiceDialog(
    count: Int,
    canDeleteFiles: Boolean,
    subject: String = if (count == 1) "this download" else "these $count downloads",
    deleteFilesDefault: Boolean = false,
    onConfirm: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var deleteFiles by remember(count, canDeleteFiles) { mutableStateOf(deleteFilesDefault) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Remove $subject?") },
        text = {
            Column {
                if (canDeleteFiles) {
                    // A folder, a file and a half-finished one are all different things to
                    // delete, so the label says which rather than saying "content files"
                    // whatever that happens to be.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TickBox(checked = deleteFiles, onChange = { deleteFiles = it })
                        Text(
                            "Also remove the content files",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (deleteFiles) {
                            "This deletes what is on disk. It cannot be undone."
                        } else {
                            "The download is removed from the list and what it has " +
                                "fetched so far is kept in the cache."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Remove $subject from the list? Nothing has been written to disk yet.",
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (canDeleteFiles) deleteFiles else false) }) {
                Text("Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
    /**
     * Sized from the window by the caller. A fixed 230 dp is a third of a 700 dp
     * window, which leaves nothing for the thing the window is for, so it narrows
     * before the table gives up any columns.
     */
    width: androidx.compose.ui.unit.Dp,
    /** Narrow: the labels no longer sit comfortably beside the counts. */
    compact: Boolean,
    onCategory: (LibraryCategory) -> Unit,
    onGroup: (LibraryGroup) -> Unit,
    onToggleTorrents: () -> Unit
) {
    // What the table is actually showing, so every number beside it is reachable.
    val scoped = DownloadLibrary.scopedFor(items, torrentsOnly)
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color(0xFF161C1F))
            .padding(vertical = 6.dp)
    ) {
        RailRow("All", scoped.size, category == LibraryCategory.ALL && group == LibraryGroup.ALL && !torrentsOnly, compact = compact) {
            onCategory(LibraryCategory.ALL)
        }
        LibraryCategory.entries.filter { it != LibraryCategory.ALL }.forEach { entry ->
            RailRow(
                entry.label,
                DownloadLibrary.countFor(scoped, entry),
                category == entry,
                icon = LibraryCategoryIcons.of(entry),
                compact = compact
            ) {
                onCategory(entry)
            }
        }
        Spacer(Modifier.height(10.dp))
        GroupHeader("Finished")
        RailRow(
            LibraryGroup.FINISHED.label,
            DownloadLibrary.countFor(scoped, LibraryGroup.FINISHED),
            group == LibraryGroup.FINISHED,
            compact = compact
        ) { onGroup(LibraryGroup.FINISHED) }
        GroupHeader("Unfinished")
        RailRow(
            LibraryGroup.UNFINISHED.label,
            DownloadLibrary.countFor(scoped, LibraryGroup.UNFINISHED),
            group == LibraryGroup.UNFINISHED,
            compact = compact
        ) { onGroup(LibraryGroup.UNFINISHED) }
        Spacer(Modifier.height(10.dp))
        GroupHeader("Queues")
        RailRow("Main", scoped.size, !torrentsOnly && group == LibraryGroup.QUEUES, compact = compact) {
            onGroup(LibraryGroup.QUEUES)
        }
        RailRow("Torrents", DownloadLibrary.torrentCount(items), torrentsOnly, compact = compact) {
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
    /** Narrow rail: tighten the indent so the label and count still both fit. */
    compact: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF22302E) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = if (compact) 7.dp else 14.dp,
                end = if (compact) 7.dp else 12.dp,
                top = 6.dp,
                bottom = 6.dp
            ),
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
    /**
     * How much of the toolbar fits, and how wide each button ended up. The captions go
     * before the search box, because the buttons are what the toolbar is for; the search
     * box then takes whatever is left rather than a fixed width, so it is never what
     * overflows.
     */
    layout: ToolbarLayout,
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
        val compact = layout.style == ToolbarStyle.COMPACT
        ToolbarButton("New Download", Icons.Default.Add, highlighted = true, onClick = onNew, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Resume", Icons.Default.PlayArrow, enabled = hasSelection, onClick = onResume, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Pause", DlmIcons.Pause, enabled = hasSelection, onClick = onPause, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Start Queue", Icons.Default.PlayArrow, enabled = activeCount > 0, onClick = onStartQueue, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Stop Queue", DlmIcons.Stop, enabled = activeCount > 0, onClick = onStopQueue, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Stop All", DlmIcons.Stop, enabled = activeCount > 0, onClick = onStopAll, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Delete", Icons.Default.Delete, enabled = hasSelection, onClick = onDelete, compact = compact, buttonWidth = layout.buttonDp)
        // Next to the search box rather than with the transfer actions: it is about the
        // destination, not about the queue.
        ToolbarButton("Downloads", DlmIcons.Folder, onClick = onOpenFolder, compact = compact, buttonWidth = layout.buttonDp)
        if (layout.showsSearch) {
            // A weight with a ceiling, not a fixed width. The fixed 260 dp was the one
            // thing in this row that could not give way, so it was what pushed Settings
            // off the end of a window at the size the app opens at.
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                singleLine = true,
                placeholder = { Text("Search in the list", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(15.dp)) },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = SEARCH_MIN_DP.dp, max = SEARCH_MAX_DP.dp)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        ToolbarButton("Settings", Icons.Default.Settings, onClick = onSettings, compact = compact, buttonWidth = layout.buttonDp)
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    /**
     * Icon-only. Nine captioned buttons are 666 dp before the search box, which is why
     * the right-hand end of the toolbar used to be cut off rather than wrapped - at the
     * app's own opening width, not only on a deliberately small one. An icon-only
     * button only needs room for a 30 dp target; the tooltip still names it.
     */
    compact: Boolean = false,
    /** The captioned width the caller worked out for this window. */
    buttonWidth: Float = CAPTION_BUTTON_DP
) {
    val tint = when {
        !enabled -> Color(0xFF4A5759)
        highlighted -> Color(0xFF0B1A14)
        else -> Color(0xFFB4C0C2)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(if (compact) COMPACT_BUTTON_DP.dp else buttonWidth.dp)
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
        if (!compact) {
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
}

@Composable
private fun ColumnHeader(sort: LibrarySort, onSort: (LibrarySort) -> Unit, layout: TableLayout) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161C1F))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(26.dp))
        // The name is the one column that takes the space the others leave, so it is a
        // weight rather than a width. Everything else has a fixed width and is dropped
        // from the right, in the order of how much a person needs it: a countdown
        // nobody can read is worth less than a status, and both less than the size.
        ColumnHeaderCell("Name", Dp.Unspecified, Modifier.weight(1f), sort, DownloadColumn.NAME, onSort)
        if (layout.size > 0.dp) {
            ColumnHeaderCell("Size", layout.size, Modifier, sort, DownloadColumn.SIZE, onSort)
        }
        if (layout.showStatus) {
            ColumnHeaderCell("Status", layout.status, Modifier, sort, DownloadColumn.STATUS, onSort)
        }
        if (layout.showSpeed) {
            ColumnHeaderCell("Speed", layout.speed, Modifier, sort, DownloadColumn.SPEED, onSort)
        }
        if (layout.showTimeLeft) {
            ColumnHeaderCell("Time Left", layout.timeLeft, Modifier, sort, DownloadColumn.TIME_LEFT, onSort)
        }
        if (layout.showDateAdded) {
            ColumnHeaderCell("Date Added", layout.dateAdded, Modifier, sort, DownloadColumn.DATE_ADDED, onSort)
        }
    }
}

@Composable
private fun ColumnHeaderCell(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    sort: LibrarySort,
    column: DownloadColumn,
    onSort: (LibrarySort) -> Unit
) {
    val active = sort.column == column
    Row(
        modifier = modifier
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    palette: androidx.compose.material3.ColorScheme,
    layout: TableLayout,
    checked: Boolean,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    /** Opens this download's own settings. */
    onOptions: () -> Unit,
    /** Right-click: the menu every other torrent client opens. */
    onContext: () -> Unit = {}
) {
    val running = item.status == DownloadStatus.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) Color(0xFF1C2A28) else Color.Transparent)
            .clickable(onClick = onToggle)
            // The secondary button, read off the raw event rather than taken through
            // `combinedClickable`'s long-press.
            //
            // Compose Desktop treats a secondary press as the start of a long press and
            // holds it. That produced two failures from one cause: the context menu never
            // opened, and - because the same handler sat in the row's own chain - the pause
            // and gear buttons inside the row stopped responding too. `combinedClickable`
            // is not used anywhere in this row.
            .onPointerEvent(PointerEventType.Press) { event ->
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                // The button number, not `isRightButtonDown`: the flag is also set on the
                // release, and a context menu that opens again on mouse-up is worse than
                // one that opens once.
                if (mouse != null && mouse.button == java.awt.event.MouseEvent.BUTTON3) {
                    onContext()
                    event.changes.forEach { it.consume() }
                }
            }
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

        // A weight, not a width: the name is the column that has to absorb whatever the
        // window has left over. It matches the header, which is weighted the same way.
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
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

        if (layout.size > 0.dp) {
            Cell(DisplayFormat.bytes(item.totalBytes), layout.size, palette)
        }
        if (layout.showStatus) {
            Cell(DisplayFormat.status(item), layout.status, palette, colour = statusColour(item.status, palette))
        }
        if (layout.showSpeed) {
            Cell(DisplayFormat.speed(item.speedBytesPerSecond), layout.speed, palette)
        }
        if (layout.showTimeLeft) {
            Cell(
                DisplayFormat.timeLeft(DownloadLibrary.estimateSecondsLeft(item)),
                layout.timeLeft,
                palette
            )
        }
        if (layout.showDateAdded) {
            Cell(DisplayFormat.timeAgo(item.createdAt), layout.dateAdded, palette)
        }

        if (layout.showRowActions) {
            Row(
                Modifier.width(ROW_ACTION_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.RESOLVING ->
                        IconButton(16.dp, DlmIcons.Pause, "Pause", onPause)

                    DownloadStatus.PAUSED -> IconButton(16.dp, Icons.Default.PlayArrow, "Resume", onResume)
                    DownloadStatus.FAILED -> IconButton(16.dp, Icons.Default.Refresh, "Retry", onRetry)
                    DownloadStatus.COMPLETED -> IconButton(16.dp, DlmIcons.FolderOpen, "Show in folder", onOpen)
                }
                // Always present, unlike the status button above, because these settings
                // are wanted on a finished download (to set a share limit) as much as on
                // a running one - and on a paused one more than anything else.
                IconButton(16.dp, Icons.Default.Settings, "Download options", onOptions)
            }
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

/**
 * A small icon button on a row.
 *
 * The `onClick` used to be accepted and then ignored - the composable drew the icon and
 * nothing else, which is why the pause, retry and gear buttons on every row did nothing
 * when pressed. The icon is drawn at [size] and the whole button is a 24 dp target, so it
 * can actually be hit with a mouse.
 */
@Composable
private fun IconButton(
    size: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, Modifier.size(size), tint = Color(0xFF8A9799))
    }
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
    outputPath = outputPath,
    priority = DownloadPriority.fromRank(priorityRank),
    speedLimitBytesPerSecond = speedLimitBytesPerSecond,
    startAfterEpochMillis = startAfterEpochMillis,
    shareRatioLimit = shareRatioLimit,
    seedTimeLimitMinutes = seedTimeLimitMinutes,
    seedingSinceEpochMillis = seedingSinceEpochMillis,
    seedingStoppedAtEpochMillis = seedingStoppedAtEpochMillis,
    torrentSelectedFiles = torrentSelectedFiles,
    torrentSequential = torrentSequential,
    torrentFirstLastPiecesFirst = torrentFirstLastPiecesFirst,
    torrentContentFolder = torrentContentFolder,
    uploadRate = uploadRate,
    seeds = seeds,
    peerCount = peerCount,
    uploadedBytes = uploadedBytes,
    completedAt = completedAt
)
