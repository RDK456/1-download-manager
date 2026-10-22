package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.downloadhub.app.ui.SettingsSection
import com.downloadhub.app.ui.theme.UiGeneration

/**
 * The switch between the redesign and the classic look. It sits at the top of Settings in
 * both, so whichever one is showing can get to the other. The change is immediate.
 */
@Composable
fun InterfaceStyleSection() {
    val context = LocalContext.current
    SettingsSection("Interface") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (UiGeneration.expressive) {
                FilterGroup(
                    options = listOf(FilterOption(true, "New"), FilterOption(false, "Classic")),
                    selected = true,
                    onSelect = { UiGeneration.set(context, it) },
                    unselectedContainer = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            } else {
                // The classic palette has no tonal surfaces, so the grouped buttons would come
                // out in Material's default lavender. Plain chips take the chosen colour theme.
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = false, onClick = { UiGeneration.set(context, true) }, label = { Text("New") })
                    androidx.compose.material3.FilterChip(selected = true, onClick = {}, label = { Text("Classic") })
                }
            }
            Text(
                if (UiGeneration.expressive) "The new look: shapes that morph, springy motion, tonal colour."
                else "The original look: hardware-style panels and keys.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One-time chooser shown on a fresh install or the first launch after updating, so the user
 * picks the new or the classic look. Either choice can be changed later in Settings.
 */
@Composable
fun InterfaceChooserDialog() {
    if (UiGeneration.chosen) return
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        title = { Text("Choose your look") },
        text = { Text("New: shapes that morph, springy motion, tonal colour.\nClassic: the original hardware-style panels.\n\nYou can change this any time in Settings > Interface.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { UiGeneration.set(context, true) }) { Text("New") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { UiGeneration.set(context, false) }) { Text("Classic") } }
    )
}
