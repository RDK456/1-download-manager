package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.rememberLazyListState
import com.downloadhub.core.ArchiveOrg
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
import com.downloadhub.core.SearchFilter
import com.downloadhub.core.SearchSourceCount
import com.downloadhub.core.sortSearchResults
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TextButton

/**
 * Find something to download.
 *
 * The same search the Windows app does, over the same sources, with the same rules - the
 * logic is in `:core` and this is the screen around it. A result's Download button hands
 * its magnet to the add sheet, which is the same sheet a pasted magnet goes through, so the
 * file list and the folder are already handled.
 */
/**
 * A search, kept outside the screen that shows it.
 *
 * The state used to be `remember`ed inside [SearchScreen], which meant it belonged to
 * the screen's place in the composition rather than to the search. Tapping Download on
 * a result navigated to the downloads list, the search screen left the composition, and
 * the query and every result went with it - so coming back to Search showed an empty
 * box, and a person comparing five releases had to search again to get back to them.
 *
 * This is held by the app's destination state instead, so navigating away and back
 * costs nothing. That matters more here than on the desktop, because the add sheet
 * takes over the screen and puts back exactly the way a person gets to compare one
 * torrent against the four next to it.
 */
class SearchScreenState {
    var query by mutableStateOf("")
    var group by mutableStateOf(SearchGroup.MOVIES)
    var results by mutableStateOf(emptyList<SearchResult>())
    var outcome by mutableStateOf<SearchOutcome?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
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
     * Puts the screen back to how it looks before anything was searched for.
     *
     * An explicit clear, because the search used to empty itself on navigating away and
     * nobody could tell which parts of that were deliberate.
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
fun SearchScreen(
    state: SearchScreenState,
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
        // A new search replaces the old one. Two searches' answers arriving into one list
        // is a list belonging to neither.
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

    val visible = state.visible
    val facets = remember(results) { SearchFilter.sources(results, SOURCE_LABELS) }
    // The search box, the filters and the notes. With results on screen they are the first
    // row of the list, so a short phone or one held sideways is not left with a sliver of
    // results under a fixed header. Before there are results there is nothing to scroll.
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { state.query = it },
                    label = { Text("Search") },
                    placeholder = { Text("What are you looking for?") },
                    leadingIcon = { androidx.compose.material3.Icon(Lucide.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (!busy) run() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                CategoryAndSearchRow(
                    group = group,
                    onGroup = { entry ->
                        state.group = entry
                        if (isSearchable(state.query)) run()
                    },
                    busy = busy,
                    onSearch = { run() },
                    // Present only once there is something to throw away. This is what the
                    // search used to do by itself, quietly, on navigating away.
                    canClear = state.hasAnything,
                    onClear = { state.clear() }
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

            // Which sources are being shown. Only once there are results - a filter listing sites
            // that have not been asked yet is a row of tabs on an empty page - and each chip
            // carries its own count, so switching one off is a decision about how many rows
            // are being hidden, made before it is made.
            if (facets.size > 1) {
                SourceFilterRow(
                    facets = facets,
                    selected = state.sources,
                    hidden = results.size - visible.size,
                    onToggle = { state.toggleSource(it) }
                )
            }
        }
    }
    if (results.isNotEmpty() && visible.isNotEmpty()) {
        LazyColumn(modifier.fillMaxSize()) {
            item { header() }
            items(visible, key = { it.source + it.infoHash }) { result ->
                SearchResultCard(result, SOURCE_LABELS[result.source]) {
                    onPick(result.magnet)
                }
            }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            header()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    results.isEmpty() && busy -> Centre("Asking every source...")
                    results.isEmpty() && message == null && outcome == null -> TorrentPicks(onPick)
                    results.isEmpty() -> Centre("Nothing found for \"${query.trim()}\".")
                    else -> Centre("Nothing from the chosen sources. All ${results.size} results are hidden.")
                }
            }
        }
    }
}

/**
 * Source id to the name shown on a filter chip.
 *
 * Taken from the sources themselves rather than typed out again, so a source that
 * renames itself does not end up with two spellings - one in its results and one on its
 * chip.
 */
private val SOURCE_LABELS: Map<String, String> = defaultSearchSources()
    .associate { it.id to it.label }

/** The source chips, horizontally scrollable so a long list of sites is still one row. */
@Composable
private fun SourceFilterRow(
    facets: List<SearchSourceCount>,
    selected: Set<String>,
    hidden: Int,
    onToggle: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Sources",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            facets.forEach { facet ->
                // Selected is "in the filter", so with nothing chosen every chip reads
                // as on - and that means "all of them", which is what it does.
                val on = facet.id in selected || selected.isEmpty()
                FilterChip(
                    selected = on,
                    onClick = { onToggle(facet.id) },
                    label = { Text("${facet.label} ${facet.count}", fontSize = 11.sp) }
                )
            }
        }
        if (hidden > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$hidden hidden",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * What the search shows before anything is typed: public-domain films, concerts and
 * audiobooks from the Internet Archive, each a real torrent the archive seeds. A new
 * random draw every time the screen opens, and more as the list is scrolled.
 */
@Composable
private fun TorrentPicks(onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var picks by remember { mutableStateOf(emptyList<SearchResult>()) }
    var loading by remember { mutableStateOf(false) }
    suspend fun more(reset: Boolean = false) {
        if (loading) return
        loading = true
        val next = runCatching { ArchiveOrg.torrentPicks() }.getOrDefault(emptyList())
        picks = (if (reset) next else picks + next).distinctBy { it.infoHash }
        loading = false
    }
    LaunchedEffect(Unit) { more(reset = true) }

    if (picks.isEmpty()) {
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Centre("Search the sources for something to download.")
        return
    }
    LoadMoreAtEnd(list) { more() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Free picks from the Internet Archive", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { list.scrollToItem(0); more(reset = true) } }, enabled = !loading) {
                Icon(Lucide.Shuffle, contentDescription = "Show different picks")
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(picks, key = { it.infoHash }) { pick ->
                SearchResultCard(pick, "Internet Archive") { onPick(pick.magnet) }
            }
            if (loading) item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
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
private fun SearchResultCard(
    result: SearchResult,
    sourceLabel: String?,
    onDownload: () -> Unit
) {
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
                        if (result.reportsHealth) "${result.seeders} seeds · ${result.leechers} peers" else "seeds/peers unknown",
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
                    // The source's own name, not its id: `thepiratebay` is a label for a
                    // database column and not something to read on a card, and it matches
                    // the spelling on the filter chip above.
                    Text(
                        sourceLabel ?: result.source,
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
    onSearch: () -> Unit,
    canClear: Boolean,
    onClear: () -> Unit
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
        // Only once there is something to clear. The search used to empty itself on its
        // own when a download was queued, which is exactly what this makes deliberate.
        if (canClear) {
            TextButton(onClick = onClear) { Text("Clear", fontSize = 11.sp) }
        }
    }
}
