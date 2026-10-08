package com.downloadhub.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.label

/**
 * Status and category selection, reached from the filter button in the top bar.
 * [showCategories] is off in the Torrents tab, where every item is a torrent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filter: DownloadFilter,
    category: DownloadCategory?,
    categoryCounts: Map<DownloadCategory, Int>,
    showCategories: Boolean,
    onFilterChange: (DownloadFilter) -> Unit,
    onCategoryChange: (DownloadCategory?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // A sheet is only as tall as the screen allows; anything past that has to
                // scroll or it is simply cut off (a short phone, or one held sideways).
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                if (filter != DownloadFilter.ALL || category != null) {
                    TextButton(onClick = onReset) {
                        Text("Reset")
                    }
                }
            }

            Text("Status", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DownloadFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { onFilterChange(option) },
                        label = { Text(filterLabel(option)) }
                    )
                }
            }

            if (showCategories) {
                Spacer(Modifier.height(4.dp))
                Text("File category", style = MaterialTheme.typography.titleSmall)
                CategoryGrid(
                    selected = category,
                    counts = categoryCounts,
                    onSelect = onCategoryChange
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Apply")
            }
        }
    }
}

@Composable
private fun CategoryGrid(
    selected: DownloadCategory?,
    counts: Map<DownloadCategory, Int>,
    onSelect: (DownloadCategory?) -> Unit
) {
    val options = listOf<DownloadCategory?>(null) + DownloadCategory.entries
        .filterNot { it == DownloadCategory.ARCHIVE }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowOptions.forEach { option ->
                    val label = option?.label ?: "All types"
                    val count = if (option == null) {
                        counts.values.sum()
                    } else {
                        (counts[option] ?: 0) +
                            if (option == DownloadCategory.COMPRESSED) {
                                counts[DownloadCategory.ARCHIVE] ?: 0
                            } else {
                                0
                            }
                    }
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        enabled = option == null || count > 0,
                        label = { Text(if (count > 0) "$label ($count)" else label) }
                    )
                }
            }
        }
    }
}

/** Shared label helper so the filter sheet and the active-filter chips agree. */
fun filterLabel(filter: DownloadFilter): String = when (filter) {
    DownloadFilter.ALL -> "All"
    DownloadFilter.ACTIVE -> "Active"
    DownloadFilter.COMPLETED -> "Completed"
}
