package com.downloadhub.app.ui.expressive.screen.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.List
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Magnet
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.canPause
import com.downloadhub.app.data.model.canResume
import com.downloadhub.app.ui.TabSummary
import com.downloadhub.app.ui.expressive.component.DownloadRow
import com.downloadhub.app.ui.expressive.component.DownloadTile
import com.downloadhub.app.ui.expressive.component.EmptyAction
import com.downloadhub.app.ui.expressive.component.EmptyState
import com.downloadhub.app.ui.expressive.component.FilterGroup
import com.downloadhub.app.ui.expressive.component.FilterOption
import com.downloadhub.app.ui.expressive.component.NoticeBanner
import com.downloadhub.app.ui.expressive.component.SkeletonRow
import com.downloadhub.app.ui.expressive.model.BannerKind
import com.downloadhub.app.ui.expressive.model.RowConditions
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.planBanners
import com.downloadhub.app.ui.expressive.model.toRowModel
import com.downloadhub.app.ui.formatSpeed
import com.downloadhub.core.LibraryKind

/** At most this many rows turn at once, so a long queue stays calm and cheap to draw. */
private const val MAX_SPINNING = 6

/** Placeholder rows shown while the database has not answered yet. */
private const val SKELETON_ROWS = 6

/**
 * The Downloads and Torrents tabs.
 *
 * [items] arrive already narrowed by kind, search and filter by the view model, so what is
 * decided here is only the in-progress / finished split and how it looks. Holding a row
 * starts selection; the floating toolbar then acts on every selected row. Everything lives
 * in one lazy grid: one column for the list, several for the grid layout, with the header
 * pieces spanning the full width.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(
    items: List<DownloadEntity>,
    loaded: Boolean,
    summary: TabSummary,
    conditions: RowConditions,
    activeBanners: Set<BannerKind>,
    bannerExpanded: Boolean,
    dismissedBanners: Set<BannerKind>,
    onBannerAction: (BannerKind, String) -> Unit,
    onBannerDismiss: (BannerKind) -> Unit,
    onBannerExpand: () -> Unit,
    kind: LibraryKind,
    kindCounts: Map<LibraryKind, Int>,
    showKinds: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onKindChange: (LibraryKind) -> Unit,
    onOpen: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
    onRetry: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onAdd: () -> Unit,
    isTorrents: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    var grid by remember { mutableStateOf(prefs.getBoolean("downloads_grid", false)) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf<List<String>?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    // Checked again every ten seconds, so a transfer that goes quiet is called stalled
    // without anything else having to change.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(10_000)
            value = System.currentTimeMillis()
        }
    }

    val doneCount = items.count { it.status == DownloadStatus.COMPLETED }
    val shown = items.filter { (it.status == DownloadStatus.COMPLETED) == showDone }
    // Rows that disappeared (finished, deleted elsewhere) must not stay selected.
    val liveSelection = selected.filter { id -> items.any { it.id == id } }
    val selecting = liveSelection.isNotEmpty()
    val selectedItems = items.filter { it.id in liveSelection }

    fun toggle(id: String) {
        selected = if (id in liveSelection) liveSelection - id else liveSelection + id
    }

    Box(modifier.fillMaxSize()) {
        // The first rows to be transferring are the ones that turn.
        val spinning = remember(shown) {
            shown.filter { it.status == DownloadStatus.RUNNING }.take(MAX_SPINNING).map { it.id }.toSet()
        }
        val plan = planBanners(activeBanners, dismissedBanners, bannerExpanded)
        LazyVerticalGrid(
            columns = if (grid) GridCells.Adaptive(168.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid) 8.dp else 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selecting) {
                item(key = "selection", span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ selected = emptyList() }) { Icon(Lucide.X, contentDescription = "Clear selection") }
                        Text("${liveSelection.size} selected", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                        IconButton({ selected = shown.map { it.id } }) { Icon(Lucide.ListChecks, contentDescription = "Select all") }
                    }
                }
            } else {
                item(key = "banners", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = if (plan.shown.isEmpty()) 0.dp else 8.dp)) {
                        plan.shown.forEach { banner ->
                            NoticeBanner(banner, onAction = { onBannerAction(banner, it) }, onDismiss = { onBannerDismiss(banner) })
                        }
                        if (plan.hiddenCount > 0) {
                            TextButton(onBannerExpand) { Text(if (plan.hiddenCount == 1) "1 more notice" else "${plan.hiddenCount} more notices") }
                        }
                    }
                }
                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                    if (summary.active > 0 || summary.paused > 0) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (summary.active > 0) "${summary.active} active · ${formatSpeed(summary.speed)}" else "${summary.paused} paused",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (summary.active > 0) TextButton(onPauseAll) { Text("Pause all") }
                            else TextButton(onResumeAll) { Text("Resume all") }
                        }
                    }
                }
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = TextFieldDefaults.roundedShape,
                            colors = TextFieldDefaults.tonalColors(),
                            placeholder = { Text(if (isTorrents) "Search torrents" else "Search downloads") },
                            leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                        )
                        IconButton({
                            grid = !grid
                            prefs.edit().putBoolean("downloads_grid", grid).apply()
                        }) {
                            Icon(if (grid) Lucide.List else Lucide.LayoutGrid, contentDescription = if (grid) "Show as list" else "Show as grid")
                        }
                    }
                }
                if (showKinds) {
                    item(key = "kinds", span = { GridItemSpan(maxLineSpan) }) {
                        FilterGroup(
                            options = listOf(LibraryKind.ALL, LibraryKind.NORMAL, LibraryKind.YOUTUBE, LibraryKind.TORRENT).map {
                                FilterOption(it, when (it) { LibraryKind.ALL -> "All"; LibraryKind.NORMAL -> "Files"; else -> it.label }, kindCounts[it])
                            },
                            selected = kind,
                            onSelect = onKindChange,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
                item(key = "state", span = { GridItemSpan(maxLineSpan) }) {
                    FilterGroup(
                        options = listOf(
                            FilterOption(false, "In progress", items.size - doneCount),
                            FilterOption(true, "Finished", doneCount)
                        ),
                        selected = showDone,
                        onSelect = { showDone = it },
                        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
                    )
                }
            }
            when {
                !loaded -> {
                    item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.semantics { contentDescription = "Loading your downloads" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(SKELETON_ROWS) { SkeletonRow(it, SKELETON_ROWS) }
                        }
                    }
                }
                shown.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    val noneAtAll = items.isEmpty()
                    EmptyState(
                        shape = if (noneAtAll) StateShapeKind.COOKIE else StateShapeKind.BURST,
                        icon = if (isTorrents) Lucide.Magnet else Lucide.Download,
                        title = when {
                            noneAtAll && query.isNotBlank() -> "Nothing matches \"$query\""
                            noneAtAll && isTorrents -> "No torrents yet"
                            noneAtAll -> "Nothing downloading"
                            showDone -> "Nothing finished yet"
                            else -> "All done"
                        },
                        body = when {
                            noneAtAll && query.isNotBlank() -> "Check the spelling, or clear the search."
                            noneAtAll && isTorrents -> "Paste a magnet link or open a .torrent file."
                            noneAtAll -> "Paste a link, share one from your browser, or find something in Discover."
                            showDone -> "Finished downloads land here."
                            else -> "Nothing is in progress. Finished downloads are under Finished."
                        },
                        primary = if (noneAtAll && query.isBlank()) EmptyAction("Add a link", onAdd) else null,
                        secondary = if (noneAtAll && query.isNotBlank()) EmptyAction("Clear search", { onQueryChange("") }) else null
                    )
                }
                else -> itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
                    val model = remember(item, conditions, now / 10_000) { item.toRowModel(conditions, now) }
                    val pauseOrResume: (() -> Unit)? = when {
                        item.status.canPause -> ({ onPause(item.id) })
                        item.status.canResume -> ({ onResume(item.id) })
                        else -> null
                    }
                    if (grid) {
                        DownloadTile(
                            model = model,
                            selected = item.id in liveSelection,
                            onClick = { if (selecting) toggle(item.id) else onOpen(item.id) },
                            onLongClick = { toggle(item.id) },
                            spin = item.id in spinning,
                            onPauseOrResume = pauseOrResume,
                            onDelete = { confirmDelete = listOf(item.id) },
                            modifier = Modifier.animateItem()
                        )
                    } else {
                        DownloadRow(
                            model = model,
                            index = index,
                            count = shown.size,
                            selected = item.id in liveSelection,
                            onClick = { if (selecting) toggle(item.id) else onOpen(item.id) },
                            onLongClick = { toggle(item.id) },
                            spin = item.id in spinning,
                            onPauseOrResume = pauseOrResume,
                            onDelete = { confirmDelete = listOf(item.id) },
                            onRetry = { onRetry(item.id) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
        if (selecting) {
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
                IconButton({
                    selectedItems.filter { it.status.canPause }.forEach { onPause(it.id) }
                    selected = emptyList()
                }) { Icon(Lucide.Pause, contentDescription = "Pause selected") }
                IconButton({
                    selectedItems.filter { it.status.canResume }.forEach { onResume(it.id) }
                    selected = emptyList()
                }) { Icon(Lucide.Play, contentDescription = "Resume selected") }
                IconButton({ confirmDelete = liveSelection }) { Icon(Lucide.Trash2, contentDescription = "Delete selected") }
            }
        }
    }

    confirmDelete?.let { ids ->
        val count = ids.count { id -> items.any { it.id == id } }
        if (count == 0) {
            confirmDelete = null
        } else {
            AlertDialog(
                onDismissRequest = { confirmDelete = null },
                title = { Text(if (count == 1) "Delete this download?" else "Delete $count downloads?") },
                text = { Text("The files are removed from this phone too. You can undo for a few seconds.") },
                confirmButton = {
                    TextButton({
                        onDelete(ids)
                        selected = emptyList()
                        confirmDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton({ confirmDelete = null }) { Text("Keep") } }
            )
        }
    }
}
