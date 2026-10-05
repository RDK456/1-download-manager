package com.downloadhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.PeerRow
import com.downloadhub.core.TrackerRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * qBittorrent's per-torrent extras on the phone: force recheck and reannounce, and the
 * Trackers and Peers lists, refreshed every couple of seconds while the sheet is open.
 */
@Composable
internal fun TorrentExtras(
    id: String,
    trackersOf: () -> List<TrackerRow>,
    peersOf: () -> List<PeerRow>,
    onForceRecheck: () -> Unit,
    onForceReannounce: () -> Unit
) {
    var showPeers by remember(id) { mutableStateOf(false) }
    var trackers by remember(id) { mutableStateOf(emptyList<TrackerRow>()) }
    var peers by remember(id) { mutableStateOf(emptyList<PeerRow>()) }
    LaunchedEffect(id) {
        while (true) {
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
            FilterChip(selected = !showPeers, onClick = { showPeers = false }, label = { Text("Trackers (${trackers.size})") })
            FilterChip(selected = showPeers, onClick = { showPeers = true }, label = { Text("Peers (${peers.size})") })
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        if (showPeers) {
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
