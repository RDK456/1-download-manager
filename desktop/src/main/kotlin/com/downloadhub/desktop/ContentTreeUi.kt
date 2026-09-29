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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import java.util.Locale

/**
 * The torrent's file list, as a tree.
 *
 * The shape the reference clients use, and the only one that reads: folders that can be
 * opened, files indented inside them, and a size against every line. A release torrent
 * names its files identically and differs only at the end, so a flat list of those names
 * is thirteen rows of the same sentence - which is what this replaced.
 *
 * Columns are the ones asked for: name, total size, progress, download priority, and
 * what is still to come. Priority is a fixed "Normal" rather than an editable column,
 * because choosing a priority per file is not in this build and an editable column that
 * quietly does nothing is worse than showing the one thing that is true.
 */
@Composable
fun ContentTreeList(
    rows: List<ContentRow>,
    /**
     * Hoisted, not local. The dialog turns this into the request's file selection, so it
     * has to be the same value the boxes are drawing - a list that kept its own copy
     * would show ticks that the Add button then ignored.
     */
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    /** Used by the detail pane, which reports the selection rather than editing it. */
    readOnly: Boolean = false
) {
    // Which folders are open. Nothing starts open, because a release with four hundred
    // files is not readable all at once - but the folders are one click away and each
    // says how much is in it.
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    // The filter is applied before the tree is built, so a filter matching one file
    // inside a folder still shows the folder - with only that file in it - rather than
    // hiding the file behind a folder the filter had no way to open.
    val nodes = remember(rows, filter) {
        val needle = filter.trim().lowercase(Locale.US)
        val kept = if (needle.isEmpty()) {
            rows
        } else {
            rows.filter { it.path.lowercase(Locale.US).contains(needle) }
        }
        contentTree(kept)
    }
    val shown = remember(nodes, expanded) { visibleContentNodes(nodes, expanded) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!readOnly) {
                TreeButton("Select All", onSelectAll)
                TreeButton("Select None", onSelectNone)
            }
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                label = { Text("Filter files", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.width(170.dp)
            )
            if (readOnly) {
                Text(
                    "as chosen when it was added",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            TreeHeader("Name", Modifier.weight(1f))
            TreeHeader("Total Size", Modifier.width(84.dp))
            TreeHeader("Progress", Modifier.width(58.dp))
            TreeHeader("Priority", Modifier.width(62.dp))
            TreeHeader("Remaining", Modifier.width(96.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (shown.isEmpty()) {
                Text(
                    if (rows.isEmpty()) "This torrent lists no files." else "No files match that filter.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            } else {
                LazyColumn {
                    // Keyed on the full path. In a tree that is unique even when two files
                    // share a name, which is the whole reason the flat list's key - the
                    // file index - no longer works here: a folder has no index at all.
                    items(shown, key = { (node, _) -> node.fullPath }) { (node, depth) ->
                        when (node) {
                            is ContentNode.Folder -> TreeFolderRow(
                                node = node,
                                depth = depth,
                                selected = selected,
                                open = node.fullPath in expanded,
                                readOnly = readOnly,
                                onOpen = {
                                    expanded = if (node.fullPath in expanded) {
                                        expanded - node.fullPath
                                    } else {
                                        expanded + node.fullPath
                                    }
                                },
                                onToggle = {
                                    // A folder's tick is all-or-nothing across everything
                                    // beneath it, because half a folder is not a thing
                                    // anyone means to ask for.
                                    val allIn = node.fileIndices.all { it in selected }
                                    onSelectionChange(
                                        if (allIn) {
                                            selected - node.fileIndices.toSet()
                                        } else {
                                            selected + node.fileIndices
                                        }
                                    )
                                }
                            )

                            is ContentNode.File -> TreeFileRow(
                                node = node,
                                depth = depth,
                                selected = node.index in selected,
                                readOnly = readOnly,
                                onToggle = {
                                    onSelectionChange(
                                        if (node.index in selected) {
                                            selected - node.index
                                        } else {
                                            selected + node.index
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One folder, with its own tick, its own size, and its own open/closed triangle. */
@Composable
private fun TreeFolderRow(
    node: ContentNode.Folder,
    depth: Int,
    selected: Set<Int>,
    open: Boolean,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (readOnly) Modifier else Modifier.clickable { onToggle() })
            // 14 dp a level, so a file three folders down is visibly inside them rather
            // than merely listed after them.
            .padding(start = (4 + depth * 14).dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (readOnly) {
            Spacer(Modifier.width(13.dp))
        } else {
            TickBox(checked = node.isFullySelected(selected), onChange = { onToggle() }, size = 12.dp)
        }
        // Its own small target, and it does not toggle the tick: opening a folder and
        // downloading it are different questions.
        Box(
            Modifier
                .width(15.dp)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (open) "▾" else "▸",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            node.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        Text(
            "${node.fileIndices.size}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text("", fontSize = 11.sp, modifier = Modifier.width(58.dp))
        Text("", fontSize = 11.sp, modifier = Modifier.width(62.dp))
        Text(
            DisplayFormat.bytes(node.totalSize),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
    }
}

/** One file, indented inside whatever folders contain it. */
@Composable
private fun TreeFileRow(
    node: ContentNode.File,
    depth: Int,
    selected: Boolean,
    readOnly: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (readOnly) Modifier else Modifier.clickable { onToggle() })
            .padding(start = (19 + depth * 14).dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (readOnly) {
            Spacer(Modifier.width(13.dp))
        } else {
            TickBox(checked = selected, onChange = { onToggle() }, size = 12.dp)
        }
        Spacer(Modifier.width(5.dp))
        Text(
            node.label,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        Text(
            if (node.size > 0) DisplayFormat.bytes(node.size) else "-",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            "0%",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp)
        )
        Text(
            "Normal",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(62.dp)
        )
        Text(
            if (node.size > 0) "${DisplayFormat.bytes(node.size)}  100%" else "-",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
    }
}

@Composable
private fun TreeHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun TreeButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
