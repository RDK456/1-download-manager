package com.downloadhub.app.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/**
 * The whole Internet Archive: search or browse the most downloaded items of any media type
 * (video, audio, books, software and apps, images, data). Opening an item lists its files;
 * one tap queues all of them, or any one alone.
 */
@Composable
fun ArchiveScreen(loader: ThumbnailCache, onDownload: (List<ArchiveFile>) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ArchiveType.ALL) }
    var items by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<ArchiveItem?>(null) }

    LaunchedEffect(submitted, type) {
        loading = true
        failed = null
        page = 1
        items = runCatching { ArchiveOrg.search(submitted, type) }
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
            placeholder = { Text("Empty: most downloaded") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArchiveType.entries.forEach { FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.label) }) }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(failed ?: "Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item -> ArchiveRow(item, loader) { open = item } }
                if (more) item {
                    TextButton(onClick = {
                        scope.launch {
                            val next = runCatching { ArchiveOrg.search(submitted, type, page + 1) }.getOrDefault(emptyList())
                            page++
                            items = (items + next).distinctBy { it.id }
                            more = next.size == ArchiveOrg.PAGE_SIZE
                        }
                    }) { Text("Load more") }
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(item: ArchiveItem, loader: ThumbnailCache, onOpen: () -> Unit) {
    var thumb by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { thumb = runCatching { loader.load(null, item.thumbnailUrl) }.getOrNull()?.asImageBitmap() }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.mediatype, item.creator, item.sizeBytes.takeIf { it > 0 }?.let(::formatBytes).orEmpty())
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
                    items(list, key = { it.name }) { file ->
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
