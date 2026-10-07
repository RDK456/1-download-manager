package com.downloadhub.app.ui

import androidx.compose.foundation.border
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Trash2
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.downloadhub.app.ui.theme.Mono
import com.downloadhub.app.ui.theme.inkPanel
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.label
import com.downloadhub.app.download.ThumbnailCache
import java.io.File

@Composable
fun DownloadCard(
    item: DownloadEntity,
    loader: ThumbnailCache,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Eased between the 400 ms progress writes, so the bar glides instead of stepping.
    val progress by androidx.compose.animation.core.animateFloatAsState(progressFor(item), label = "progress")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .inkPanel()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadThumbnail(
                    loader = loader,
                    item = item,
                    modifier = Modifier.size(58.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        cardSubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(item.status),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                when {
                    item.status.canPauseUi -> IconButton(onClick = onPause) {
                        Icon(Lucide.Pause, contentDescription = "Pause download")
                    }
                    item.status == DownloadStatus.PAUSED -> IconButton(onClick = onResume) {
                        Icon(Lucide.Play, contentDescription = "Resume download")
                    }
                    item.status == DownloadStatus.FAILED -> IconButton(onClick = onRetry) {
                        Icon(Lucide.RotateCw, contentDescription = "Retry download")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Lucide.EllipsisVertical, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (item.status.canPauseUi) {
                            DropdownMenuItem(
                                text = { Text("Pause") },
                                onClick = { menuOpen = false; onPause() },
                                leadingIcon = { Icon(Lucide.Pause, contentDescription = null) }
                            )
                        }
                        if (item.status == DownloadStatus.PAUSED) {
                            DropdownMenuItem(
                                text = { Text("Resume") },
                                onClick = { menuOpen = false; onResume() },
                                leadingIcon = { Icon(Lucide.Play, contentDescription = null) }
                            )
                        }
                        if (item.status == DownloadStatus.FAILED) {
                            DropdownMenuItem(
                                text = { Text("Retry") },
                                onClick = { menuOpen = false; onRetry() },
                                leadingIcon = { Icon(Lucide.RotateCw, contentDescription = null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Details") },
                            onClick = { menuOpen = false; onClick() },
                            leadingIcon = { Icon(Lucide.FileText, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { menuOpen = false; onDelete() },
                            leadingIcon = { Icon(Lucide.Trash2, contentDescription = null) }
                        )
                    }
                }
            }
            Spacer(Modifier.size(11.dp))
            if (item.status == DownloadStatus.COMPLETED) {
                // A finished download shows a state, not a full progress bar.
                CompletedFooter(item)
            } else {
                SignalBar(
                    progress = progress,
                    color = statusColor(item.status),
                    live = item.status.canPauseUi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        progressLabel(item),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        progressDetail(item),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (item.status == DownloadStatus.FAILED) null else Mono,
                        modifier = Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Secondary line of a card. A finished download shows only its category, because
 * the Completed footer underneath already states the status.
 */
fun cardSubtitle(item: DownloadEntity): String =
    if (item.status == DownloadStatus.COMPLETED) {
        item.category.label
    } else {
        "${item.category.label} • ${statusLabel(item.status)}"
    }

/**
 * Artwork for a download: the cached thumbnail, the finished file when the
 * download is an image, or a category icon when there is nothing to show.
 */
@Composable
internal fun DownloadThumbnail(
    loader: ThumbnailCache,
    item: DownloadEntity,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp
) {
    // Keyed on what the lookup reads, not the whole row: the row changes on every progress
    // write, and the lookup touches the disk (for a finished torrent it walks the folder),
    // so it runs off the main thread and only when something it depends on changed.
    var bitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.thumbnailPath, item.thumbnailUrl, item.status, item.outputPath, item.category, item.mimeType) {
        val loaded = runCatching {
            val localPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { localThumbnailFor(item) }
            loader.load(localPath, item.thumbnailUrl)
        }.getOrNull()
        if (loaded != null) bitmap = loaded.asImageBitmap()
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            // A neutral hairline so a photo's edge does not blur into the surface.
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = iconFor(item),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(iconSize)
            )
        }
        val duration = item.durationSeconds
        if (image != null && duration != null && duration > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp),
                color = Color.Black.copy(alpha = 0.66f),
                shape = MaterialTheme.shapes.extraSmall
            ) {
                Text(
                    formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }
    }
}

/**
 * Where to look for artwork: the cached thumbnail, or the finished file itself - any
 * finished file, since the loader can draw a frame or cover from media, an icon from an
 * APK and the first page from a PDF, and returns nothing for the rest.
 */
private fun localThumbnailFor(item: DownloadEntity): String? {
    if (item.thumbnailPath?.let { File(it).isFile } == true) return item.thumbnailPath
    if (item.status == DownloadStatus.COMPLETED) {
        val output = item.outputPath
        if (!output.isNullOrBlank() && !output.startsWith("content:")) {
            val file = File(output)
            if (file.isFile) return file.absolutePath
            // A torrent publishes a folder: preview its largest media file.
            if (file.isDirectory) {
                return file.walkTopDown()
                    .filter { it.isFile }
                    .maxByOrNull { candidate -> candidate.length() }
                    ?.takeIf { candidate ->
                        candidate.extension.lowercase() in MEDIA_PREVIEW_EXTENSIONS
                    }
                    ?.absolutePath
            }
        }
    }
    return null
}

private val MEDIA_PREVIEW_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts", "flv", "mpg", "mpeg", "wmv", "ogv",
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma",
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
    "apk", "pdf"
)

@Composable
private fun CompletedFooter(item: DownloadEntity) {
    // One quiet line rather than a tinted bar the width of the card: a finished download
    // is the common case, and a bar on every one made the list twice as tall as it needs.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Lucide.CircleCheck,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        // Neutral, not green: a finished download is the normal state, and on a panel
        // only what is moving gets the accent.
        Text(
            "DONE",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatBytes(item.bytesDownloaded),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun progressDetail(item: DownloadEntity): String = when (item.status) {
    DownloadStatus.RUNNING -> buildString {
        if (item.speedBytesPerSecond > 0) append(formatSpeed(item.speedBytesPerSecond))
        if (item.etaSeconds >= 0) {
            if (isNotEmpty()) append(" • ")
            append(formatEta(item.etaSeconds))
        }
        if (isEmpty()) append(formatBytes(item.bytesDownloaded))
    }
    DownloadStatus.RESOLVING -> "Finding the best stream"
    DownloadStatus.QUEUED -> "Waiting in queue"
    DownloadStatus.PAUSED -> "${progressLabel(item)} paused"
    DownloadStatus.FAILED -> item.errorMessage ?: "Retry available"
    DownloadStatus.COMPLETED -> formatBytes(item.bytesDownloaded)
}

fun sourceLabel(source: DownloadSource): String = when (source) {
    DownloadSource.HTTP -> "Direct file"
    DownloadSource.YOUTUBE -> "YouTube"
    DownloadSource.TORRENT -> "Torrent"
}

private fun iconFor(item: DownloadEntity): ImageVector = when {
    item.source == DownloadSource.TORRENT -> Lucide.Folder
    else -> when (item.category) {
        DownloadCategory.VIDEO -> Lucide.Film
        DownloadCategory.AUDIO -> Lucide.Music
        DownloadCategory.IMAGE -> Lucide.Image
        DownloadCategory.COMPRESSED, DownloadCategory.ARCHIVE -> Lucide.Archive
        DownloadCategory.DOCUMENT -> Lucide.FileText
        DownloadCategory.PROGRAM -> Lucide.LayoutGrid
        DownloadCategory.FILE, DownloadCategory.OTHER -> Lucide.File
    }
}

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
    // One accent, for what is moving; neutral for what is waiting or done; red only for
    // what needs a hand - the way a panel lights one LED rather than all of them.
    DownloadStatus.RESOLVING -> MaterialTheme.colorScheme.primary
    DownloadStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
}

internal val DownloadStatus.canPauseUi: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.RESOLVING || this == DownloadStatus.RUNNING

/**
 * The app's signature progress bar: a row of blocks, like an LED level meter, rather than
 * a smooth Material line. It is also what the downloader does - a file fetched in
 * segments - so the bar shows the work as pieces. While a download is moving, the block
 * at its leading edge pulses.
 */
@Composable
internal fun SignalBar(
    progress: Float,
    color: Color,
    live: Boolean,
    modifier: Modifier = Modifier,
    segments: Int = 28
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val pulse = if (live) {
        rememberInfiniteTransition(label = "signal").animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "head"
        ).value
    } else 1f
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val block = (size.width - gap * (segments - 1)) / segments
        val lit = progress.coerceIn(0f, 1f) * segments
        val head = lit.toInt().coerceAtMost(segments - 1)
        for (i in 0 until segments) {
            val x = i * (block + gap)
            drawRect(track, Offset(x, 0f), Size(block, size.height))
            val fill = (lit - i).coerceIn(0f, 1f)
            when {
                live && i == head -> drawRect(color.copy(alpha = pulse), Offset(x, 0f), Size(block, size.height))
                fill > 0f -> drawRect(color, Offset(x, 0f), Size(block * fill, size.height))
            }
        }
    }
}

/**
 * A download as a grid tile: the artwork big, the name under it, then the segmented bar
 * while it runs or the size once it is done. Tapping opens the same details sheet as a
 * card; the corner key pauses, resumes or retries without opening anything.
 */
@Composable
fun DownloadTile(
    item: DownloadEntity,
    loader: ThumbnailCache,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit
) {
    val progress by androidx.compose.animation.core.animateFloatAsState(progressFor(item), label = "tile-progress")
    Column(
        Modifier
            .fillMaxWidth()
            .inkPanel()
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box {
            DownloadThumbnail(loader, item, Modifier.fillMaxWidth().aspectRatio(16f / 10f), iconSize = 36.dp)
            val action: Pair<androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>? = when {
                item.status.canPauseUi -> Lucide.Pause to onPause
                item.status == DownloadStatus.PAUSED -> Lucide.Play to onResume
                item.status == DownloadStatus.FAILED -> Lucide.RotateCw to onRetry
                else -> null
            }
            action?.let { (icon, run) ->
                Surface(
                    onClick = run,
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.62f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            item.fileName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.size(6.dp))
        if (item.status == DownloadStatus.COMPLETED) {
            Text(
                formatBytes(item.totalBytes.takeIf { it > 0 } ?: item.bytesDownloaded),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SignalBar(progress, statusColor(item.status), live = item.status.canPauseUi, modifier = Modifier.fillMaxWidth().height(6.dp), segments = 16)
            Spacer(Modifier.size(4.dp))
            Text(
                progressLabel(item),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                color = statusColor(item.status),
                maxLines = 1
            )
        }
    }
}
