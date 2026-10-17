package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.shape.StateShape
import com.downloadhub.app.ui.expressive.shape.badgeColors

/** A short explanation above a list, on a tonal surface so it reads as part of the page, not a caption. */
@Composable
fun HintCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A group heading inside a long page. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * The row every list in the app uses: a morphing badge, a title that may wrap, an optional
 * second line, and whatever trails. [index] and [count] give the segmented shape (round ends,
 * tight gaps).
 */
@Composable
fun BadgeRow(
    title: String,
    index: Int,
    count: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    kind: StateShapeKind = StateShapeKind.CIRCLE,
    supporting: String? = null,
    titleLines: Int = 2,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val (fill, onFill) = badgeColors(kind)
    val badge: @Composable () -> Unit = {
        StateShape(kind, fill, Modifier.size(44.dp), spin = false) {
            Icon(icon, contentDescription = null, tint = onFill, modifier = Modifier.size(20.dp))
        }
    }
    val body: @Composable () -> Unit = { Text(title, maxLines = titleLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmallEmphasized) }
    val second: (@Composable () -> Unit)? = supporting?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    SegmentedListItem(onClick = onClick, shapes = shapes, modifier = modifier, leadingContent = badge, supportingContent = second, trailingContent = trailing, content = body)
}
