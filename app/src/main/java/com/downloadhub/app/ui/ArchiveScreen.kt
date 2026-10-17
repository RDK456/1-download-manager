package com.downloadhub.app.ui

import com.composables.icons.lucide.Download

import com.composables.icons.lucide.File

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import com.downloadhub.app.ui.expressive.component.Button
import com.downloadhub.app.ui.expressive.component.Card
import com.downloadhub.app.ui.expressive.shape.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.downloadhub.app.ui.expressive.component.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.download.ThumbnailCache
import com.downloadhub.core.ArchiveFile
import com.downloadhub.core.ArchiveItem
import com.downloadhub.core.ArchiveOrg
import com.downloadhub.core.ArchiveType
import kotlin.random.Random

/**
 * The whole Internet Archive: search or browse the most downloaded items of any media type
 * (video, audio, books, software and apps, images, data). Opening an item lists its files;
 * one tap queues all of them, or any one alone.
 */
/** Keeps the open item through the app being closed in the background. */
private val ArchiveItemSaver = androidx.compose.runtime.saveable.Saver<ArchiveItem?, List<String>>(
    save = { item -> item?.let { listOf(it.id, it.title, it.creator, it.mediatype, it.sizeBytes.toString(), it.downloads.toString()) }.orEmpty() },
    restore = { v ->
        if (v.size < 6) null
        else ArchiveItem(v[0], v[1], v[2], v[3], v[4].toLongOrNull() ?: 0L, v[5].toLongOrNull() ?: 0L)
    }
)

@Composable
fun ArchiveScreen(loader: ThumbnailCache, onDownload: (List<ArchiveFile>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ArchiveType.ALL) }
    var items by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var open by rememberSaveable(stateSaver = ArchiveItemSaver) { mutableStateOf<ArchiveItem?>(null) }
    var moreFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    var loadingMore by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // With nothing typed, start somewhere random in the most-downloaded list and shuffle
    // it, so browsing shows different things on every visit.
    suspend fun fetch(at: Int) = ArchiveOrg.search(submitted, type, at).let { if (submitted.isBlank()) it.shuffled() else it }

    LaunchedEffect(submitted, type, attempt) {
        loading = true
        failed = null
        moreFailed = false
        page = if (submitted.isBlank()) 1 + Random.nextInt(ArchiveOrg.BROWSE_DEPTH) else 1
        items = runCatching { fetch(page) }
            .onFailure { failed = "Could not reach the Internet Archive." }
            .getOrDefault(emptyList())
        more = items.size == ArchiveOrg.PAGE_SIZE
        loading = false
    }

    open?.let { item ->
        BackHandler { open = null }
        ArchiveItemView(item, onDownload)
        return
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search archive.org") },
            placeholder = { Text("Empty: random popular picks") },
            leadingIcon = { androidx.compose.material3.Icon(Lucide.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArchiveType.entries.forEach { FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.label) }) }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() && com.downloadhub.app.ui.theme.UiGeneration.expressive -> com.downloadhub.app.ui.expressive.component.EmptyState(
                if (failed != null) com.downloadhub.app.ui.expressive.model.StateShapeKind.PENTAGON else com.downloadhub.app.ui.expressive.model.StateShapeKind.CIRCLE,
                Lucide.Search,
                if (failed != null) "Can't reach the Archive" else "Nothing found",
                failed ?: "Try other words, or another kind of media.",
                primary = if (failed != null) com.downloadhub.app.ui.expressive.component.EmptyAction("Try again", { attempt++ }) else null
            )
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(failed ?: "Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> {
                // Stops asking after a failure; the Try again row below clears it and asks once more.
                if (more && !moreFailed) LoadMoreAtEnd(list) {
                    if (loadingMore) return@LoadMoreAtEnd
                    loadingMore = true
                    val next = runCatching { fetch(page + 1) }.getOrNull()
                    if (next != null) {
                        page++
                        items = (items + next).distinctBy { it.id }
                        more = next.size == ArchiveOrg.PAGE_SIZE
                    } else {
                        moreFailed = true
                    }
                    loadingMore = false
                }
                LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (com.downloadhub.app.ui.theme.UiGeneration.expressive) 2.dp else 8.dp)) {
                    itemsIndexed(items, key = { _, it -> it.id }) { index, item -> ArchiveRow(item, loader, index, items.size) { open = item } }
                    if (moreFailed) item {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Couldn't load more.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { moreFailed = false }) { Text("Try again") }
                        }
                    }
                    if (loadingMore) item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(item: ArchiveItem, loader: ThumbnailCache, index: Int, count: Int, onOpen: () -> Unit) {
    var thumb by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { thumb = runCatching { loader.load(null, item.thumbnailUrl) }.getOrNull()?.asImageBitmap() }
    if (com.downloadhub.app.ui.theme.UiGeneration.expressive) {
        androidx.compose.material3.SegmentedListItem(
            onClick = onOpen,
            shapes = androidx.compose.material3.ListItemDefaults.segmentedShapes(index, count),
            leadingContent = {
                Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                    thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            },
            supportingContent = {
                Text(
                    listOf(item.typeLabel, item.creator, item.sizeBytes.takeIf { it > 0 }?.let(::formatBytes).orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            },
            content = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmallEmphasized) }
        )
        return
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.typeLabel, item.creator, item.sizeBytes.takeIf { it > 0 }?.let(::formatBytes).orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArchiveItemView(item: ArchiveItem, onDownload: (List<ArchiveFile>) -> Unit) {
    val context = LocalContext.current
    var files by remember(item.id) { mutableStateOf<List<ArchiveFile>?>(null) }
    var failed by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        files = runCatching { ArchiveOrg.files(item.id) }
            .onFailure { failed = "Could not list the files." }
            .getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.pageUrl))) } }) {
            Text("Open on archive.org")
        }
        val list = files
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(failed ?: "This item has no downloadable files.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> {
                var confirming by remember(item.id) { mutableStateOf(false) }
                val summary = "${list.size} files · ${formatBytes(list.sumOf { it.sizeBytes })}"
                Button(
                    onClick = { if (ArchiveOrg.needsConfirmation(list)) confirming = true else onDownload(list) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Download all ($summary)") }
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Download all?") },
                        text = { Text("This queues $summary from \"${item.title}\".") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                onDownload(list)
                            }) { Text("Download all") }
                        },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
                    )
                }
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    itemsIndexed(list, key = { _, it -> it.name }) { index, file ->
                        if (com.downloadhub.app.ui.theme.UiGeneration.expressive) {
                            com.downloadhub.app.ui.expressive.component.BadgeRow(
                                title = file.name,
                                index = index,
                                count = list.size,
                                icon = com.composables.icons.lucide.Lucide.File,
                                supporting = listOf(file.format, formatBytes(file.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                                onClick = { onDownload(listOf(file)) },
                                trailing = { androidx.compose.material3.FilledTonalIconButton(onClick = { onDownload(listOf(file)) }) { androidx.compose.material3.Icon(com.composables.icons.lucide.Lucide.Download, contentDescription = "Download " + file.name) } }
                            )
                            return@itemsIndexed
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(file.format, formatBytes(file.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onDownload(listOf(file)) }) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}
