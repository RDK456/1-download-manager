package com.downloadhub.app.ui.expressive.screen.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
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
import com.downloadhub.app.ui.expressive.component.EmptyAction
import com.downloadhub.app.ui.expressive.component.EmptyState
import com.downloadhub.app.ui.expressive.component.FilterGroup
import com.downloadhub.app.ui.expressive.component.FilterOption
import com.downloadhub.app.ui.expressive.component.NoticeBanner
import com.downloadhub.app.ui.expressive.model.BannerKind
import com.downloadhub.app.ui.expressive.model.RowConditions
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.planBanners
import com.downloadhub.app.ui.expressive.model.toRowModel
import com.downloadhub.app.ui.formatSpeed
import com.downloadhub.core.LibraryKind

/** At most this many rows turn at once, so a long queue stays calm and cheap to draw. */
private const val MAX_SPINNING = 6

/**
 * The Downloads and Torrents tabs.
 *
 * [items] arrive already narrowed by kind, search and filter by the view model, so what is
 * decided here is only the in-progress / finished split and how it looks. Holding a row
 * starts selection; the floating toolbar then acts on every selected row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen(
    items: List<DownloadEntity>,
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
    onDelete: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onAdd: () -> Unit,
    isTorrents: Boolean,
    modifier: Modifier = Modifier
) {
    var showDone by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

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
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (selecting) {
                item(key = "selection") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ selected = emptyList() }) { Icon(Lucide.X, contentDescription = "Clear selection") }
                        Text("${liveSelection.size} selected", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.weight(1f))
                        IconButton({ selected = shown.map { it.id } }) { Icon(Lucide.ListChecks, contentDescription = "Select all") }
                    }
                }
            } else {
                item(key = "banners") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = if (plan.shown.isEmpty()) 0.dp else 8.dp)) {
                        plan.shown.forEach { banner ->
                            NoticeBanner(banner, onAction = { onBannerAction(banner, it) }, onDismiss = { onBannerDismiss(banner) })
                        }
                        if (plan.hiddenCount > 0) {
                            TextButton(onBannerExpand) { Text(if (plan.hiddenCount == 1) "1 more notice" else "${plan.hiddenCount} more notices") }
                        }
                    }
                }
                item(key = "summary") {
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
                item(key = "search") {
                    TextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = TextFieldDefaults.roundedShape,
                        colors = TextFieldDefaults.tonalColors(),
                        placeholder = { Text(if (isTorrents) "Search torrents" else "Search downloads") },
                        leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                    )
                }
                if (showKinds) {
                    item(key = "kinds") {
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
                item(key = "state") {
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
            if (shown.isEmpty()) {
                item(key = "empty") {
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
            } else {
                itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
                    val model = remember(item, conditions) { item.toRowModel(conditions) }
                    DownloadRow(
                        model = model,
                        index = index,
                        count = shown.size,
                        selected = item.id in liveSelection,
                        onClick = { if (selecting) toggle(item.id) else onOpen(item.id) },
                        onLongClick = { toggle(item.id) },
                        spin = item.id in spinning,
                        onPauseOrResume = when {
                            item.status.canPause -> ({ onPause(item.id) })
                            item.status.canResume -> ({ onResume(item.id) })
                            else -> null
                        },
                        onDelete = { selected = listOf(item.id); confirmDelete = true },
                        modifier = Modifier.animateItem()
                    )
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
                IconButton({ confirmDelete = true }) { Icon(Lucide.Trash2, contentDescription = "Delete selected") }
            }
        }
    }

    if (confirmDelete && selectedItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (selectedItems.size == 1) "Delete this download?" else "Delete ${selectedItems.size} downloads?") },
            text = { Text("The files are removed from this phone too. This cannot be undone.") },
            confirmButton = {
                TextButton({
                    selectedItems.forEach { onDelete(it.id) }
                    selected = emptyList()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } }
        )
    }
}
