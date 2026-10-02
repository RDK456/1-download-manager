package com.downloadhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.SearchGroup
import com.downloadhub.core.SearchOutcome
import com.downloadhub.core.SearchResult
import com.downloadhub.core.defaultSearchSources
import com.downloadhub.core.isSearchable
import com.downloadhub.core.searchSources
import com.downloadhub.core.sortSearchResults
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Find something to download.
 *
 * The same search the Windows app does, over the same sources, with the same rules - the
 * logic is in `:core` and this is the screen around it. A result's Download button hands
 * its magnet to the add sheet, which is the same sheet a pasted magnet goes through, so the
 * file list and the folder are already handled.
 */
@Composable
fun SearchScreen(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(SearchGroup.MOVIES) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val sources = remember { defaultSearchSources() }

    fun run() {
        val trimmed = query.trim()
        if (!isSearchable(trimmed)) {
            message = "Type at least two characters to search."
            return
        }
        // A new search replaces the old one. Two searches' answers arriving into one list
        // is a list belonging to neither.
        job?.cancel()
        message = null
        busy = true
        results = emptyList()
        outcome = null
        job = scope.launch {
            val answer = searchSources(
                sources = sources,
                query = trimmed,
                group = group,
                onPartial = { partial -> results = sortSearchResults(partial) }
            )
            outcome = answer
            results = sortSearchResults(answer.results)
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                placeholder = { Text("What are you looking for?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            CategoryAndSearchRow(
                group = group,
                onGroup = { entry ->
                    group = entry
                    if (isSearchable(query)) run()
                },
                busy = busy,
                onSearch = { run() }
            )
        }

        // Said out loud, above the results. A search that quietly drops a source looks like
        // one that found nothing, and those are very different things to be told.
        outcome?.offlineNote()?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        message?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                results.isEmpty() && busy -> Centre("Asking every source...")
                results.isEmpty() && message == null && outcome == null ->
                    Centre("Search the sources for something to download.")
                results.isEmpty() -> Centre("Nothing found for \"${query.trim()}\".")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.source + it.infoHash }) { result ->
                        SearchResultCard(result) { onPick(result.magnet) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centre(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, onDownload: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    result.name,
                    fontSize = 14.sp,
                    // The whole name. What identifies a release is at the front of the
                    // name and what it *is* - resolution, codec, audio, group - is at the
                    // back, so cutting the tail takes away exactly what tells one
                    // result from another.
                    softWrap = true,
                    overflow = TextOverflow.Clip
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DisplayFormat.bytes(result.sizeBytes),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    // "Seeders unknown" rather than "0 seeders" for a source that publishes
                    // no counts. A zero there means the site does not say, and writing it as
                    // a number invites skipping a live release.
                    Text(
                        if (result.reportsHealth) "${result.seeders} seeders" else "seeders unknown",
                        fontSize = 11.sp,
                        fontWeight = if (result.reportsHealth && result.seeders > 0) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = if (result.reportsHealth && result.seeders > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        result.source,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDownload) { Text("Download") }
        }
    }
}

/**
 * The categories, and the Search button, on as many lines as they need.
 *
 * They were one Row with no wrapping, which is four chips and a button side by side -
 * roughly 415 dp of content on a screen with about 328 dp of width once the padding is
 * taken off. The Search button was laid out past the right-hand edge and never seen, and
 * the chips were squeezed against it rather than laid out evenly. A row that cannot wrap
 * does not align; it overflows, and the overflow is invisible because it is off screen.
 *
 * FlowRow wraps onto a second line instead. It is also how the desktop shows its four
 * categories - the two do not have to look identical to behave the same way.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CategoryAndSearchRow(
    group: SearchGroup,
    onGroup: (SearchGroup) -> Unit,
    busy: Boolean,
    onSearch: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Four words as chips rather than a dropdown: a menu that hides four words behind
        // a tap is a menu for eight, and a phone has no room for eight.
        SearchGroup.entries.forEach { entry ->
            FilterChip(
                selected = entry == group,
                onClick = { onGroup(entry) },
                label = { Text(entry.label, fontSize = 11.sp) }
            )
        }
        Button(
            onClick = onSearch,
            enabled = !busy,
            modifier = Modifier.padding(start = 2.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Search")
            }
        }
    }
}
