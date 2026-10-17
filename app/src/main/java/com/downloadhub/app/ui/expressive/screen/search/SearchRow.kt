package com.downloadhub.app.ui.expressive.screen.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Magnet
import com.downloadhub.app.ui.expressive.model.searchHealthShape
import com.downloadhub.app.ui.expressive.model.searchSummary
import com.downloadhub.app.ui.expressive.shape.StateShape
import com.downloadhub.app.ui.expressive.shape.badgeColors
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.SearchResult

/**
 * One torrent search result as a segmented list item: a badge whose shape says how healthy
 * the torrent is, the whole name (what tells releases apart is at the end, so it is never
 * cut), size, seeds and source, and a download button. Tapping anywhere on the row does the
 * same as the button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchRow(
    result: SearchResult,
    sourceLabel: String?,
    index: Int,
    count: Int,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = searchHealthShape(result.reportsHealth, result.seeders)
    val (badge, onBadge) = badgeColors(shape)
    val summary = searchSummary(DisplayFormat.bytes(result.sizeBytes), result.reportsHealth, result.seeders, result.leechers, sourceLabel ?: result.source)
    SegmentedListItem(
        onClick = onDownload,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = modifier.padding(horizontal = 16.dp).semantics { contentDescription = result.name + ", " + summary.joinToString(", ") },
        leadingContent = {
            StateShape(shape, badge, Modifier.size(44.dp), spin = false) {
                Icon(Lucide.Magnet, contentDescription = null, tint = onBadge, modifier = Modifier.size(20.dp))
            }
        },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
        },
        trailingContent = {
            // The row already downloads on tap; the button is a bigger target, not a second action to announce.
            androidx.compose.foundation.layout.Box(Modifier.clearAndSetSemantics { }) {
                FilledTonalIconButton(onClick = onDownload) { Icon(Lucide.Download, contentDescription = null) }
            }
        },
        // The whole name, wrapped. What tells one release from another is at the back of it.
        content = { Text(result.name, style = MaterialTheme.typography.titleSmallEmphasized) }
    )
}
