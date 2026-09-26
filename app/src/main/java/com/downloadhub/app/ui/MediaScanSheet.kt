package com.downloadhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.download.MediaCandidate
import com.downloadhub.app.download.MediaKind
import com.downloadhub.app.download.PageScanState

/**
 * Shows what a pasted page offers: direct video, audio and file links, plus any
 * embedded players. Each row queues that item; "Download all" queues everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScanSheet(
    state: PageScanState,
    onPick: (MediaCandidate) -> Unit,
    onPickAll: () -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (state) {
                is PageScanState.Found -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Media on this page", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "${state.items.size} item(s) found",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (state.items.size > 1) {
                            Button(onClick = onPickAll) { Text("Download all") }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.items, key = { it.url }) { candidate ->
                            MediaRow(candidate = candidate, onClick = { onPick(candidate) })
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
                is PageScanState.Scanning -> {
                    Text("Checking the page", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Looking for video, audio and files...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is PageScanState.Failed -> {
                    Text("Nothing to download here", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRescan) { Text("Try again") }
                        Button(onClick = onDismiss) { Text("Close") }
                    }
                }
                else -> {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun MediaRow(candidate: MediaCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .padding(end = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconFor(candidate.kind),
                contentDescription = null,
                tint = colorFor(candidate.kind),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                candidate.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                kindLabel(candidate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.OpenInNew,
            contentDescription = "Download",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun kindLabel(candidate: MediaCandidate): String {
    val host = candidate.url.substringAfter("//", "").substringBefore('/')
    return when (candidate.kind) {
        MediaKind.VIDEO -> listOf("Video", candidate.extension.takeIf { it.isNotEmpty() }, host)
        MediaKind.AUDIO -> listOf("Audio", candidate.extension.takeIf { it.isNotEmpty() }, host)
        MediaKind.PLAYER -> listOf("Embedded player", host)
        MediaKind.FILE -> listOf("File", candidate.extension.takeIf { it.isNotEmpty() }, host)
    }.filterNot { it.isNullOrBlank() }.joinToString(" - ")
}

private fun iconFor(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.VIDEO -> Icons.Default.Movie
    MediaKind.AUDIO -> Icons.Default.AudioFile
    MediaKind.PLAYER -> Icons.Default.PlayCircle
    MediaKind.FILE -> Icons.Default.InsertDriveFile
}

private fun colorFor(kind: MediaKind): Color = when (kind) {
    MediaKind.VIDEO -> Color(0xFF7C3AED)
    MediaKind.AUDIO -> Color(0xFF00796B)
    MediaKind.PLAYER -> Color(0xFFB45309)
    MediaKind.FILE -> Color(0xFF1769E0)
}
