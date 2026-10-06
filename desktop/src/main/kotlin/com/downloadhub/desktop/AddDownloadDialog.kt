package com.downloadhub.desktop

import com.downloadhub.core.ContentNode
import com.downloadhub.core.ContentRow
import com.downloadhub.core.contentTree
import com.downloadhub.core.visibleContentNodes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
    onDismiss: () -> Unit,
    /** Several links, or a numbered range like `file[01-20].jpg`: queued together. */
    onBatch: (List<String>) -> Unit = {}
) {
    // Starts with a link already on the clipboard, as AB Download Manager does: the usual
    // way to get here is having just copied one.
    var link by remember { mutableStateOf(clipboardLink().orEmpty()) }
    // Set when a picked file turns out not to be a torrent, so the reason sits under the
    // box rather than replacing the whole dialog.
    var problem by remember { mutableStateOf<String?>(null) }
    val linkFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { linkFocus.requestFocus() } }
    val batch = remember(link) { com.downloadhub.core.BatchLinks.expand(link) }.takeIf { it.size > 1 }
    fun next() {
        if (batch != null) return onBatch(batch)
        val pending = PendingDownload.forLink(link)
        if (pending == null) {
            problem = "That is not a link, a magnet or a file on this computer."
        } else {
            onSubmit(pending)
        }
    }

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
                    label = { Text("Link or magnet - or several links, or a range like file[01-20].jpg") },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().focusRequester(linkFocus).onEnter(link.isNotBlank()) { next() }
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
            Button(onClick = { next() }, enabled = link.isNotBlank()) { Text(if (batch != null) "Queue ${batch.size} links" else "Next") }
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
     *
     * Without it a dialog opened by a `.torrent` double-clicked in Explorer can open
     * behind Explorer: the file comes to the front, and the window that owns the dialog is
     * behind it. The dialog is present either way, which is what makes it read as "the
     * dialog does not open".
     */
    onShown: () -> Unit = {},
    /**
     * Asked to fetch a magnet's file list, which the magnet does not carry.
     *
     * Called once, when the dialog opens on a magnet. The result is handed back on the
     * same thread; the dialog shows a "reading the file list" state meanwhile and carries
     * on with what it knows if nothing arrives.
     */
    onLoadMetadata: (String, (TorrentMetainfo?) -> Unit) -> Unit = { _, done -> done(null) }
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
    // What a plain link sends with its requests. Empty means nothing extra is sent.
    var headersText by remember(pending.link) { mutableStateOf("") }
    var cookies by remember(pending.link) { mutableStateOf("") }
    var username by remember(pending.link) { mutableStateOf("") }
    var password by remember(pending.link) { mutableStateOf("") }

    /**
     * The metadata in force, which starts as whatever the caller already had and is
     * replaced by the fetched one.
     *
     * Held here rather than pushed back into the caller because the file list, the folder
     * name, the totals and the information block all read it, and a magnet's arrives
     * after this is already on screen.
     */
    var metainfo by remember(pending.link) { mutableStateOf(pending.metainfo) }
    var loading by remember(pending.link) { mutableStateOf(false) }

    /**
     * Only a magnet needs its file list fetched: it does not carry one, and it has one in
     * the swarm's metadata, a few kilobytes away.
     */
    val wantsMetadata = pending.metainfo.files.isEmpty() && pending.isTorrent
    // Bumped by "Try again", which asks the swarm a second time.
    var attempt by remember(pending.link) { mutableStateOf(0) }
    LaunchedEffect(pending.link, attempt) {
        if (wantsMetadata) {
            loading = true
            onLoadMetadata(pending.link) { fetched ->
                if (fetched != null && fetched.files.isNotEmpty()) {
                    selected.value = TorrentSelection.allSelected(fetched)
                    folder = TorrentSelection.contentFolder(fetched, folder)
                }
                metainfo = fetched ?: metainfo
                loading = false
            }
        }
    }

    /**
     * The file list, keyed on the metadata as well as the filter.
     *
     * It was not, and that is the bug behind "shows as no files": a magnet's list arrives
     * after this has composed, so `remember` handed back the empty tree it built on the
     * first pass and never rebuilt it. The count line reads `metainfo.files.size` live,
     * so it said "0 of 12 files" over an empty list - the two numbers came from different
     * places.
     */
    val rows = remember(metainfo, filter) { contentRowsFor(metainfo, filter) }
    val chosenSize = TorrentSelection.selectedSize(metainfo, selected.value)

    /**
     * Everything starts selected, whenever the list turns out to be non-empty.
     *
     * Done here rather than only inside the loader's callback, so it holds however the
     * metadata arrived - fetched for a magnet, read from a file - and so a list that was
     * empty and then was not cannot leave OK disabled with every box unticked.
     */
    LaunchedEffect(metainfo.files.size) {
        if (metainfo.files.isNotEmpty() && selected.value.isEmpty()) {
            selected.value = TorrentSelection.allSelected(metainfo)
        }
        if (metainfo.files.isNotEmpty() && folder.isBlank()) {
            folder = TorrentSelection.contentFolder(metainfo, folder)
        }
    }

    val canConfirm = pending.link.isNotBlank() &&
        (metainfo.files.isEmpty() || selected.value.isNotEmpty())
    fun confirm() {
        val request = TorrentSelection.validated(
            meta = metainfo,
            link = pending.link,
            saveDirectory = File(directory),
            selected = selected.value,
            metainfoFile = pending.file,
            sequential = sequential,
            firstLastPiecesFirst = firstLastPieces,
            startImmediately = startNow,
            stopCondition = stopConditionFrom(stopWhen, stopValue),
            chosenFolder = folder
        )?.copy(
            http = com.downloadhub.core.HttpRequestOptions(
                headers = com.downloadhub.core.HttpRequestOptions.parseHeaders(headersText),
                cookies = cookies.trim(),
                username = username.trim(),
                password = password
            )
        )
        if (request != null) onConfirm(request)
    }
    // Enter is OK from anywhere in the window, except the request fields, where the
    // headers box needs it for new lines. The root takes focus on open so Enter works
    // before anything has been clicked.
    var typingRequest by remember(pending.link) { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onEnter(canConfirm && !typingRequest) { confirm() }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        // ---- the heading, with the buttons on the same line --------------------
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (pending.isTorrent) "Add torrent" else "Add download",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = DIALOG_PRIMARY
                )
                Text(
                    // The fetched name when there is one: a magnet's `dn=` is a guess
                    // its author typed, and the swarm's metadata is the real one.
                    if (metainfo.name.isNotBlank()) metainfo.name else pending.name,
                    fontSize = 12.sp,
                    color = DIALOG_SECONDARY,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ---- two columns -------------------------------------------------------
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            // A share of the window's width, clamped at both ends, rather than a
            // constant. Fixed at 340 dp it was right in exactly one window size: drag the
            // window narrower and the two columns between them ran out of room, so the
            // file list's four numbers stopped fitting and its rows were drawn past the
            // right-hand edge and gone. The list takes whatever is left, and fits its
            // columns to it.
            val optionsWidth = (maxWidth.value * OPTIONS_COLUMN_SHARE)
                .coerceIn(OPTIONS_COLUMN_MIN_DP, OPTIONS_COLUMN_MAX_DP)
            Row(Modifier.fillMaxSize()) {
            // ---- left: what to do with it -----------------------------------
            Column(
                Modifier
                    .width(optionsWidth.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(end = 14.dp)
            ) {
                SectionLabel("Save at")
                // The Browse button beside the field, not under it: a folder you can
                // only type into is a folder nobody chooses.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = directory,
                        onValueChange = { directory = it },
                        label = { Text("Folder", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { onPickDirectory()?.let { directory = it.absolutePath } },
                        modifier = Modifier.height(36.dp)
                    ) { Text("Browse", fontSize = 12.sp) }
                }
                if (metainfo.files.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Content layout - folder name", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !metainfo.isSingleFile,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (pending.isTorrent) {
                    SectionLabel("Torrent options")
                    TickRow("Start torrent", startNow) { startNow = it }
                    TickRow("Download in sequential order", sequential) { sequential = it }
                    TickRow(
                        "Download first and last pieces first",
                        firstLastPieces
                    ) { firstLastPieces = it }

                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Stop condition",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = DIALOG_PRIMARY,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        StopDropdown(selected = stopWhen, onChange = { stopWhen = it })
                    }
                    if (stopWhen > 1) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = stopValue,
                            onValueChange = { stopValue = it },
                            label = {
                                Text(
                                    when (stopWhen) {
                                        2 -> "Ratio (for example 2.0)"
                                        3 -> "Amount in MB (for example 500)"
                                        else -> "Minutes (for example 30)"
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    TorrentInformationBlock(metainfo)
                }
            }

            // ---- right: what is in it ---------------------------------------
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (metainfo.files.isNotEmpty()) {
                    ContentTreeList(
                        modifier = Modifier.weight(1f),
                        rows = rows,
                        filter = filter,
                        onFilter = { filter = it },
                        // The selection is this window's own state, because OK builds the
                        // request out of it. A list that kept a private copy would draw
                        // ticks OK then ignored.
                        selected = selected.value,
                        onSelectionChange = { chosen -> selected.value = chosen },
                        onSelectAll = {
                            selected.value = TorrentSelection.allSelected(metainfo)
                        },
                        onSelectNone = { selected.value = emptySet() }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${selected.value.size} of ${metainfo.files.size} files, " +
                            DisplayFormat.bytes(chosenSize) +
                            if (chosenSize < metainfo.totalSize) {
                                " of ${DisplayFormat.bytes(metainfo.totalSize)}"
                            } else {
                                ""
                            },
                        fontSize = 11.sp,
                        color = DIALOG_SECONDARY
                    )
                } else if (!pending.isTorrent) {
                    Box(Modifier.onFocusChanged { typingRequest = it.hasFocus }) {
                        HttpRequestFields(
                            headers = headersText,
                            onHeaders = { headersText = it },
                            cookies = cookies,
                            onCookies = { cookies = it },
                            username = username,
                            onUsername = { username = it },
                            password = password,
                            onPassword = { password = it }
                        )
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // A magnet's list is being fetched, and a plain link's genuinely
                        // cannot have one. Those are different things and must not share
                        // a sentence: one is a wait, the other is a fact. The old wording
                        // claimed the second about the first, so a magnet offered no
                        // choices at all on the grounds that it had none - when it had
                        // simply not been asked.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (loading) {
                                Text(
                                    "Reading the file list from the swarm...",
                                    fontSize = 12.sp,
                                    color = DIALOG_SECONDARY
                                )
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.width(220.dp),
                                    color = AppTheme.Palette.accent,
                                    trackColor = AppTheme.Palette.raised
                                )
                            } else {
                                Text(
                                    when {
                                        pending.link.startsWith("magnet:", ignoreCase = true) ->
                                            "No peers answered in time, so the file list is " +
                                                "not available yet. You can still set where " +
                                                "it goes, whether it starts straight away, " +
                                                "and when it should stop sharing."

                                        else ->
                                            "The size of this is not known until the " +
                                                "headers arrive, so there is nothing to " +
                                                "choose here."
                                    },
                                    fontSize = 12.sp,
                                    color = DIALOG_SECONDARY,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                if (wantsMetadata && metainfo.files.isEmpty()) {
                                    TextButton(onClick = { attempt++ }) { Text("Try again") }
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // ---- the buttons -------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel", color = DIALOG_SECONDARY) }
            Spacer(Modifier.width(8.dp))
            // Disabled rather than silently doing nothing when nothing is selected:
            // a button that looks live and then adds nothing is the bug this dialog
            // was built to avoid.
            Button(onClick = { confirm() }, enabled = canConfirm) { Text(if (pending.isTorrent) "OK" else "Add") }
        }
    }
}


fun contentRowsFor(meta: TorrentMetainfo, filter: String): List<ContentRow> {
    val needle = filter.trim().lowercase(Locale.US)
    val all = meta.files.map { ContentRow(it.path, it.index, it.size) }
    return if (needle.isEmpty()) all else all.filter { it.path.lowercase(Locale.US).contains(needle) }
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
        Text("Torrent information", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = DIALOG_PRIMARY)
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
            color = DIALOG_SECONDARY
        )
        Text(value, fontSize = 11.sp, color = DIALOG_PRIMARY, modifier = Modifier.weight(1f))
    }
}

/**
 * Turns the stop-sharing choice into a value the engine can act on.
 *
 * Anything unparseable becomes "never" rather than a silent limit of zero, which would mean
 * stop immediately and look like the torrent finished at once.
 */
internal fun stopConditionFrom(which: Int, value: String): TorrentStopCondition = when (which) {
    // No value to read: the download finishing is the whole of the condition.
    1 -> TorrentStopCondition.WhenComplete
    2 -> value.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
        ?.let { TorrentStopCondition.AtRatio(it) } ?: TorrentStopCondition.Never
    3 -> value.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let {
        TorrentStopCondition.AtUploadedAmount((it * 1024L * 1024L).toLong())
    } ?: TorrentStopCondition.Never
    4 -> value.trim().toIntOrNull()?.takeIf { it > 0 }
        ?.let { TorrentStopCondition.AfterSeedingFor(it) } ?: TorrentStopCondition.Never
    else -> TorrentStopCondition.Never
}


/**
 * The options column's width: a share of the window's, clamped at both ends.
 *
 * 36% is where the information block's 64-character info hash stops wrapping while the
 * file list still has room for a name and three numbers. The window is resizable, so these
 * are starting points rather than constraints - but a clamped share is what stops the two
 * columns either crowding each other out or leaving the file list a strip.
 */
private const val OPTIONS_COLUMN_SHARE = 0.36f
private const val OPTIONS_COLUMN_MIN_DP = 250f
private const val OPTIONS_COLUMN_MAX_DP = 400f



/**
 * Headers, cookies and a login for one link - what a site that only serves its own
 * signed-in visitors needs before it hands over a file. Shared by the add dialog and
 * the download's options, so the two ask for the same things the same way.
 */
@Composable
internal fun HttpRequestFields(
    headers: String,
    onHeaders: (String) -> Unit,
    cookies: String,
    onCookies: (String) -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionLabel("Request")
        Text(
            "Only needed when a site refuses the link on its own. Leave these empty otherwise.",
            fontSize = 11.sp,
            color = DIALOG_SECONDARY
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = headers,
            onValueChange = onHeaders,
            label = { Text("Extra headers, one per line (Name: value)", fontSize = 12.sp) },
            placeholder = { Text("Referer: https://example.com/page", fontSize = 12.sp) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = cookies,
            onValueChange = onCookies,
            label = { Text("Cookies (name=value; other=value)", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        SectionLabel("Login")
        Row {
            OutlinedTextField(
                value = username,
                onValueChange = onUsername,
                label = { Text("Username", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                label = { Text("Password", fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** A link or magnet on the clipboard, if that is what is there; null otherwise. */
internal fun clipboardLink(): String? = runCatching {
    val text = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    text?.trim()?.takeIf { it.length < 4096 && !it.contains('\n') && LinkParser.isFetchable(it, null) }
}.getOrNull()
