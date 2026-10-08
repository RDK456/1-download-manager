package com.downloadhub.app.ui

import androidx.compose.foundation.verticalScroll
import com.downloadhub.app.ui.theme.inkPanel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Shuffle
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import com.downloadhub.core.FreeCatalog
import kotlinx.coroutines.launch

/** One way in from the Discover tab. */
data class DiscoverTile(val title: String, val subtitle: String, val icon: ImageVector, val destination: AppDestination)

/** The Discover tab: free books, free TV and the player, each a tap away. */
@Composable
fun DiscoverScreen(tiles: List<DiscoverTile>, onOpen: (AppDestination) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEach { tile ->
            Box(Modifier.fillMaxWidth().inkPanel().clickable { onOpen(tile.destination) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)) {
                        Icon(tile.icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(12.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Lucide.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun BooksScreen(loader: ThumbnailCache, catalog: FreeCatalog, onDownload: (BookResult, BookFile) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(catalog) { mutableStateOf("") }
    var results by remember(catalog) { mutableStateOf<List<BookResult>>(emptyList()) }
    var busy by remember(catalog) { mutableStateOf(false) }
    var searched by remember(catalog) { mutableStateOf(false) }
    var source by remember(catalog) { mutableStateOf<String?>(null) }
    // Shown until something is searched for: a fresh random draw every time the screen
    // opens, and more of it as the grid is scrolled to the end.
    var picks by remember(catalog) { mutableStateOf<List<BookResult>>(emptyList()) }
    var picking by remember(catalog) { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    suspend fun morePicks(reset: Boolean = false) {
        if (picking) return
        picking = true
        val next = BookSources.recommended(catalog)
        picks = (if (reset) next else picks + next).distinctBy { it.source + "|" + it.pageUrl + "|" + it.title }
        picking = false
    }
    LaunchedEffect(catalog) { morePicks(reset = true) }
    val showingPicks = !searched || query.isBlank()

    fun run() {
        if (query.isBlank() || busy) return
        busy = true
        scope.launch {
            results = BookSources.search(query, catalog)
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
            label = { Text("Search ${catalog.label}") },
            placeholder = { Text(catalog.hint) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (!showingPicks && results.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = source == null, onClick = { source = null }, label = { Text("All (${results.size})") })
                catalog.sources.forEach { name ->
                    val count = results.count { it.source == name }
                    if (count > 0) FilterChip(selected = source == name, onClick = { source = name }, label = { Text("$name ($count)") })
                }
            }
        }
        if (showingPicks) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Picked for you", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { scope.launch { grid.scrollToItem(0); morePicks(reset = true) } }, enabled = !picking) {
                    Icon(Lucide.Shuffle, contentDescription = "Show different picks")
                }
            }
        }
        val shown = if (showingPicks) picks else results.filter { source == null || it.source == source }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                when {
                    busy || (showingPicks && picking) -> CircularProgressIndicator()
                    else -> Text(
                        if (showingPicks) "Could not load picks. ${catalog.blurb}" else "Nothing found. Try fewer words, or a surname.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            if (showingPicks) LoadMoreAtEnd(grid) { morePicks() }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = grid,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.source + "|" + it.pageUrl + "|" + it.title }) { book ->
                    BookCard(book, loader, onDownload)
                }
                if (showingPicks && picking) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
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
                    Icon(Lucide.BookOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
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

/**
 * Lazy loading: calls [load] when a grid is scrolled to within [buffer] items of its end,
 * so the next page arrives before the user reaches the bottom. Fires again only once new
 * items have pushed the end away, so a load that brings nothing back does not loop.
 */
@Composable
internal fun LoadMoreAtEnd(state: LazyGridState, buffer: Int = 6, load: suspend () -> Unit) {
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - buffer
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) load() }
}

/** [LoadMoreAtEnd] for a list. */
@Composable
internal fun LoadMoreAtEnd(state: LazyListState, buffer: Int = 6, load: suspend () -> Unit) {
    val nearEnd by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - buffer
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) load() }
}
