package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.CategoryRule
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.HttpProbe
import com.downloadhub.core.HttpRequestOptions
import com.downloadhub.core.LinkParser
import com.downloadhub.core.SaveCategories
import com.downloadhub.core.TorrentAddRequest
import com.downloadhub.core.TorrentMetainfo
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Add Download window for an ordinary link, laid out like AB Download Manager's: the
 * link, a category that picks the folder, the folder, the file name, and the size read
 * from the server before anything is downloaded. Add queues it stopped; Download starts it.
 *
 * Torrents keep the larger window with the file list; a plain link has no files to choose,
 * so it gets the short one.
 */
@Composable
fun QuickAddDialog(
    pending: PendingDownload,
    root: File,
    rules: List<CategoryRule>,
    onPickDirectory: () -> File?,
    /** Called with true when the request options open, so the window can grow to fit them. */
    onExpand: (Boolean) -> Unit,
    onConfirm: (TorrentAddRequest) -> Unit,
    onDismiss: () -> Unit
) {
    val categories = remember(root, rules) { SaveCategories.all(root, rules) }
    var url by remember { mutableStateOf(pending.link) }
    var fileName by remember { mutableStateOf(pending.name) }
    var category by remember { mutableStateOf(SaveCategories.forFile(pending.name, root, rules)) }
    var useCategory by remember { mutableStateOf(true) }
    var folder by remember { mutableStateOf(category.folder.path) }
    // What the user has touched is theirs: the probe fills in only what they have not.
    var nameEdited by remember { mutableStateOf(false) }
    var categoryPicked by remember { mutableStateOf(false) }
    var folderEdited by remember { mutableStateOf(false) }

    var size by remember { mutableStateOf<Long?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeFailed by remember { mutableStateOf<String?>(null) }
    var probeAttempt by remember { mutableStateOf(0) }

    var showOptions by remember { mutableStateOf(false) }
    var headersText by remember { mutableStateOf("") }
    var cookies by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var typingHeaders by remember { mutableStateOf(false) }

    fun applyCategory(choice: com.downloadhub.core.SaveCategory) {
        category = choice
        if (useCategory && !folderEdited) folder = choice.folder.path
    }

    LaunchedEffect(url, probeAttempt) {
        if (!url.startsWith("http", ignoreCase = true)) return@LaunchedEffect
        delay(350) // typing in the link box should not fire a request per keystroke
        probing = true
        probeFailed = null
        val headers = HttpRequestOptions.parseHeaders(headersText) +
            listOfNotNull(cookies.trim().takeIf { it.isNotEmpty() }?.let { "Cookie" to it })
        val result = withContext(Dispatchers.IO) { HttpProbe.probe(url.trim(), headers) }
        probing = false
        result.onSuccess { probe ->
            size = probe.totalBytes
            val name = probe.fileName
            if (!nameEdited && name != null && name != fileName) {
                fileName = name
                if (!categoryPicked) applyCategory(SaveCategories.forFile(name, root, rules))
            }
        }.onFailure { probeFailed = it.message ?: "Could not reach the server" }
    }

    val canSave = url.isNotBlank() && fileName.isNotBlank() && folder.isNotBlank()
    fun confirm(startNow: Boolean) {
        if (!canSave) return
        onConfirm(
            TorrentAddRequest(
                metainfo = TorrentMetainfo(
                    name = LinkParser.sanitizeFileName(fileName.trim()),
                    files = emptyList(),
                    comment = "",
                    createdAtEpochMillis = 0L,
                    createdBy = "",
                    infoHashV1 = "",
                    infoHashV2 = "",
                    isSingleFile = true
                ),
                saveDirectory = File(folder.trim()),
                startImmediately = startNow,
                link = url.trim(),
                http = HttpRequestOptions(
                    headers = HttpRequestOptions.parseHeaders(headersText),
                    cookies = cookies.trim(),
                    username = username.trim(),
                    password = password
                )
            )
        )
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onEnter(canSave && !typingHeaders) { confirm(startNow = true) }
            .focusRequester(rootFocus)
            .focusable()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            label = { Text("Link", fontSize = 12.sp) },
            trailingIcon = {
                TextButton(onClick = { clipboardLink()?.let { url = it } }) { Text("Paste", fontSize = 11.sp) }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            TickBox(useCategory, onChange = { on ->
                useCategory = on
                if (!folderEdited) folder = if (on) category.folder.path else root.path
            }, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text("Use category", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                var open by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { open = true },
                    enabled = useCategory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(category.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    categories.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.name) },
                            onClick = {
                                open = false
                                categoryPicked = true
                                folderEdited = false
                                applyCategory(choice)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            // The size, read from the server: what AB shows beside the category.
            Box(Modifier.width(110.dp), contentAlignment = Alignment.CenterEnd) {
                when {
                    probing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    size != null -> Text(DisplayFormat.bytes(size!!), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    probeFailed != null -> Text("Size unknown", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                    else -> Text("Size unknown", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it; folderEdited = true },
                singleLine = true,
                label = { Text("Save to", fontSize = 12.sp) },
                trailingIcon = {
                    IconButton(onClick = {
                        onPickDirectory()?.let { folder = it.absolutePath; folderEdited = true }
                    }) { Icon(DlmIcons.Folder, "Choose folder", Modifier.size(18.dp)) }
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { probeAttempt++ }) { Icon(Icons.Default.Refresh, "Read the size again") }
            IconButton(onClick = { showOptions = !showOptions; onExpand(showOptions) }) {
                Icon(
                    Icons.Default.Settings,
                    "Headers, cookies and login",
                    tint = if (showOptions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it; nameEdited = true },
            singleLine = true,
            label = { Text("File name", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth()
        )

        if (showOptions) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged { typingHeaders = it.hasFocus }
            ) {
                // Scrolls by itself; the weighted box gives it a bounded height to scroll in.
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
            Spacer(Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { confirm(startNow = false) }, enabled = canSave) { Text("Add") }
            Spacer(Modifier.width(10.dp))
            Button(onClick = { confirm(startNow = true) }, enabled = canSave) { Text("Download") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}
