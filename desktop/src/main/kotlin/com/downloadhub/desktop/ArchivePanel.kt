package com.downloadhub.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.ArchiveFile
import com.downloadhub.core.ArchiveItem
import com.downloadhub.core.ArchiveOrg
import com.downloadhub.core.ArchiveType
import kotlinx.coroutines.launch

/**
 * The whole Internet Archive: search or browse the most downloaded items of any media type
 * (video, audio, books, software and apps, images, data). Opening an item lists the files
 * it holds; one click queues all of them, or any one alone.
 */
@Composable
fun ArchivePanel(onDownload: (List<ArchiveFile>) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ArchiveType.ALL) }
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
            .onFailure { failed = "Could not reach the Internet Archive: ${it.message}" }
            .getOrDefault(emptyList())
        more = items.size == ArchiveOrg.PAGE_SIZE
        loading = false
    }

    open?.let { item ->
        ArchiveItemView(item, onBack = { open = null }, onDownload = onDownload, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search archive.org - leave empty to browse the most downloaded", fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { submitted = query.trim() }) { Text("Search") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArchiveType.entries.forEach { FilterPill(it.label, type == it) { type = it } }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(failed ?: "Nothing found.", color = AppTheme.Palette.muted, fontSize = 13.sp)
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item -> ArchiveRow(item) { open = item } }
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
private fun ArchiveRow(item: ArchiveItem, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(AppTheme.Palette.surface)
            .border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.3f), shape)
            .clickable(onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumb = rememberRemoteImage(item.thumbnailUrl)
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(AppTheme.Palette.raised)) {
            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.mediatype, item.creator, item.sizeBytes.takeIf { it > 0 }?.let(::formatBytes).orEmpty(), "${item.downloads} downloads")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp, color = AppTheme.Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArchiveItemView(item: ArchiveItem, onBack: () -> Unit, onDownload: (List<ArchiveFile>) -> Unit, modifier: Modifier) {
    var files by remember(item.id) { mutableStateOf<List<ArchiveFile>?>(null) }
    var failed by remember(item.id) { mutableStateOf<String?>(null) }
    var notice by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        files = runCatching { ArchiveOrg.files(item.id) }
            .onFailure { failed = "Could not list the files: ${it.message}" }
            .getOrDefault(emptyList())
    }

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(item.pageUrl)) } }) { Text("Open page") }
        }
        val list = files
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(failed ?: "This item has no downloadable files.", color = AppTheme.Palette.muted, fontSize = 13.sp)
            }
            else -> {
                var confirming by remember(item.id) { mutableStateOf(false) }
                val summary = "${list.size} files · ${formatBytes(list.sumOf { it.sizeBytes })}"
                fun downloadAll() {
                    onDownload(list)
                    notice = "Queued ${list.size} files."
                }
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Download all?") },
                        text = { Text("This queues $summary from \"${item.title}\".") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                downloadAll()
                            }) { Text("Download all") }
                        },
                        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
                    )
                }
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (ArchiveOrg.needsConfirmation(list)) confirming = true else downloadAll() }) {
                        Text("Download all ($summary)")
                    }
                    notice?.let { Text(it, fontSize = 12.sp, color = AppTheme.Palette.accent, modifier = Modifier.padding(start = 12.dp)) }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.name }) { file ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, fontSize = 12.sp, color = AppTheme.Palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(file.format, formatBytes(file.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 10.sp, color = AppTheme.Palette.muted)
                            }
                            TextButton(onClick = {
                                onDownload(listOf(file))
                                notice = "Queued ${file.name.substringAfterLast('/')}."
                            }) { Text("Download", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}
