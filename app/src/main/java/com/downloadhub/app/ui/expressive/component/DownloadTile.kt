package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Magnet
import com.composables.icons.lucide.CirclePlay
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.ui.expressive.model.BarStyle
import com.downloadhub.app.ui.expressive.model.DownloadRowModel
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.model.spoken
import com.downloadhub.app.ui.expressive.shape.StateShape
import com.downloadhub.app.ui.theme.Mono

/**
 * The grid form of a download: the same state shape, name, status and bar as a row, in a
 * tile. Corners are the large shape and round fully while pressed or selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadTile(
    model: DownloadRowModel,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    spin: Boolean = true,
    onPauseOrResume: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val actions = buildList {
        if (onPauseOrResume != null && (model.canPause || model.canResume)) {
            add(CustomAccessibilityAction(if (model.canPause) "Pause" else "Resume") { onPauseOrResume(); true })
        }
        if (onDelete != null) add(CustomAccessibilityAction("Delete") { onDelete(); true })
    }
    val spoken = model.spoken()
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
        modifier = modifier
            .clearAndSetSemantics {
                contentDescription = spoken
                this.selected = selected
                customActions = actions
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StateShape(
                kind = model.shape,
                color = if (selected) scheme.primary else if (model.isError) scheme.errorContainer else scheme.primaryContainer,
                modifier = Modifier.size(44.dp),
                spin = spin && model.shape == StateShapeKind.COOKIE
            ) {
                Icon(
                    if (selected) Lucide.Check else if (model.isError) Lucide.CircleAlert else when (model.source) {
                        DownloadSource.HTTP -> Lucide.File
                        DownloadSource.YOUTUBE -> Lucide.CirclePlay
                        DownloadSource.TORRENT -> Lucide.Magnet
                    },
                    contentDescription = null,
                    tint = if (selected) scheme.onPrimary else if (model.isError) scheme.onErrorContainer else scheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(model.name, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                model.headline,
                style = MaterialTheme.typography.bodySmall,
                color = if (model.isError) scheme.error else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (model.detail.isNotEmpty()) {
                Text(model.detail, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = scheme.onSurfaceVariant, maxLines = 1)
            }
            if (model.bar != BarStyle.NONE) RowBar(model.bar, model.progress)
            if (model.speed != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.speed, style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono))
                    Text(model.eta ?: "", style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono), color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
