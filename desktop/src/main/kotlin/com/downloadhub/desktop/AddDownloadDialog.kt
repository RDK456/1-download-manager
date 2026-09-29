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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.downloadhub.core.LinkParser
import com.downloadhub.core.TorrentAddRequest
import com.downloadhub.core.TorrentFile
import com.downloadhub.core.TorrentMetainfo
import com.downloadhub.core.TorrentSelection
import com.downloadhub.core.TorrentStopCondition
import java.io.File
import java.util.Locale

/**
 * Where a new download starts from.
 *
 * The `+` button and New Download used to set a flag that nothing read, because the only
 * dialog they used to open was replaced by [AddDownloadDialog] and the call site went
 * with it. The button looked live and did nothing, which is the worst kind of broken:
 * there is no error, just a dialog that never arrives.
 *
 * So the entry point is here and it always leads to the pre-download dialog. There is no
 * path into the queue that skips it, which is also what makes the dialog reliable: it
 * cannot be reached with a `.torrent` queued unseen, because the queue is only ever
 * written from a request this dialog produced.
 */
@Composable
fun NewDownloadDialog(
    onPickTorrent: () -> File?,
    onSubmit: (PendingDownload) -> Unit,
    onDismiss: () -> Unit
) {
    var link by remember { mutableStateOf("") }
    // Set when a picked file turns out not to be a torrent, so the reason sits under the
    // box rather than replacing the whole dialog.
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("New download") },
        text = {
            Column {
                Text(
                    "Paste a link, or pick a .torrent file. Either way you get to look at " +
                        "it before it is queued.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; problem = null },
                    label = { Text("Link or magnet") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val file = onPickTorrent()
                        if (file == null) {
                            // Cancelling the file chooser is not a problem, so the message
                            // is only for a file that was chosen and could not be used.
                            return@OutlinedButton
                        }
                        val pending = PendingDownload.forLink(file.absolutePath)
                        if (pending == null) {
                            problem = "${file.name} could not be read as a torrent."
                        } else {
                            onSubmit(pending)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose a .torrent file...") }
                problem?.let { reason ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        reason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pending = PendingDownload.forLink(link)
                    if (pending == null) {
                        problem = "That is not a link, a magnet or a file on this computer."
                    } else {
                        onSubmit(pending)
                    }
                },
                enabled = link.isNotBlank()
            ) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The dialog shown before anything is queued - a link, a magnet or a `.torrent`. */
data class PendingDownload(
    /** The link to queue. */
    val link: String,
    /** What is known. Empty for a magnet or a plain link. */
    val metainfo: TorrentMetainfo,
    /** The `.torrent` behind it, when there is one. */
    val file: File? = null
) {
    /** The name to show in the title and to queue under. */
    val name: String
        get() = metainfo.name.ifBlank { LinkParser.fileNameFrom(link) }

    /** True when there is a file list to choose between. */
    val hasFileList: Boolean get() = metainfo.files.isNotEmpty()

    /** A magnet, a `.torrent` on disk, or something fetched over HTTP. */
    val isTorrent: Boolean
        get() = file != null || link.startsWith("magnet:", ignoreCase = true)

    companion object {
        /**
         * Works out what a link is, reading a `.torrent` if it is one.
         *
         * A magnet and an ordinary link both come back with empty metadata rather than as
         * a failure: a magnet genuinely has no file list until peers answer, and refusing to
         * show the dialog for one would make the dialog unusable for the two most common
         * kinds of download.
         */
        fun forLink(link: String, isFile: (String) -> Boolean = { File(it).isFile }): PendingDownload? {
            val trimmed = link.trim()
            if (trimmed.isEmpty()) return null
            if (!LinkParser.isFetchable(trimmed, null) && !isFile(trimmed)) return null

            val file = if (LinkParser.sourceFor(trimmed) == com.downloadhub.core.DownloadSource.TORRENT) {
                File(trimmed).takeIf { it.isFile }
            } else {
                null
            }
            val meta = file?.let { runCatching { com.downloadhub.core.TorrentParser.parse(it) }.getOrNull() }
                // A magnet's name is in its own `dn=` parameter. `fileNameFrom` cannot see
                // it - there is no path - and falls back to "download", which is what the
                // list and the dialog title would then say.
                ?: TorrentMetainfo.empty(
                    if (trimmed.startsWith("magnet:", ignoreCase = true)) {
                        LinkParser.magnetDisplayName(trimmed)
                            ?: LinkParser.fileNameFrom(trimmed)
                    } else {
                        LinkParser.fileNameFrom(trimmed)
                    }
                )
            return PendingDownload(trimmed, meta, file)
        }
    }
}

/**
 * The pre-download dialog.
 *
 * Offered for every kind of add, because there is always something to choose: where it
 * goes, what it is called, and for a `.torrent` which files. A magnet and a plain link
 * have no file list to pick, and the dialog says so rather than showing an empty pane the
 * user has to guess at.
 *
 * Modelled on qBittorrent's Add Torrent dialog, because that is the one people already
 * know - and on AB Download Manager's flat list, which is the other look being asked for.
 */
@Composable
fun AddDownloadDialog(
    pending: PendingDownload,
    defaultDirectory: String,
    deleteCacheWhenRemoved: Boolean,
    onPickDirectory: () -> File?,
    onConfirm: (TorrentAddRequest) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Called once the dialog has a real size, so the window can raise itself.
     *
     * Without it a dialog opened by a `.torrent` double-clicked in Explorer can open
     * behind Explorer: the file comes to the front, and the window that owns the dialog is
     * behind it. The dialog is present either way, which is what makes it read as "the
     * dialog does not open".
     */
    onShown: () -> Unit = {}
) {
    val selected = remember(pending.link) {
        mutableStateOf(TorrentSelection.allSelected(pending.metainfo))
    }
    var directory by remember(pending.link) { mutableStateOf(defaultDirectory) }
    var folder by remember(pending.link) {
        mutableStateOf(TorrentSelection.contentFolder(pending.metainfo, ""))
    }
    var filter by remember(pending.link) { mutableStateOf("") }
    var sequential by remember(pending.link) { mutableStateOf(false) }
    var firstLastPieces by remember(pending.link) { mutableStateOf(false) }
    var startNow by remember(pending.link) { mutableStateOf(true) }
    var stopWhen by remember(pending.link) { mutableStateOf(0) }
    var stopValue by remember(pending.link) { mutableStateOf("2.0") }

    // Reported from an effect rather than from `onSizeChanged`.
    //
    // A size callback fires *during* layout, and calling back into Compose from there
    // changes state mid-layout, which Compose refuses with "layout state is not idle
    // before measure starts" - a hard crash, and one that only shows up by actually
    // opening the dialog.
    LaunchedEffect(pending.link) { onShown() }

    val rows = remember(pending.link, filter) { contentRowsFor(pending.metainfo, filter) }
    val chosenSize = TorrentSelection.selectedSize(pending.metainfo, selected.value)

    AlertDialog(
        onDismissRequest = onDismiss,
        // Inside the Window, like every dialog here: a Dialog composed outside one has no
        // Compose scene and takes the whole JVM down with it.
        properties = APP_DIALOG_PROPERTIES,
        title = {
            Column {
                Text(if (pending.isTorrent) "Add torrent" else "Add download")
                Text(
                    pending.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 620.dp)) {
                // ---- where it goes ----------------------------------------------
                Text("Save at", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = directory,
                        onValueChange = { directory = it },
                        label = { Text("Folder", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        onPickDirectory()?.let { directory = it.absolutePath }
                    }) { Text("Browse", fontSize = 12.sp) }
                }
                if (pending.hasFileList) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Content layout - folder name", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !pending.metainfo.isSingleFile,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                // ---- the files ---------------------------------------------------
                if (pending.hasFileList) {
                    Text("Files", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    ContentFileList(
                        rows = rows,
                        filter = filter,
                        onFilter = { filter = it },
                        // The selection is the dialog's own state, because the Add button
                        // builds the request out of it. A list that kept a private copy
                        // would draw ticks the Add button then ignored.
                        selected = selected.value,
                        onSelectionChange = { chosen -> selected.value = chosen },
                        onSelectAll = {
                            selected.value = TorrentSelection.allSelected(pending.metainfo)
                        },
                        onSelectNone = { selected.value = emptySet() }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${selected.value.size} of ${pending.metainfo.files.size} files, " +
                            DisplayFormat.bytes(chosenSize) +
                            if (chosenSize < pending.metainfo.totalSize) {
                                " of ${DisplayFormat.bytes(pending.metainfo.totalSize)}"
                            } else {
                                ""
                            },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Said out loud rather than showing an empty pane: a magnet and a
                    // plain link have no file list, and a blank area says nothing about
                    // whether that is a bug.
                    Text(
                        when {
                            pending.link.startsWith("magnet:", ignoreCase = true) ->
                                "A magnet has no file list until it connects, so there is " +
                                    "nothing to choose here. You can still set where it goes " +
                                    "and when it should stop sharing."
                            else ->
                                "The size of this is not known until the headers arrive, " +
                                    "so there is nothing to choose here."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                // ---- how it downloads ---------------------------------------------
                if (pending.isTorrent) {
                    Text("Torrent options", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    TickRow("Download in sequential order", sequential) { sequential = it }
                    TickRow("Download first and last pieces first", firstLastPieces) {
                        firstLastPieces = it
                    }
                    TickRow("Start torrent", startNow) { startNow = it }
                    Spacer(Modifier.height(6.dp))
                    Text("Stop condition", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    StopRow(0, "None", stopWhen) { stopWhen = it }
                    StopRow(1, "Ratio reached", stopWhen) { stopWhen = it }
                    StopRow(2, "Uploaded amount reached", stopWhen) { stopWhen = it }
                    StopRow(3, "Seeding time reached", stopWhen) { stopWhen = it }
                    if (stopWhen > 0) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = stopValue,
                            onValueChange = { stopValue = it },
                            label = {
                                Text(
                                    when (stopWhen) {
                                        1 -> "Ratio (for example 2.0)"
                                        2 -> "Amount in MB (for example 500)"
                                        else -> "Minutes (for example 30)"
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (pending.isTorrent) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    TorrentInformationBlock(pending.metainfo)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val request = TorrentSelection.validated(
                        meta = pending.metainfo,
                        link = pending.link,
                        saveDirectory = File(directory),
                        selected = selected.value,
                        metainfoFile = pending.file,
                        sequential = sequential,
                        firstLastPiecesFirst = firstLastPieces,
                        startImmediately = startNow,
                        stopCondition = stopConditionFrom(stopWhen, stopValue),
                        chosenFolder = folder
                    )
                    if (request != null) onConfirm(request)
                },
                // Disabled rather than silently doing nothing when nothing is selected:
                // a button that looks live and then adds nothing is the bug this dialog
                // was built to avoid.
                enabled = pending.link.isNotBlank() &&
                    (pending.metainfo.files.isEmpty() || selected.value.isNotEmpty())
            ) { Text(if (pending.isTorrent) "OK" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One row of the file list. */
data class ContentRow(val path: String, val index: Int, val size: Long) {
    /** The last segment: a path column 90 characters wide shows one file and no context. */
    val name: String get() = path.substringAfterLast('/')

    /**
     * What the list actually shows, shortened from the *front*.
     *
     * A release torrent names every one of its files the same 60-character way and tells
     * them apart only in the last few characters - "[Judas] Chainsaw Man (Season 1)
     * [1080p][HEVC x265 10bit] - 01.mkv" against the same for 02. Truncating that the
     * usual way, at the end, makes all of them read "[Judas] Chainsaw Man (Season..." so
     * the list cannot tell its own rows apart. Keeping the tail and dropping the head
     * shows the part that differs, which is the part being looked for.
     */
    val displayName: String
        get() {
            val whole = name
            return if (whole.length <= NAME_BUDGET) {
                whole
            } else {
                "..." + whole.takeLast(NAME_BUDGET - 3)
            }
        }

    companion object {
        /**
         * How many characters of a name the list shows.
         *
         * A count rather than a measurement because the column is sized in `dp` and the
         * characters are not a fixed width; this is close enough that a long name is cut
         * rather than silently wrapped onto two lines, which is what a name column that
         * grows does to a list of thirteen.
         */
        const val NAME_BUDGET = 44
    }
}

/**
 * The rows to show, filtered.
 *
 * The filter matches the whole path even though the name is displayed, because a torrent
 * with two files of the same name in different folders is common and filtering on the name
 * alone would hide one of them for no reason.
 */
fun contentRowsFor(meta: TorrentMetainfo, filter: String): List<ContentRow> {
    val needle = filter.trim().lowercase(Locale.US)
    val all = meta.files.map { ContentRow(it.path, it.index, it.size) }
    return if (needle.isEmpty()) all else all.filter { it.path.lowercase(Locale.US).contains(needle) }
}

/**
 * The torrent's file list.
 *
 * A thin wrapper over [ContentTreeList], kept because two callers want the same list with
 * different amounts of editing: the pre-download dialog edits it, and the detail pane's
 * Content tab reports it. The tree itself lives in one place, so the two cannot drift into
 * showing the same torrent differently.
 */
@Composable
fun ContentFileList(
    rows: List<ContentRow>,
    filter: String,
    onFilter: (String) -> Unit,
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    readOnly: Boolean = false
) {
    ContentTreeList(
        rows = rows,
        selected = selected,
        onSelectionChange = onSelectionChange,
        filter = filter,
        onFilter = onFilter,
        onSelectAll = onSelectAll,
        onSelectNone = onSelectNone,
        readOnly = readOnly
    )
}
@Composable
private fun HeaderCell(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * The torrent's own details, read-only.
 *
 * The info hash in particular is worth showing: it is how anyone else identifies this exact
 * torrent, and a user comparing against another client needs it to know whether they are
 * looking at the same thing.
 */
@Composable
fun TorrentInformationBlock(metainfo: TorrentMetainfo) {
    Column(Modifier.fillMaxWidth()) {
        Text("Torrent information", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        InfoRow("Name", metainfo.name)
        InfoRow(
            "Size",
            DisplayFormat.bytes(metainfo.totalSize) + " (" + metainfo.files.size + " files)"
        )
        InfoRow(
            "Date",
            if (metainfo.createdAtEpochMillis > 0) {
                java.time.Instant.ofEpochMilli(metainfo.createdAtEpochMillis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime().toString().replace('T', ' ')
            } else {
                "Not available"
            }
        )
        InfoRow("Created by", metainfo.createdBy.ifBlank { "Not available" })
        if (metainfo.infoHashV1.isNotBlank()) InfoRow("Info hash v1", metainfo.infoHashV1)
        if (metainfo.infoHashV2.isNotBlank()) InfoRow("Info hash v2", metainfo.infoHashV2)
        if (metainfo.pieceLength > 0) {
            InfoRow("Piece size", DisplayFormat.bytes(metainfo.pieceLength))
        }
        if (metainfo.comment.isNotBlank()) InfoRow("Comment", metainfo.comment)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            // A fixed label column so the values line up. An info hash is 64 characters
            // wide and a label that resized with it would push the values around.
            modifier = Modifier.width(96.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, fontSize = 11.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StopRow(index: Int, label: String, current: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(index) }
    ) {
        RadioButton(selected = current == index, onClick = { onChange(index) })
        Text(label, fontSize = 12.sp)
    }
}

/**
 * Turns the stop-sharing choice into a value the engine can act on.
 *
 * Anything unparseable becomes "never" rather than a silent limit of zero, which would mean
 * stop immediately and look like the torrent finished at once.
 */
internal fun stopConditionFrom(which: Int, value: String): TorrentStopCondition = when (which) {
    1 -> value.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
        ?.let { TorrentStopCondition.AtRatio(it) } ?: TorrentStopCondition.Never
    2 -> value.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let {
        TorrentStopCondition.AtUploadedAmount((it * 1024L * 1024L).toLong())
    } ?: TorrentStopCondition.Never
    3 -> value.trim().toIntOrNull()?.takeIf { it > 0 }
        ?.let { TorrentStopCondition.AfterSeedingFor(it) } ?: TorrentStopCondition.Never
    else -> TorrentStopCondition.Never
}
