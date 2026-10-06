package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus

/**
 * The strip of numbers above the download list: what is running, how fast it is going
 * each way, what is waiting and what is done - the five things you look at the list to
 * find out, read at a glance. Beside them, the Table / Cards switch and the folder.
 */
@Composable
fun DashboardCards(
    items: List<DownloadItem>,
    cardView: Boolean,
    onCardView: (Boolean) -> Unit,
    onOpenFolder: () -> Unit
) {
    val active = items.count { it.status == DownloadStatus.RUNNING }
    val down = items.filter { it.status == DownloadStatus.RUNNING }.sumOf { it.speedBytesPerSecond }
    val up = items.sumOf { it.uploadRate }
    val queued = items.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
    val done = items.count { it.status == DownloadStatus.COMPLETED }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCard(DlmIcons.ArrowDownward, active.toString(), "Active", AppTheme.Palette.accent, Modifier.weight(1f))
        StatCard(DlmIcons.ArrowDownward, DisplayFormat.speed(down), "Download speed", AppTheme.Palette.accent, Modifier.weight(1f))
        StatCard(DlmIcons.ArrowUpward, DisplayFormat.speed(up), "Upload speed", AppTheme.success, Modifier.weight(1f))
        StatCard(DlmIcons.Pause, queued.toString(), "Queued", AppTheme.Palette.muted, Modifier.weight(1f))
        StatCard(Icons.Default.Refresh, done.toString(), "Completed", AppTheme.success, Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ViewSwitch(cardView, onCardView)
            OutlinedButton(onClick = onOpenFolder, modifier = Modifier.height(30.dp)) {
                Icon(DlmIcons.Folder, null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open folder", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, value: String, label: String, tint: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(60.dp)
            .clip(shape)
            .background(AppTheme.Palette.surface, shape)
            .border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, Modifier.size(16.dp), tint = tint) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, maxLines = 1)
            Text(label, fontSize = 11.sp, color = AppTheme.Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Table (AB Download Manager's columns) or Cards (one roomy card per download). */
@Composable
private fun ViewSwitch(cardView: Boolean, onCardView: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.clip(shape).border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.5f), shape)) {
        listOf(false to "Table", true to "Cards").forEach { (cards, label) ->
            val selected = cards == cardView
            Text(
                label,
                fontSize = 11.sp,
                color = if (selected) AppTheme.Palette.onAccent else AppTheme.Palette.onSurface,
                modifier = Modifier
                    .background(if (selected) AppTheme.Palette.accent else Color.Transparent)
                    .clickable { onCardView(cards) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/**
 * One download as a card: name, kind and state at the top, a slim progress bar, and one
 * line of numbers - size, speed each way, time left, peers - with its actions at the end.
 */
@Composable
fun DownloadCardRow(
    item: DownloadItem,
    checked: Boolean,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
    onRemove: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val stateColor = when (item.status) {
        DownloadStatus.RUNNING -> AppTheme.Palette.accent
        DownloadStatus.COMPLETED -> AppTheme.success
        DownloadStatus.FAILED -> AppTheme.Palette.error
        else -> AppTheme.Palette.muted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (checked) AppTheme.Palette.selection else AppTheme.Palette.surface, shape)
            .border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.3f), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor))
            Spacer(Modifier.width(8.dp))
            Text(
                item.fileName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.Palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Chip(
                when (item.source) {
                    DownloadSource.TORRENT -> "Torrent"
                    DownloadSource.YOUTUBE -> "YouTube"
                    else -> "Direct"
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(DisplayFormat.status(item), fontSize = 11.sp, color = stateColor)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fraction = (item.progressPercent / 100f).coerceIn(0f, 1f)
            val shown by androidx.compose.animation.core.animateFloatAsState(fraction, label = "card-progress")
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppTheme.Palette.raised)
                    .drawBehind { drawRect(stateColor, size = size.copy(width = size.width * shown)) }
            )
            Spacer(Modifier.width(10.dp))
            Text("${item.progressPercent}%", fontSize = 11.sp, color = AppTheme.Palette.onSurface)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                statsLine(item),
                fontSize = 11.sp,
                color = AppTheme.Palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            when {
                item.isActive -> CardAction(DlmIcons.Pause, "Pause", onPause)
                item.status == DownloadStatus.FAILED -> CardAction(Icons.Default.Refresh, "Retry", onRetry)
                item.status != DownloadStatus.COMPLETED -> CardAction(Icons.Default.PlayArrow, "Resume", onResume)
            }
            CardAction(DlmIcons.Folder, "Show in folder", onOpen)
            CardAction(Icons.Default.Settings, "Options", onOptions)
            CardAction(Icons.Default.Close, "Remove", onRemove)
        }
    }
}

/** "15.8 MB / 994 MB · ↓ 3.4 MB/s · ↑ 9.9 KB/s · 4m 7s left · 47 peers", leaving out what does not apply. */
internal fun statsLine(item: DownloadItem): String = buildList {
    add(
        if (item.totalBytes > 0) "${DisplayFormat.bytes(item.bytesDownloaded)} / ${DisplayFormat.bytes(item.totalBytes)}"
        else DisplayFormat.bytes(item.bytesDownloaded)
    )
    if (item.status == DownloadStatus.RUNNING) {
        add("↓ ${DisplayFormat.speed(item.speedBytesPerSecond)}")
        if (item.isTorrent) add("↑ ${DisplayFormat.speed(item.uploadRate)}")
        val left = item.totalBytes - item.bytesDownloaded
        if (item.speedBytesPerSecond > 0 && left > 0) add(eta(left / item.speedBytesPerSecond) + " left")
    }
    if (item.isTorrent && item.peerCount > 0) add("${item.peerCount} peers")
}.joinToString("  ·  ")

private fun eta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = AppTheme.Palette.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AppTheme.Palette.raised)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun CardAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, label, Modifier.size(16.dp), tint = AppTheme.Palette.muted)
    }
}
