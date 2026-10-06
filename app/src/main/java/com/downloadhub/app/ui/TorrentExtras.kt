package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Folder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.core.ContentNode
import com.downloadhub.core.ContentRow
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.PeerRow
import com.downloadhub.core.TorrentMetainfo
import com.downloadhub.core.TrackerRow
import com.downloadhub.core.contentTree
import com.downloadhub.core.visibleContentNodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A torrent's files, bytes done per file (by index), and the indices being skipped. */
class TorrentContentView(val meta: TorrentMetainfo, val done: LongArray, val skipped: Set<Int>)

/**
 * qBittorrent's per-torrent extras on the phone: force recheck and reannounce, and the
 * Content, Trackers and Peers lists, refreshed every couple of seconds while the sheet is open.
 */
@Composable
internal fun TorrentExtras(
    id: String,
    contentOf: () -> TorrentContentView?,
    trackersOf: () -> List<TrackerRow>,
    peersOf: () -> List<PeerRow>,
    onFilesWanted: (List<Int>, Boolean) -> Unit,
    onForceRecheck: () -> Unit,
    onForceReannounce: () -> Unit
) {
    var tab by remember(id) { mutableStateOf(0) }
    // The newest lambda, which reads the newest row - the loop below outlives recompositions.
    val latestContentOf by rememberUpdatedState(contentOf)
    var content by remember(id) { mutableStateOf<TorrentContentView?>(null) }
    var trackers by remember(id) { mutableStateOf(emptyList<TrackerRow>()) }
    var peers by remember(id) { mutableStateOf(emptyList<PeerRow>()) }
    LaunchedEffect(id) {
        while (true) {
            content = withContext(Dispatchers.IO) { latestContentOf() }
            trackers = withContext(Dispatchers.IO) { trackersOf() }
            peers = withContext(Dispatchers.IO) { peersOf() }
            delay(2_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onForceRecheck) { Text("Force recheck") }
            OutlinedButton(onClick = onForceReannounce) { Text("Force reannounce") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Content (${content?.meta?.files?.size ?: 0})") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Trackers (${trackers.size})") })
            FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Peers (${peers.size})") })
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        if (tab == 0) {
            ContentList(id, content, onFilesWanted)
        } else if (tab == 2) {
            if (peers.isEmpty()) Text("No peers connected right now.", style = MaterialTheme.typography.bodySmall, color = muted)
            peers.take(MAX_ROWS).forEach { peer ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${peer.address}  ·  ${peer.client.ifBlank { "Unknown" }}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${peer.progressPercent}%  ·  ↓ ${DisplayFormat.speed(peer.downloadRate)}  ·  ↑ ${DisplayFormat.speed(peer.uploadRate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted
                    )
                }
            }
        } else {
            if (trackers.isEmpty()) {
                Text(
                    "No trackers yet. A paused torrent shows its trackers once it is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }
            trackers.take(MAX_ROWS).forEach { tracker ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(tracker.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Tier ${tracker.tier}  ·  ${tracker.status}" + if (tracker.message.isNotBlank()) "  ·  ${tracker.message}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tracker.status == "Working") MaterialTheme.colorScheme.primary else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** A sheet is not a table; past this many rows the list stops being readable on a phone. */
private const val MAX_ROWS = 30

/**
 * The Content tab: the torrent's folders and files as a tree, each with its progress and a
 * tick box that downloads or skips it, like the desktop's Content tab.
 */
@Composable
private fun ContentList(id: String, content: TorrentContentView?, onFilesWanted: (List<Int>, Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (content == null || content.meta.files.isEmpty()) {
        Text("The file list appears once the torrent has its metadata.", style = MaterialTheme.typography.bodySmall, color = muted)
        return
    }
    val tree = remember(content.meta) {
        contentTree(content.meta.files.map { ContentRow(it.path, it.index, it.size) })
    }
    // A release that is one folder opens on its files; several top-level folders start closed.
    var expanded by remember(id, content.meta) {
        mutableStateOf(tree.filterIsInstance<ContentNode.Folder>().takeIf { it.size == 1 }?.map { it.fullPath }?.toSet().orEmpty())
    }
    // A tick shows at once; the service writes it and the next refresh confirms it, after
    // which the local copy is dropped.
    var overrides by remember(id) { mutableStateOf(mapOf<Int, Boolean>()) }
    LaunchedEffect(content) {
        overrides = overrides.filterNot { (index, wanted) -> (index !in content.skipped) == wanted }
    }
    val skipped = content.skipped - overrides.filterValues { it }.keys + overrides.filterValues { !it }.keys
    val setWanted: (List<Int>, Boolean) -> Unit = { indices, wanted ->
        overrides = overrides + indices.associateWith { wanted }
        onFilesWanted(indices, wanted)
    }
    val rows = visibleContentNodes(tree, expanded)
    rows.take(MAX_CONTENT_ROWS).forEach { (node, depth) ->
        val indices = when (node) {
            is ContentNode.Folder -> node.fileIndices
            is ContentNode.File -> listOf(node.index)
        }
        val size = indices.sumOf { content.meta.files.getOrNull(it)?.size ?: 0L }
        val done = indices.sumOf { content.done.getOrNull(it) ?: 0L }
        val wanted = indices.count { it !in skipped }
        val state = when (wanted) {
            0 -> ToggleableState.Off
            indices.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = node is ContentNode.Folder) {
                    expanded = if (node.fullPath in expanded) expanded - node.fullPath else expanded + node.fullPath
                }
                .padding(start = (depth * 14).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TriStateCheckbox(state = state, onClick = { setWanted(indices, state != ToggleableState.On) })
            if (node is ContentNode.Folder) {
                Icon(if (node.fullPath in expanded) Lucide.ChevronDown else Lucide.ChevronRight, null, Modifier.size(18.dp))
                Icon(Lucide.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(Modifier.width(18.dp))
                Icon(Lucide.File, null, Modifier.size(18.dp), tint = muted)
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(node.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val fraction = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    if (state == ToggleableState.Off) "Skipped  ·  ${DisplayFormat.bytes(size)}"
                    else "${(fraction * 100).toInt()}%  ·  ${DisplayFormat.bytes(done)} of ${DisplayFormat.bytes(size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted
                )
            }
        }
    }
    if (rows.size > MAX_CONTENT_ROWS) {
        Text("${rows.size - MAX_CONTENT_ROWS} more - close a folder to see the rest.", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

/** Past this many rows a sheet stops being usable; folders can be closed to see the rest. */
private const val MAX_CONTENT_ROWS = 200
