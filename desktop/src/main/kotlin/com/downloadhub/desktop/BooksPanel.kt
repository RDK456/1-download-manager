package com.downloadhub.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.BookFile
import com.downloadhub.core.BookResult
import com.downloadhub.core.BookSources
import kotlinx.coroutines.launch

/**
 * Free books as a cover grid: Project Gutenberg, Open Library, the Internet Archive and
 * Wikisource, searched together. Each card offers the formats its source has; choosing one
 * queues it like any other download, named after the book.
 */
@Composable
fun BooksPanel(
    onDownload: (BookResult, BookFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
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

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search free books", fontSize = 12.sp) },
                placeholder = { Text("Title or author", fontSize = 12.sp, color = AppTheme.Palette.faint) },
                modifier = Modifier.weight(1f).onEnter(!busy) { run() }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { run() }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppTheme.Palette.onAccent)
                else Text("Search")
            }
        }
        val sources = listOf(BookSources.GUTENBERG, BookSources.OPEN_LIBRARY, BookSources.ARCHIVE, BookSources.WIKISOURCE)
        if (results.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterPill("All (${results.size})", source == null) { source = null }
                sources.forEach { name ->
                    val count = results.count { it.source == name }
                    if (count > 0) FilterPill("$name ($count)", source == name) { source = name }
                }
            }
        }
        val shown = results.filter { source == null || it.source == source }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        busy -> "Searching four free libraries..."
                        searched -> "Nothing found. Try the author's surname, or fewer words."
                        else -> "Classics and public-domain books, free to download: Project Gutenberg, " +
                            "Open Library, the Internet Archive and Wikisource."
                    },
                    color = AppTheme.Palette.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(shown, key = { it.source + "|" + it.pageUrl + "|" + it.title }) { book ->
                    BookCard(book, onDownload)
                }
            }
        }
    }
}

@Composable
internal fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        fontSize = 11.sp,
        color = if (selected) AppTheme.Palette.onAccent else AppTheme.Palette.onSurface,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) AppTheme.Palette.accent else AppTheme.Palette.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** A cover, its title and author, the source, and a button per format. */
@Composable
private fun BookCard(book: BookResult, onDownload: (BookResult, BookFile) -> Unit) {
    val scope = rememberCoroutineScope()
    // Null until asked; an Internet Archive item says what it holds only when asked.
    var resolved by remember(book) { mutableStateOf<List<BookFile>?>(null) }
    var looking by remember(book) { mutableStateOf(false) }
    val files = book.files.ifEmpty { resolved.orEmpty() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .clip(shape)
            .background(AppTheme.Palette.surface)
            .border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.3f), shape)
            .padding(10.dp)
    ) {
        val cover = rememberRemoteImage(book.coverUrl)
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(AppTheme.Palette.raised),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(book.title.take(60), color = AppTheme.Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(book.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(book.author, book.year).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { " " },
            fontSize = 11.sp,
            color = AppTheme.Palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(book.source, fontSize = 10.sp, color = AppTheme.Palette.faint, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                files.isNotEmpty() -> files.take(3).forEach { file -> FormatButton(file.format) { onDownload(book, file) } }
                looking -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                resolved != null -> Text("No downloadable copy", fontSize = 10.sp, color = AppTheme.Palette.error)
                book.archiveId != null -> FormatButton("Get formats") {
                    looking = true
                    scope.launch {
                        resolved = runCatching { BookSources.filesFor(book) }.getOrDefault(emptyList())
                        looking = false
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = AppTheme.Palette.accent,
        modifier = Modifier
            .clip(shape)
            .border(1.dp, AppTheme.Palette.accent.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** A book queued like any other download: named after the book, filed by the category rules. */
internal fun bookRequest(book: BookResult, file: BookFile, settings: DesktopSettings): com.downloadhub.core.TorrentAddRequest {
    val name = BookSources.fileName(book, file)
    val folder = com.downloadhub.core.SaveCategories
        .forFile(name, settings.downloadDirFile(), settings.categoryRules.map { it.toRule() }).folder
    return com.downloadhub.core.TorrentAddRequest(
        metainfo = com.downloadhub.core.TorrentMetainfo(name, emptyList(), "", 0L, "", "", "", true),
        saveDirectory = folder,
        link = file.url
    )
}
