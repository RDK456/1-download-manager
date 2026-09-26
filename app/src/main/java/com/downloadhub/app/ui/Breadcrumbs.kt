package com.downloadhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.R

/** One segment of the breadcrumb trail. A `null` handler marks the current page. */
data class Breadcrumb(
    val label: String,
    val onClick: (() -> Unit)? = null
)

/**
 * Horizontal trail shown in the top bar, e.g. `Home > Settings > About us`.
 * Tapping an earlier segment walks back up the trail.
 */
@Composable
fun Breadcrumbs(
    crumbs: List<Breadcrumb>,
    modifier: Modifier = Modifier
) {
    if (crumbs.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            val isCurrent = crumb.onClick == null
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isCurrent) {
                    Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
                } else {
                    Modifier
                        .clickable { crumb.onClick?.invoke() }
                        .padding(vertical = 8.dp, horizontal = 6.dp)
                }
            )
        }
    }
}
