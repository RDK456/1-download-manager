package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.File
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Magnet
import com.composables.icons.lucide.CirclePlay
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.ui.expressive.model.DownloadRowModel
import com.downloadhub.app.ui.expressive.model.spoken
import com.downloadhub.app.ui.expressive.shape.StateShape
import com.downloadhub.app.ui.theme.Mono
import androidx.compose.ui.graphics.vector.ImageVector

/** Where the row's own text and numbers sit once the system font is large. */
private const val STACK_FROM_FONT_SCALE = 1.5f

/**
 * One download in the list: a segmented list item with a state shape, the file name, a
 * status line, speed and time in mono, and a wavy bar.
 *
 * [index] and [count] place it in its group, which sets the corners (large on the ends,
 * small between). Pressing or selecting rounds them fully, on the theme's spring.
 *
 * [spin] is decided by the list, which lets only the first few transferring rows turn.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadRow(
    model: DownloadRowModel,
    index: Int,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    spin: Boolean = true,
    onPauseOrResume: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val big = LocalDensity.current.fontScale >= STACK_FROM_FONT_SCALE
    val actions = buildList {
        if (onPauseOrResume != null && (model.canPause || model.canResume)) {
            add(CustomAccessibilityAction(if (model.canPause) "Pause" else "Resume") { onPauseOrResume(); true })
        }
        if (onDelete != null) add(CustomAccessibilityAction("Delete") { onDelete(); true })
    }
    val spoken = model.spoken()
    val (badge, onBadge) = com.downloadhub.app.ui.expressive.shape.badgeColors(model.shape, selected)
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            this.selected = selected
            customActions = actions
        },
        leadingContent = {
            StateShape(
                kind = model.shape,
                color = badge,
                modifier = Modifier.size(48.dp),
                spin = spin && model.shape == com.downloadhub.app.ui.expressive.model.StateShapeKind.COOKIE
            ) {
                Icon(
                    imageVector = if (selected) Lucide.Check else if (model.isError) Lucide.CircleAlert else iconFor(model.source),
                    contentDescription = null,
                    tint = onBadge,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        supportingContent = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        model.headline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (model.isError) scheme.error else scheme.onSurfaceVariant
                    )
                    if (model.detail.isNotEmpty()) {
                        Text(model.detail, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = scheme.onSurfaceVariant)
                    }
                    if (big && model.speed != null) {
                        Text("${model.speed}  ${model.eta}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = scheme.onSurfaceVariant)
                    }
                }
                if (model.bar != com.downloadhub.app.ui.expressive.model.BarStyle.NONE) {
                    Spacer(Modifier.height(6.dp))
                    RowBar(model.bar, model.progress)
                }
            }
        },
        trailingContent = if (model.isError && onRetry != null) {
            { androidx.compose.material3.TextButton(onRetry) { Text("Retry") } }
        } else if (!big && model.speed != null) {
            {
                Column {
                    Text(model.speed, style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono), textAlign = TextAlign.End)
                    Text(model.eta ?: "", style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono), color = scheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
        } else null,
        content = { FileNameText(model.name) }
    )
}

private fun iconFor(source: DownloadSource): ImageVector = when (source) {
    DownloadSource.HTTP -> Lucide.File
    DownloadSource.YOUTUBE -> Lucide.CirclePlay
    DownloadSource.TORRENT -> Lucide.Magnet
}
