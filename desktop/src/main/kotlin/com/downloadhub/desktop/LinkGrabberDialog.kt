package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.downloadhub.core.BatchLinks
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.HostResolver
import com.downloadhub.core.HttpProbe
import com.downloadhub.core.LinkParser
import com.downloadhub.core.TorrentAddRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the server said about one grabbed link. [failed] is "could not check", not proof it is dead. */
private data class GrabInfo(val name: String?, val size: Long?, val failed: Boolean)

/**
 * JDownloader's LinkGrabber: every link gathered from a paste, a range or the browser lands
 * in one list, is checked against its server in the background (name, size, reachable), and
 * the ticked ones are queued together. Links that arrive while it is open are added to it.
 */
@Composable
fun LinkGrabberDialog(
    links: SnapshotStateList<String>,
    makeRequest: (name: String, url: String, startNow: Boolean, folder: File?) -> TorrentAddRequest,
    onQueue: (TorrentAddRequest) -> Unit,
    onPickDirectory: () -> File?,
    onDone: () -> Unit
) {
    val info = remember { mutableStateMapOf<String, GrabInfo>() }
    val skipped = remember { mutableStateListOf<String>() }
    var folder by remember { mutableStateOf<File?>(null) }
    val clipboard = LocalClipboardManager.current
    // File-host links the host's own check blocked; the user passes that check in a browser window.
    var verifying by remember { mutableStateOf<List<String>?>(null) }
    var recheck by remember { mutableStateOf(0) }
    val blocked = links.filter { HostResolver.needsResolving(it) && info[it]?.failed == true }
    verifying?.let { batch ->
        HostCheckWindow(
            links = batch,
            onResolved = { link -> info.remove(link); recheck++ },
            onClose = { verifying = null }
        )
    }

    // Checked four at a time: a hundred links must not open a hundred connections to one host.
    LaunchedEffect(links.toList(), recheck) {
        val gate = Semaphore(4)
        // File-host page links each cost a page load and a POST to the host, which throttles
        // parallel ones, so they go one at a time and a refusal is tried again.
        val hostGate = Semaphore(1)
        coroutineScope {
            links.filter { it !in info }.forEach { link ->
                launch {
                    (if (HostResolver.needsResolving(link)) hostGate else gate).withPermit {
                        var result = withContext(Dispatchers.IO) { HttpProbe.probe(link) }
                        var attempt = 1
                        while (result.isFailure && HostResolver.needsResolving(link) && attempt < 3) {
                            delay(1_000L * attempt++)
                            result = withContext(Dispatchers.IO) { HttpProbe.probe(link) }
                        }
                        val probe = result.getOrNull()
                        info[link] = GrabInfo(probe?.fileName, probe?.totalBytes, result.isFailure)
                    }
                }
            }
        }
    }

    fun nameOf(link: String) = info[link]?.name ?: LinkParser.fileNameFrom(link)
    val chosen = links.filter { it !in skipped }

    // A set of files that belong together (the parts of one release) goes in a folder of its
    // own, named after what they share, as a torrent's content folder is. The name follows the
    // list until the user types one.
    var ownFolder by remember { mutableStateOf(true) }
    var subfolder by remember { mutableStateOf("") }
    var subfolderEdited by remember { mutableStateOf(false) }
    val suggested = BatchLinks.commonFolderName(chosen.map { nameOf(it) }).takeIf { chosen.size > 1 }.orEmpty()
    LaunchedEffect(suggested) { if (!subfolderEdited) subfolder = suggested }
    val totalBytes = chosen.sumOf { info[it]?.size ?: 0L }

    fun queue(startNow: Boolean) {
        val sub = LinkParser.sanitizeFileName(subfolder, fallback = "").takeIf { ownFolder && chosen.size > 1 }
        val group = if (chosen.size > 1) java.util.UUID.randomUUID().toString() else ""
        chosen.forEach { link ->
            val request = makeRequest(LinkParser.sanitizeFileName(nameOf(link)), link, startNow, folder).copy(groupId = group)
            onQueue(if (sub.isNullOrBlank()) request else request.copy(saveDirectory = File(request.saveDirectory, sub)))
        }
        onDone()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${chosen.size} of ${links.size} selected", fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (blocked.isNotEmpty()) TextButton(onClick = { verifying = blocked }) { Text("Verify ${blocked.size} in browser") }
            TextButton(onClick = { skipped.clear() }) { Text("All") }
            TextButton(onClick = { skipped.addAll(links.filter { it !in skipped }) }) { Text("None") }
            TextButton(onClick = { skipped.addAll(links.filter { info[it]?.failed == true && it !in skipped }) }) { Text("Untick unreachable") }
            TextButton(onClick = {
                clipboard.getText()?.text?.let { text ->
                    links.addAll(BatchLinks.expand(text).filter { it !in links })
                }
            }) { Text("Paste links") }
        }
        if (chosen.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ownFolder, onCheckedChange = { ownFolder = it })
                Text("Save all in a folder", fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = subfolder,
                    onValueChange = { subfolder = it; subfolderEdited = true },
                    enabled = ownFolder,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${chosen.size} files" + if (totalBytes > 0) " · ${DisplayFormat.bytes(totalBytes)}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(links.toList(), key = { it }) { link ->
                val data = info[link]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = link !in skipped, onCheckedChange = { if (it) skipped.remove(link) else skipped.add(link) })
                    Column(Modifier.weight(1f)) {
                        Text(nameOf(link), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            link, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            data == null -> "checking…"
                            data.failed -> "unreachable"
                            data.size != null -> DisplayFormat.bytes(data.size)
                            else -> "size unknown"
                        },
                        fontSize = 12.sp,
                        color = if (data?.failed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { links.remove(link) }) { Icon(Lucide.X, "Remove", Modifier.size(16.dp)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onPickDirectory()?.let { folder = it } }) {
                Text(folder?.name ?: "Folder by file type", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (folder != null) TextButton(onClick = { folder = null }) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onDone) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { queue(false) }, enabled = chosen.isNotEmpty()) { Text("Add") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { queue(true) }, enabled = chosen.isNotEmpty()) { Text("Download ${chosen.size}") }
        }
    }
}
