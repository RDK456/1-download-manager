package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.PeerRow
import com.downloadhub.core.TrackerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Re-reads [read] every 1.5 s off the UI thread for as long as the tab is open. */
@Composable
private fun <T> rememberPolled(id: String, read: (String) -> List<T>): List<T> {
    var rows by remember(id) { mutableStateOf<List<T>>(emptyList()) }
    LaunchedEffect(id) {
        while (true) {
            rows = withContext(Dispatchers.IO) { runCatching { read(id) }.getOrDefault(emptyList()) }
            delay(1_500)
        }
    }
    return rows
}

/** qBittorrent's Trackers tab: each tracker, its tier, its state and its last message. */
@Composable
internal fun TrackersTab(id: String, read: (String) -> List<TrackerRow>, onAdd: (List<String>) -> Unit) {
    val rows = rememberPolled(id, read)
    var adding by remember(id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = adding,
                onValueChange = { adding = it },
                singleLine = true,
                placeholder = { Text("Add trackers: paste one or more announce URLs", fontSize = 11.sp) },
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = adding.isNotBlank(),
                onClick = {
                    onAdd(adding.split(' ', '\n', ',').filter { it.contains("://") })
                    adding = ""
                }
            ) { Text("Add") }
        }
        Spacer(Modifier.padding(top = 4.dp))
        TableHeader(listOf("Tier" to 44.dp, "URL" to 0.dp, "Status" to 120.dp, "Message" to 220.dp))
        if (rows.isEmpty()) {
            Note("No trackers yet. A magnet with none relies on DHT; a paused torrent shows its trackers once it starts.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.url }) { row ->
                    TableRow(
                        listOf("${row.tier}" to 44.dp, row.url to 0.dp, row.status to 120.dp, row.message to 220.dp),
                        statusColour = if (row.status == "Working") AppTheme.success else null
                    )
                }
            }
        }
    }
}

/** qBittorrent's Peers tab: who it is connected to and how fast each is going. */
@Composable
internal fun PeersTab(id: String, read: (String) -> List<PeerRow>) {
    val rows = rememberPolled(id, read)
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        TableHeader(
            listOf(
                "Address" to 0.dp, "Client" to 150.dp, "Progress" to 70.dp, "Down" to 80.dp,
                "Up" to 80.dp, "Downloaded" to 90.dp, "Uploaded" to 90.dp
            )
        )
        if (rows.isEmpty()) {
            Note("No peers connected right now.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.address }) { peer ->
                    TableRow(
                        listOf(
                            peer.address to 0.dp,
                            peer.client.ifBlank { "Unknown" } to 150.dp,
                            "${peer.progressPercent}%" to 70.dp,
                            DisplayFormat.speed(peer.downloadRate) to 80.dp,
                            DisplayFormat.speed(peer.uploadRate) to 80.dp,
                            DisplayFormat.bytes(peer.downloaded) to 90.dp,
                            DisplayFormat.bytes(peer.uploaded) to 90.dp
                        )
                    )
                }
            }
        }
    }
}

/** A width of 0.dp means "whatever is left". */
@Composable
private fun TableHeader(columns: List<Pair<String, Dp>>) {
    Row(Modifier.fillMaxWidth().background(AppTheme.Palette.band).padding(vertical = 5.dp)) {
        columns.forEach { (label, width) ->
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.Palette.muted,
                maxLines = 1,
                modifier = cell(width)
            )
        }
    }
}

/** [statusColour] tints the third column, the tracker status. */
@Composable
private fun TableRow(cells: List<Pair<String, Dp>>, statusColour: Color? = null) {
    Row(Modifier.fillMaxWidth().hoverFill().padding(vertical = 4.dp)) {
        cells.forEachIndexed { index, (text, width) ->
            Text(
                text,
                fontSize = 11.sp,
                color = if (index == 2 && statusColour != null) statusColour else AppTheme.Palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = cell(width)
            )
        }
    }
}

private fun RowScope.cell(width: Dp): Modifier =
    (if (width == 0.dp) Modifier.weight(1f) else Modifier.width(width)).padding(horizontal = 6.dp)

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, color = AppTheme.Palette.muted)
    }
}
