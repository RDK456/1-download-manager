package com.downloadhub.app.ui.expressive.screen.add

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.downloadhub.app.ui.expressive.model.AddChoice
import com.downloadhub.app.ui.expressive.model.label

/**
 * The add button with a second half. The big half adds now; the arrow opens the other ways
 * to add: paused, or into one of the named queues. The arrow half turns into a circle while
 * its menu is open and the chevron turns over, both on the theme's spring.
 */
@Composable
fun AddSplitButton(
    label: String,
    enabled: Boolean,
    choices: List<AddChoice>,
    onAdd: () -> Unit,
    onChoose: (AddChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    // The two halves sit in a row so the big half can take the width; the layout the library
    // offers sizes both to their contents and leaves the button short of the sheet's edges.
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SplitButtonDefaults.Spacing)) {
        SplitButtonDefaults.LeadingButton(onClick = onAdd, enabled = enabled, modifier = Modifier.weight(1f)) { Text(label) }
        Box {
            SplitButtonDefaults.TrailingButton(
                checked = open,
                onCheckedChange = { open = it },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "More ways to add" }
            ) {
                Icon(Lucide.ChevronDown, contentDescription = null, modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.label()) }, onClick = { open = false; onChoose(choice) })
                }
            }
        }
    }
}
