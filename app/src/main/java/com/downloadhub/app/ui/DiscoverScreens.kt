package com.downloadhub.app.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.download.ThumbnailCache
import com.downloadhub.core.BookFile
import com.downloadhub.core.BookResult
import com.downloadhub.core.BookSources
import kotlinx.coroutines.launch

/** One way in from the Discover tab. */
data class DiscoverTile(val title: String, val subtitle: String, val icon: ImageVector, val destination: AppDestination)

/** The Discover tab: free books, free TV and the player, each a tap away. */
@Composable
fun DiscoverScreen(tiles: List<DiscoverTile>, onOpen: (AppDestination) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEach { tile ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(tile.destination) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                        Icon(tile.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Free books as a cover grid: Project Gutenberg, Open Library, the Internet Archive and
 * Wikisource, searched together. A format button queues the book like any other download.
 */
@Composable
fun BooksScreen(loader: ThumbnailCache, onDownload: (BookResult, BookFile) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BookResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<String?>(null) }

    fun run() {
        if (query.isBlank() || busy) return
        busy = true
        scope.launch {
            results = BookSources.search(query)
            source = null
            searched = true
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search free books") },
            placeholder = { Text("Title or author") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (results.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = source == null, onClick = { source = null }, label = { Text("All (${results.size})") })
                listOf(BookSources.GUTENBERG, BookSources.OPEN_LIBRARY, BookSources.ARCHIVE, BookSources.WIKISOURCE).forEach { name ->
                    val count = results.count { it.source == name }
                    if (count > 0) FilterChip(selected = source == name, onClick = { source = name }, label = { Text("$name ($count)") })
                }
            }
        }
        val shown = results.filter { source == null || it.source == source }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        busy -> "Searching four free libraries..."
                        searched -> "Nothing found. Try the author's surname, or fewer words."
                        else -> "Classics and public-domain books, free to download from Project Gutenberg, Open Library, the Internet Archive and Wikisource."
                    },
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.source + "|" + it.pageUrl + "|" + it.title }) { book ->
                    BookCard(book, loader, onDownload)
                }
            }
        }
    }
}

@Composable
private fun BookCard(book: BookResult, loader: ThumbnailCache, onDownload: (BookResult, BookFile) -> Unit) {
    val scope = rememberCoroutineScope()
    var resolved by remember(book) { mutableStateOf<List<BookFile>?>(null) }
    var looking by remember(book) { mutableStateOf(false) }
    val files = book.files.ifEmpty { resolved.orEmpty() }
    var cover by remember(book.coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(book.coverUrl) {
        cover = book.coverUrl?.let { runCatching { loader.load(null, it) }.getOrNull()?.asImageBitmap() }
    }
    Card {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                val image = cover
                if (image != null) {
                    Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(book.author, book.year).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { book.source },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    files.isNotEmpty() -> files.take(3).forEach { file ->
                        AssistChip(onClick = { onDownload(book, file) }, label = { Text(file.format) })
                    }
                    looking -> CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp)
                    resolved != null -> Text("No downloadable copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                    book.archiveId != null -> AssistChip(
                        onClick = {
                            looking = true
                            scope.launch {
                                resolved = runCatching { BookSources.filesFor(book) }.getOrDefault(emptyList())
                                looking = false
                            }
                        },
                        label = { Text("Get formats") }
                    )
                }
            }
        }
    }
}
