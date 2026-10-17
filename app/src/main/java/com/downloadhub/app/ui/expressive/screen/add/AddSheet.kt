package com.downloadhub.app.ui.expressive.screen.add

import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ClipboardPaste
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ScanSearch
import com.downloadhub.app.data.AppQueue
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.ui.expressive.model.AddOptions
import com.downloadhub.app.ui.expressive.model.addChoices
import com.downloadhub.app.ui.expressive.model.primaryAddLabel
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.label
import com.downloadhub.app.download.LinkParser
import com.downloadhub.app.download.LinkProbe
import com.downloadhub.app.ui.EditorSeed
import com.downloadhub.app.ui.RequestFields
import com.downloadhub.app.ui.expressive.model.AddNote
import com.downloadhub.app.ui.expressive.model.LinkCheck
import com.downloadhub.app.ui.expressive.model.NoteLevel
import com.downloadhub.app.ui.expressive.model.checkLink
import com.downloadhub.app.ui.expressive.model.interpretProbe
import com.downloadhub.app.ui.expressive.model.isRemovableTree
import com.downloadhub.app.ui.expressive.shape.Loader
import com.downloadhub.core.BatchLinks
import com.downloadhub.core.HttpRequestOptions
import kotlinx.coroutines.delay

private val TORRENT_MIME_TYPES = arrayOf("application/x-bittorrent", "application/vnd.torrent", "application/octet-stream")

/** How long typing must pause before the link is looked at over the network. */
private const val PROBE_DELAY_MS = 700L

/**
 * The add sheet. What the field holds is classified as it changes (empty, invalid, already
 * in the list, a batch, YouTube, a torrent, a plain link) and a plain link is looked at over
 * the network once typing pauses, so problems show before anything is queued: a missing file,
 * a page instead of a file, no room, a card that cannot hold it. Notes advise and never block.
 *
 * A link on the clipboard is offered, never added by itself.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSheet(
    seed: EditorSeed,
    existing: List<DownloadEntity>,
    allowTorrentFile: Boolean,
    scanning: Boolean,
    online: Boolean,
    destinationTreeUri: String?,
    freeBytes: () -> Long,
    onDismiss: () -> Unit,
    onAdd: (
        rawLink: String,
        fileName: String?,
        category: DownloadCategory?,
        sourceOverride: DownloadSource?,
        userAgent: String?,
        contentDisposition: String?,
        quality: MediaQuality,
        audioFormat: AudioFormat,
        streamFormatId: String?,
        streamAudioFormatId: String?,
        startPaused: Boolean,
        queueId: String?
    ) -> Unit,
    onOpenYouTubeTab: (String) -> Unit,
    onPickTorrent: (Uri) -> Unit,
    onScanPage: (String) -> Unit,
    onRequestOptions: (HttpRequestOptions) -> Unit,
    onAddBatch: (List<String>, Boolean, String?) -> Unit,
    queues: List<AppQueue>,
    onOpenExisting: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var link by rememberSaveable(seed.link) { mutableStateOf(seed.link) }
    var fileName by rememberSaveable(seed.fileName) { mutableStateOf(seed.fileName.orEmpty()) }
    var request by remember(seed.link) { mutableStateOf(HttpRequestOptions()) }
    // The link is only looked at over the network once the user has changed it themselves.
    // One that arrived from a share or an intent is never probed on its own.
    var edited by remember(seed.link) { mutableStateOf(false) }
    // The user asking for the look counts as acting on the link, so a shared link can be checked on request.
    var checkRequested by remember(seed.link) { mutableStateOf(false) }
    var showRequest by remember(seed.link) { mutableStateOf(false) }
    var category by remember(seed.source) { mutableStateOf(seed.category) }

    val batch = remember(link) { BatchLinks.expand(link) }.takeIf { it.size > 1 }
    val check = remember(link, existing, batch) { checkLink(link, existing, batch?.size ?: 1, BatchLinks.MAX) }
    val source = (check as? LinkCheck.Http)?.let { DownloadSource.HTTP }
        ?: if (check is LinkCheck.Torrent) DownloadSource.TORRENT else if (check is LinkCheck.YouTube) DownloadSource.YOUTUBE else seed.source

    // The link on the clipboard when the sheet opened, offered if it is new to the field.
    val clipboardAtOpen = remember {
        runCatching {
            context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()?.let { LinkParser.extractFirstLink(it) }
    }
    val clipboardLink = clipboardAtOpen?.takeIf { link.isBlank() || !link.contains(it) }

    // Look at a plain link once typing has paused. Cancelled and restarted on every edit.
    var notes by remember { mutableStateOf<List<AddNote>?>(null) }
    var probing by remember { mutableStateOf(false) }
    val probeable = (edited || checkRequested) && (check is LinkCheck.Http || check is LinkCheck.Duplicate)
    LaunchedEffect(link, probeable, online) {
        notes = null
        if (!probeable) { probing = false; return@LaunchedEffect }
        delay(PROBE_DELAY_MS)
        if (!online) { notes = interpretProbe(null, link, -1, false); return@LaunchedEffect }
        probing = true
        val target = LinkParser.extractFirstLink(link) ?: link.trim()
        // No cookies or login are sent: those are for the real download (see LinkProbe).
        val probe = LinkProbe.probe(target)
        notes = interpretProbe(probe, target, freeBytes(), isRemovableTree(destinationTreeUri))
        probing = false
    }

    fun submit(options: AddOptions = AddOptions()) {
        if (!check.canSubmit) return
        when {
            batch != null -> onAddBatch(batch, options.startPaused, options.queueId)
            else -> {
                if (!request.isEmpty) onRequestOptions(request)
                onAdd(link, fileName.takeIf { it.isNotBlank() }, category, source, seed.userAgent, seed.contentDisposition, seed.quality, seed.audioFormat, null, null, options.startPaused, options.queueId)
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onPickTorrent) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.navigationBarsPadding(), dragHandle = { com.downloadhub.app.ui.expressive.component.SheetHandle() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Add download", style = MaterialTheme.typography.headlineSmallEmphasized)

            clipboardLink?.let { suggestion ->
                Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Link on your clipboard", style = MaterialTheme.typography.titleSmallEmphasized)
                            Text(suggestion, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        TextButton({ edited = true; link = suggestion }) { Text("Use") }
                    }
                }
            }

            val invalid = check as? LinkCheck.Invalid
            TextField(
                value = link,
                onValueChange = { edited = true; link = it },
                modifier = Modifier.fillMaxWidth(),
                shape = TextFieldDefaults.roundedShape,
                colors = TextFieldDefaults.tonalColors(),
                label = { Text(if (source == DownloadSource.TORRENT) "Magnet or .torrent link" else "Link") },
                isError = invalid != null,
                maxLines = 5,
                supportingText = {
                    Text(invalid?.reason ?: "One link, several (one per line), or a range like file[01-20].jpg")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                trailingIcon = {
                    if (probing) Loader(Modifier.size(32.dp))
                    else IconButton({
                        val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) { edited = true; link = text }
                    }) { Icon(Lucide.ClipboardPaste, contentDescription = "Paste link") }
                }
            )

            if (allowTorrentFile || source == DownloadSource.TORRENT) {
                OutlinedButton({ filePicker.launch(TORRENT_MIME_TYPES) }, Modifier.fillMaxWidth()) {
                    Icon(Lucide.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Choose .torrent file")
                }
            }

            when (check) {
                is LinkCheck.Duplicate -> NoteCard(
                    AddNote(NoteLevel.WARN, "Already in your list", "${check.name} is ${check.percent}% done."),
                    actions = {
                        TextButton({ onOpenExisting(check.existingId) }) { Text("Open it") }
                    }
                )
                is LinkCheck.Batch -> NoteCard(
                    AddNote(
                        NoteLevel.INFO,
                        "${check.count} links will be added",
                        if (check.capped) "That is the most one batch takes. Add the rest in another batch." else (batch?.take(2)?.joinToString("\n") ?: "")
                    )
                )
                is LinkCheck.YouTube -> {
                    NoteCard(AddNote(NoteLevel.INFO, "A YouTube link", "The YouTube tab lists every quality and format, and handles whole playlists."))
                    Button({ onOpenYouTubeTab(link.trim()) }, Modifier.fillMaxWidth()) { Text("Open in YouTube tab") }
                }
                else -> Unit
            }
            notes?.forEach { NoteCard(it) }
            if (!edited && !checkRequested && (check is LinkCheck.Http || check is LinkCheck.Duplicate)) {
                TextButton({ checkRequested = true }, Modifier.fillMaxWidth()) { Text("Check this link") }
            }

            if (batch == null && (check is LinkCheck.Http || check is LinkCheck.Duplicate)) {
                TextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = TextFieldDefaults.roundedShape,
                    colors = TextFieldDefaults.tonalColors(),
                    label = { Text("File name (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() })
                )
                if (link.trim().startsWith("http", ignoreCase = true)) {
                    OutlinedButton({ onScanPage(link) }, Modifier.fillMaxWidth(), enabled = !scanning) {
                        if (scanning) { Loader(Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)); Text("Checking the page") }
                        else { Icon(Lucide.ScanSearch, null); Spacer(Modifier.width(8.dp)); Text("Check page for media") }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Category", style = MaterialTheme.typography.titleSmallEmphasized)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DownloadCategory.entries.filterNot { it == DownloadCategory.ARCHIVE }.forEach { option ->
                            FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option.label) })
                        }
                    }
                }
                if (showRequest) {
                    RequestFields(initial = request, actionLabel = null, onAction = {}, onChange = { request = it })
                } else {
                    TextButton({ showRequest = true }) { Text("Headers, cookies and login") }
                }
            }

            // Plain links and batches can be added paused or to another queue. A torrent goes
            // through its own preview first, which has its own choices, so it keeps one button.
            if (check is LinkCheck.Http || check is LinkCheck.Duplicate || check is LinkCheck.Batch) {
                AddSplitButton(
                    label = when {
                        check is LinkCheck.Duplicate -> "Download again"
                        else -> primaryAddLabel(batch?.size ?: 1, AddOptions(), queues)
                    },
                    enabled = check.canSubmit,
                    choices = addChoices(queues),
                    onAdd = { submit() },
                    onChoose = { submit(it.options) }
                )
            } else {
                Button({ submit() }, Modifier.fillMaxWidth(), enabled = check.canSubmit) {
                    Text(if (check is LinkCheck.Torrent) "Add torrent" else "Add to queue")
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: AddNote, actions: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (note.level) {
        NoteLevel.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        NoteLevel.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoteLevel.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleSmallEmphasized)
            if (note.body.isNotEmpty()) Text(note.body, style = MaterialTheme.typography.bodyMedium)
            if (actions != null) FlowRow { actions() }
        }
    }
}
