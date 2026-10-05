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
import com.downloadhub.core.SearchFilter
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
/**
 * A search, kept outside the panel that shows it.
 *
 * The state used to be `remember`ed inside [SearchPanel], which meant it belonged to
 * the panel's place in the composition rather than to the search. Queueing a download
 * closed the panel, the panel left the composition, and the query and every result went
 * with it - so returning to Search showed an empty box and a person comparing five
 * releases had to search again to get back to them, having no way to know they had
 * just been there.
 *
 * Holding it here instead means the panel can be opened and closed as often as is
 * wanted and the search is still there when it comes back. `remember`ed once by
 * [LibraryScreen] and handed down.
 */
class SearchPanelState {
    var query by mutableStateOf("")
    var group by mutableStateOf(SearchGroup.MOVIES)
    var results by mutableStateOf(emptyList<SearchResult>())
    var outcome by mutableStateOf<SearchOutcome?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    // A plain `mutableStateOf`, not `remember`: this class is not a composable function,
    // and the whole point is that the state outlives the composition rather than being
    // created by it. `remember` inside it would not compile, which is the right answer.
    var job by mutableStateOf<Job?>(null)

    /** Which sources are being shown. Empty means all of them. */
    var sources by mutableStateOf(emptySet<String>())

    /** The rows actually listed: [results] narrowed to [sources]. */
    val visible: List<SearchResult> get() = SearchFilter.apply(results, sources)

    /** Whether there is anything to clear. */
    val hasAnything: Boolean
        get() = query.isNotBlank() || results.isNotEmpty() || sources.isNotEmpty() ||
            message != null

    /**
     * Puts the panel back to how it looks before anything was searched for.
     *
     * An explicit clear, because a search that empties itself on some other action is
     * how it used to behave and nobody could tell which parts were deliberate.
     */
    fun clear() {
        job?.cancel()
        query = ""
        results = emptyList()
        outcome = null
        busy = false
        message = null
        sources = emptySet()
    }

    /** Toggles one source, dropping to "all" when the last one is switched off. */
    fun toggleSource(id: String) {
        sources = if (id in sources) sources - id else sources + id
    }
}

@Composable
fun SearchPanel(
    state: SearchPanelState,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val query = state.query
    val group = state.group
    val results = state.results
    val outcome = state.outcome
    val busy = state.busy
    val message = state.message
    val scope = rememberCoroutineScope()
    val sources = remember { defaultSearchSources() }

    fun run() {
        val trimmed = state.query.trim()
        if (!isSearchable(trimmed)) {
            state.message = "Type at least two characters to search."
            return
        }
        // A new search replaces the old one rather than running beside it: two searches'
        // results arriving into one list is a list with rows from both, which is neither.
        state.job?.cancel()
        state.message = null
        state.busy = true
        state.results = emptyList()
        state.outcome = null
        // A filter from the previous search is not carried over. Someone who was looking
        // at one site and now searches for something else wants results, not the empty
        // list that site happens to have for a word it does not index.
        state.sources = emptySet()
        state.job = scope.launch {
            val answer = searchSources(
                sources = sources,
                query = trimmed,
                group = state.group,
                onPartial = { partial -> state.results = sortSearchResults(partial) }
            )
            state.outcome = answer
            state.results = sortSearchResults(answer.results)
            state.busy = false
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
                onValueChange = { state.query = it },
                label = { Text("Search", fontSize = 12.sp) },
                placeholder = { Text("What are you looking for?", fontSize = 12.sp, color = AppTheme.Palette.faint) },
                singleLine = true,
                modifier = Modifier.weight(1f).onEnter(!busy) { run() }
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
            // Clear, and only while there is something to clear.
            //
            // This is what the search used to do by itself, quietly, when a download was
            // queued. Being able to throw a search away is not the complaint - having it
            // thrown away for you, with no way back and no way to tell that it had been,
            // is. An always-present button would be noise on an empty panel, so it
            // appears with the search rather than before it.
            if (state.hasAnything) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .background(AppTheme.Palette.surface, RoundedCornerShape(4.dp))
                        .clickable { state.clear() }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        "Clear",
                        fontSize = 11.sp,
                        color = AppTheme.Palette.muted
                    )
                }
            }
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
                            state.group = entry
                            if (isSearchable(state.query)) run()
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

        // Which sources are being shown.
        //
        // Only once there are results, because a filter listing sites that have not been
        // asked yet is a list of tabs on an empty page. Each carries its own count, so
        // switching one off is a decision about how many rows are being hidden, made
        // before it is made.
        val visible = state.visible
        val facets = remember(results) { SearchFilter.sources(results, SOURCE_LABELS) }
        if (facets.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sources",
                    fontSize = 10.sp,
                    color = AppTheme.Palette.faint
                )
                Spacer(Modifier.width(8.dp))
                facets.forEach { facet ->
                    // Selected is "in the filter", so with nothing chosen every chip
                    // reads as on. Chosen sources are highlighted rather than dimmed,
                    // which is the same convention as the category tabs above it.
                    val on = facet.id in state.sources || state.sources.isEmpty()
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .background(
                                if (on) AppTheme.Palette.accentContainer else AppTheme.Palette.surface,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { state.toggleSource(facet.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${facet.label} ${facet.count}",
                            fontSize = 10.sp,
                            color = if (on) AppTheme.Palette.onAccent else AppTheme.Palette.muted
                        )
                    }
                }
                if (state.sources.isNotEmpty() && visible.size != results.size) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${visible.size} of ${results.size}",
                        fontSize = 10.sp,
                        color = AppTheme.Palette.faint
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                results.isEmpty() && busy -> CenteredNote("Asking every source...")
                results.isEmpty() && message == null && outcome == null ->
                    CenteredNote("Search the sources for something to download.")
                results.isEmpty() -> CenteredNote("Nothing found for \"${state.query.trim()}\".")
                visible.isEmpty() -> CenteredNote(
                    "Nothing from the chosen sources. All ${results.size} results are hidden."
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.source + it.infoHash }) { result ->
                        SearchRow(result) { onPick(result.magnet) }
                    }
                }
            }
        }
    }
}

/**
 * Source id to the name shown on a filter chip.
 *
 * Taken from the sources themselves rather than typed out again here, so a source that
 * renames itself does not end up with two spellings - one in its results and one on
 * its filter chip. Ids with no entry fall back to the id, which is at least honest.
 */
private val SOURCE_LABELS: Map<String, String> = defaultSearchSources()
    .associate { it.id to it.label }

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
                        "${result.seeders} seeds · ${result.leechers} peers"
                    } else {
                        "seeds/peers unknown"
                    },
                    fontSize = 10.sp,
                    color = if (result.reportsHealth && result.seeders > 0) {
                        AppTheme.Palette.accent
                    } else {
                        AppTheme.Palette.faint
                    }
                )
                Spacer(Modifier.width(10.dp))
                // The source's own name, not its id: `thepiratebay` is a label for a
                // database column and not something to read on a row, and the same
                // spelling as the filter chip above is what makes that chip legible.
                Text(
                    SOURCE_LABELS[result.source] ?: result.source,
                    fontSize = 10.sp,
                    color = AppTheme.Palette.faint
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Button(onClick = onDownload, modifier = Modifier.height(30.dp)) {
            Text("Download", fontSize = 11.sp)
        }
    }
}
