package com.downloadhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.core.ContentNode
import com.downloadhub.core.ContentRow
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.TorrentMetainfo
import com.downloadhub.core.TorrentSelection
import com.downloadhub.core.contentTree
import com.downloadhub.core.visibleContentNodes

/** A torrent being looked at before it is queued. [metainfo] is null until a magnet's list arrives. */
data class TorrentPreview(
    val link: String,
    val torrentFile: String?,
    val name: String,
    val metainfo: TorrentMetainfo?,
    val loading: Boolean
)

/**
 * The phone's pre-download dialog for a torrent: its folders and files as a tree, each with
 * a tick box, so only the wanted part of a release is fetched. Same tree as the desktop's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentPreviewSheet(
    preview: TorrentPreview,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {}
) {
    val meta = preview.metainfo
    val tree = remember(meta) { meta?.let { m -> contentTree(m.files.map { ContentRow(it.path, it.index, it.size) }) }.orEmpty() }
    var selected by remember(meta) { mutableStateOf(meta?.let { TorrentSelection.allSelected(it) }.orEmpty()) }
    // Top-level folders start open, so a one-folder release shows its files straight away.
    var expanded by remember(meta) {
        mutableStateOf(tree.filterIsInstance<ContentNode.Folder>().map { it.fullPath }.toSet())
    }
    LaunchedEffect(meta) { if (meta != null && selected.isEmpty()) selected = TorrentSelection.allSelected(meta) }
    val hasList = meta != null && meta.files.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add torrent", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(preview.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                preview.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Reading the file list from peers...", style = MaterialTheme.typography.bodySmall)
                }
                !hasList -> Column {
                    Text(
                    "The file list could not be read in time. Try again, or download everything in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (preview.torrentFile == null) TextButton(onClick = onRetry) { Text("Try again") }
                }
                else -> {
                    val total = meta!!.files.sumOf { it.size }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${selected.size} of ${meta.files.size} files · " +
                                "${DisplayFormat.bytes(TorrentSelection.selectedSize(meta, selected))} of ${DisplayFormat.bytes(total)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { selected = TorrentSelection.allSelected(meta) }) { Text("All") }
                        TextButton(onClick = { selected = emptySet() }) { Text("None") }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(visibleContentNodes(tree, expanded), key = { (node, _) -> node.fullPath }) { (node, depth) ->
                            ContentLine(
                                node = node,
                                depth = depth,
                                selected = selected,
                                open = node.fullPath in expanded,
                                onToggleOpen = {
                                    expanded = if (node.fullPath in expanded) expanded - node.fullPath else expanded + node.fullPath
                                },
                                onTick = { indices, on -> selected = if (on) selected + indices else selected - indices.toSet() }
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(selected) },
                    enabled = !hasList || selected.isNotEmpty()
                ) { Text(if (preview.loading) "Download all" else "Download") }
            }
        }
    }
}

@Composable
private fun ContentLine(
    node: ContentNode,
    depth: Int,
    selected: Set<Int>,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onTick: (List<Int>, Boolean) -> Unit
) {
    val indices = when (node) {
        is ContentNode.Folder -> node.fileIndices
        is ContentNode.File -> listOf(node.index)
    }
    val ticked = indices.count { it in selected }
    val state = when (ticked) {
        0 -> ToggleableState.Off
        indices.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (node is ContentNode.Folder) onToggleOpen() else onTick(indices, state != ToggleableState.On) }
            .padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TriStateCheckbox(state = state, onClick = { onTick(indices, state != ToggleableState.On) })
        if (node is ContentNode.Folder) {
            Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp))
            Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.width(18.dp))
            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            node.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            DisplayFormat.bytes(if (node is ContentNode.Folder) node.totalSize else (node as ContentNode.File).size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
        )
    }
}
