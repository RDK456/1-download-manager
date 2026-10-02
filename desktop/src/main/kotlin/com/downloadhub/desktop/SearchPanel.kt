package com.downloadhub.desktop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
 * A search box, a category, and what came back. Every result is a magnet, and Download hands
 * it to the same pre-download window every other magnet goes through - so the file list, the
 * folder, the stop condition and the per-file selection are all already there, and nothing
 * here has to reinvent them.
 *
 * Results stream in as each source answers rather than all at once. They are independent
 * feeds, and waiting for the slowest before showing anything means a search that feels
 * broken for as long as the site having a bad day takes to answer.
 */
@Composable
fun SearchPanel(
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
        // A new search replaces the old one rather than running beside it: two searches'
        // results arriving into one list is a list with rows from both, which is neither.
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

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search", fontSize = 12.sp) },
                placeholder = { Text("What are you looking for?", fontSize = 12.sp, color = AppTheme.Palette.faint) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { run() }, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(14.dp).height(14.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.Palette.onAccent
                    )
                } else {
                    Text("Search")
                }
            }
            Spacer(Modifier.width(10.dp))
            // The categories, as tabs rather than a dropdown: they are four words, and a
            // menu that hides four words behind a click is a menu for eight.
            SearchGroup.entries.forEach { entry ->
                val selected = entry == group
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .background(
                            if (selected) AppTheme.Palette.accentContainer else AppTheme.Palette.surface,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable {
                            group = entry
                            if (isSearchable(query)) run()
                        }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        entry.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) AppTheme.Palette.onAccent else AppTheme.Palette.muted
                    )
                }
            }
        }

        // The offline note, said out loud.
        //
        // A search that quietly drops a source looks like a search that found nothing, and
        // "nothing on the internet matches this" is a very different thing from "one of the
        // sites was down". The note is above the results rather than under them, because a
        // result the user has to scroll to find is a result they will not read.
        val note = outcome?.offlineNote()
        if (note != null) {
            Text(
                note,
                fontSize = 11.sp,
                color = AppTheme.Palette.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        message?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = AppTheme.Palette.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                results.isEmpty() && busy -> CenteredNote("Asking every source...")
                results.isEmpty() && message == null && outcome == null ->
                    CenteredNote("Search the sources for something to download.")
                results.isEmpty() -> CenteredNote("Nothing found for \"${query.trim()}\".")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.source + it.infoHash }) { result ->
                        SearchRow(result) { onPick(result.magnet) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = AppTheme.Palette.muted)
    }
}

/** One result: what it is, how big, how many are sharing it, and where to get it. */
@Composable
private fun SearchRow(result: SearchResult, onDownload: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .background(AppTheme.Palette.surface, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                result.name,
                fontSize = 12.sp,
                color = AppTheme.Palette.onSurface,
                // The whole name, over as many lines as it takes.
                //
                // This was one line with an ellipsis, which is the worst of both: the
                // part that identifies a release is at the *front* of the name and the
                // part that says what it is - resolution, codec, audio, group - is at
                // the back, so cutting the tail removed exactly the information that
                // tells one result from another. Two releases of the same film with
                // different encodes came out as the same row of text.
                //
                // It wraps rather than being selectable or hovering, because a person
                // comparing results is reading them, not copying them.
                softWrap = true,
                overflow = TextOverflow.Clip
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DisplayFormat.bytes(result.sizeBytes),
                    fontSize = 10.sp,
                    color = AppTheme.Palette.muted
                )
                Spacer(Modifier.width(10.dp))
                // "Unknown" rather than "0 seeders" for a source that publishes no counts.
                // A zero there is not a dead torrent, it is a site that does not say, and
                // writing 0 next to it invites the user to skip a live release.
                Text(
                    if (result.reportsHealth) {
                        "${result.seeders} seeders"
                    } else {
                        "seeders unknown"
                    },
                    fontSize = 10.sp,
                    color = if (result.reportsHealth && result.seeders > 0) {
                        AppTheme.Palette.accent
                    } else {
                        AppTheme.Palette.faint
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(result.source, fontSize = 10.sp, color = AppTheme.Palette.faint)
            }
        }
        Spacer(Modifier.width(10.dp))
        Button(onClick = onDownload, modifier = Modifier.height(30.dp)) {
            Text("Download", fontSize = 11.sp)
        }
    }
}
