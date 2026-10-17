package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.shape.StateShape

class EmptyAction(val label: String, val onClick: () -> Unit, val tonal: Boolean = false)

/**
 * First run, filtered-empty, no results, load failed: each gets its own shape and words and
 * at most one or two actions. The art is the same morphing badge the rows use, at a larger
 * size and at rest.
 */
@Composable
fun EmptyState(
    shape: StateShapeKind,
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primary: EmptyAction? = null,
    secondary: EmptyAction? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StateShape(shape, scheme.primaryContainer, Modifier.size(112.dp), spin = false) {
            Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (primary != null) {
            if (primary.tonal) FilledTonalButton(primary.onClick) { Text(primary.label) }
            else Button(primary.onClick) { Text(primary.label) }
        }
        if (secondary != null) {
            androidx.compose.material3.TextButton(secondary.onClick) { Text(secondary.label) }
        }
    }
}
