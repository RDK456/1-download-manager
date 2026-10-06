package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import com.downloadhub.core.DownloadStatus

/**
 * The right-click menu on a download.
 *
 * Modelled on the same two apps the rest of this came from. qBittorrent's torrent menu is
 * the shape to follow, because it is the one a torrent user already has in their hands.
 *
 * What is here is what this app can actually do. An item that appears and then reports
 * "not supported" is worse than one that is absent, because the list is the app's own
 * description of itself - see [contextActions].
 */
sealed interface ContextAction {
    /** Stops a running or queued download. */
    data object Pause : ContextAction

    /** Puts a paused download back in the queue. */
    data object Resume : ContextAction

    /** Clears the error and starts again. */
    data object ForceStart : ContextAction

    /**
     * Asks whether to remove the row, and whether to delete what is on disk.
     *
     * A menu that deleted immediately on click would be the worst possible thing to put
     * behind a right-click, so removal always asks.
     */
    data object Remove : ContextAction

    /** Opens this download's own settings. */
    data object Options : ContextAction

    /** Picks a different folder, for a download that has not finished. */
    data object SetLocation : ContextAction

    /** Renames the finished file, on disk and in the list. */
    data object Rename : ContextAction

    /** Opens the folder it sits in, with it selected. */
    data object OpenFolder : ContextAction

    /** Copies the magnet link to the clipboard. */
    data object CopyMagnet : ContextAction

    /** Saves the .torrent file somewhere else. */
    data object ExportTorrent : ContextAction

    /**
     * Always offered and never enabled.
     *
     * It is in qBittorrent's menu and users look for it, so leaving it out reads as an
     * oversight rather than as "not supported". A tick box that cannot be ticked is a lie
     * about what the app does with the files.
     */
    data object AutomaticManagement : ContextAction

    /** Puts it in another named queue, which decides when it starts. */
    data object MoveToQueue : ContextAction

    /** Re-reads every piece against its hash. qBittorrent's Force recheck. */
    data object ForceRecheck : ContextAction

    /** Asks the trackers for peers now. qBittorrent's Force reannounce. */
    data object ForceReannounce : ContextAction
}

/**
 * What this download's menu should offer, and in what order.
 *
 * Decided from the item's own state rather than shown-and-greyed, because a list of things
 * that are all unavailable says nothing, while a list of the things that apply to *this*
 * download says exactly what can be done to it.
 */
fun contextActions(item: DownloadItem, hasContentFiles: Boolean): List<ContextAction> = buildList {
    when (item.status) {
        DownloadStatus.RUNNING, DownloadStatus.RESOLVING, DownloadStatus.QUEUED ->
            add(ContextAction.Pause)

        DownloadStatus.PAUSED -> add(ContextAction.Resume)
        DownloadStatus.FAILED -> {
            // Force start and resume differ only for something that has already failed.
            add(ContextAction.ForceStart)
            add(ContextAction.Resume)
        }
        else -> Unit
    }
    add(ContextAction.Options)
    if (item.status != DownloadStatus.COMPLETED) add(ContextAction.MoveToQueue)
    if (!item.isTorrent && item.status != DownloadStatus.COMPLETED) {
        add(ContextAction.SetLocation)
    }
    if (item.status == DownloadStatus.COMPLETED && hasContentFiles) {
        add(ContextAction.Rename)
    }
    if (hasContentFiles) add(ContextAction.OpenFolder)
    if (item.isTorrent) {
        add(ContextAction.ForceRecheck)
        add(ContextAction.ForceReannounce)
        add(ContextAction.CopyMagnet)
        add(ContextAction.ExportTorrent)
    }
    add(ContextAction.AutomaticManagement)
    // Last, and always present: it is the only destructive one, and the dialog it opens
    // is the confirmation.
    add(ContextAction.Remove)
}

/** The label for a menu item. */
fun contextActionLabel(action: ContextAction): String = when (action) {
    ContextAction.Pause -> "Pause"
    ContextAction.Resume -> "Resume"
    ContextAction.ForceStart -> "Force Start"
    ContextAction.Remove -> "Remove"
    ContextAction.Options -> "Options..."
    ContextAction.SetLocation -> "Set location..."
    ContextAction.Rename -> "Rename..."
    ContextAction.OpenFolder -> "Open destination folder"
    ContextAction.CopyMagnet -> "Copy magnet link"
    ContextAction.ExportTorrent -> "Export .torrent..."
    ContextAction.AutomaticManagement -> "Automatic Torrent Management"
    ContextAction.MoveToQueue -> "Move to queue..."
    ContextAction.ForceRecheck -> "Force recheck"
    ContextAction.ForceReannounce -> "Force reannounce"
}

/**
 * The text "Copy magnet link" puts on the clipboard.
 *
 * A magnet for a torrent that was added as a magnet. One for a torrent added as a file
 * cannot be built - the tracker list lives in the file, not in anything the app kept - so
 * the file's own path is copied instead, which is something the user can actually paste
 * somewhere useful.
 */
fun magnetLinkFor(item: DownloadItem): String = when {
    item.url.startsWith("magnet:", ignoreCase = true) -> item.url
    !item.torrentFilePath.isNullOrBlank() -> item.torrentFilePath.orEmpty()
    else -> item.url
}

/**
 * The menu itself: a dropdown at the pointer, not a dialog in the middle of the window.
 *
 * It was an `AlertDialog`, which is why it looked like a pop-up. A dialog is a modal
 * panel in the centre of its window with a scrim across everything, a heading, a title and
 * a Close button - none of which is what a right-click menu is, and all of which make the
 * thing feel like an error message about the row rather than a list of things you can do
 * to it.
 *
 * It is drawn as an overlay in the *same coordinate space as the list*, offset from the
 * pointer's own position, which is why it needs nothing from the window manager. The
 * earlier version's note claimed an anchored menu needed screen coordinates that Compose
 * Desktop does not hand out. It does - the right-click handler already reads them off a
 * raw `java.awt.event.MouseEvent` - but they are the wrong thing anyway: screen
 * coordinates have to be converted back through the window's position and then clipped to
 * it, and every one of those steps is a way to put the menu somewhere the user cannot
 * click. Positioned inside the list's own box, it cannot leave the window at all.
 */
@Composable
fun DownloadContextMenu(
    item: DownloadItem,
    hasContentFiles: Boolean,
    atX: Int,
    atY: Int,
    onAction: (ContextAction) -> Unit,
    onDismiss: () -> Unit
) {
    val actions = remember(item.id, item.status, hasContentFiles) {
        contextActions(item, hasContentFiles)
    }
    val width = 230.dp
    val density = LocalDensity.current.density
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Flipped rather than clipped when it would run past the window's edge. A menu
        // that opens downwards off the bottom of the window cannot be dismissed by
        // clicking anything in it, which is the state the old clamping tried to avoid by
        // always showing it centred.
        val menuHeight = (actions.size * 30 + 12).dp
        val flipUp = maxHeight.value - atY < menuHeight.value * density + 8f
        val x = atX.coerceIn(4, (maxWidth.value - width.value).toInt().coerceAtLeast(4))
        val y = if (flipUp) {
            (atY - menuHeight.value * density).toInt().coerceAtLeast(4)
        } else {
            atY
        }
        // Clicks anywhere else close it, and nothing is dimmed: a context menu is not
        // modal, and the rest of the list stays readable behind it.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss
                )
        )
        Column(
            Modifier
                .offset { IntOffset(x, y) }
                .width(width)
                .background(AppTheme.Palette.menuPanel, RoundedCornerShape(6.dp))
                .border(1.dp, AppTheme.Palette.menuEdge, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp)
        ) {
            Text(
                item.fileName,
                fontSize = 10.sp,
                color = AppTheme.Palette.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
            HorizontalDivider(color = AppTheme.Palette.outlineVariant)
            actions.forEach { action ->
                ContextRow(
                    label = contextActionLabel(action),
                    // Only removal is destructive, and only removal is coloured. A menu
                    // where everything is red says nothing about what is dangerous.
                    destructive = action == ContextAction.Remove,
                    muted = action == ContextAction.AutomaticManagement,
                    onClick = { onAction(action) }
                )
            }
        }
    }
}

@Composable
private fun ContextRow(label: String, destructive: Boolean = false, muted: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = when {
            destructive -> AppTheme.Palette.error
            muted -> AppTheme.Palette.faint
            else -> AppTheme.Palette.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // A menu row that does not change under the pointer does not read as
            // clickable, which is the whole of what makes a menu feel like a menu.
            .background(AppTheme.Palette.selection, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

/** Asks for a new name for a finished file. */
@Composable
fun RenameDownloadDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("File name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().onEnter(name.trim().isNotEmpty()) { onConfirm(name.trim()) }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty()
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Asks where an unfinished download should be written. */
@Composable
fun SetLocationDialog(
    currentDirectory: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var path by remember { mutableStateOf(currentDirectory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Set location") },
        text = {
            Column {
                Text(
                    "Where this download is written. It has not finished, so nothing is on " +
                        "disk yet to move.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onEnter(path.trim().isNotEmpty()) { onConfirm(path.trim()) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(path.trim()) },
                enabled = path.trim().isNotEmpty()
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
