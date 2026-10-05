package com.downloadhub.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Enter (either one) runs [action] while focus is on this element or inside it.
 *
 * A preview handler, so it runs before a single-line text field gets to swallow the key.
 */
internal fun Modifier.onEnter(enabled: Boolean = true, action: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    val enter = e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)
    if (enter && enabled) action()
    enter && enabled
}

/**
 * A small heading inside the options column.
 *
 * One definition rather than a `Spacer` either side of a `Text`, because that is how the
 * gap above one heading came to be a different size from the gap below it.
 */
@Composable
internal fun SectionLabel(label: String) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

/**
 * The stop-sharing condition, as one dropdown.
 *
 * Four stacked radio buttons was a third of the options column for a choice that is only
 * ever one of four, and it is what pushed the file list's own controls off the side of
 * the dialog once the two columns were side by side. The chosen value is unchanged - the
 * same four conditions, in the same order - so nothing about the setting is different;
 * only how it is reached.
 */
@Composable
internal fun StopDropdown(selected: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // "Stop at 100%" is first after None because it is the one people reach for without
    // knowing what a share ratio is.
    val labels = listOf(
        "None",
        "When 100% downloaded",
        "Ratio reached",
        "Uploaded amount reached",
        "Seeding time reached"
    )

    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(labels.getOrElse(selected) { labels[0] }, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (index == selected) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    },
                    onClick = {
                        onChange(index)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * A label and value pair for the information block.
 *
 * A fixed label column so the values line up. An info hash is 40 characters wide and a
 * label that resized with it would push every other value along with it.
 */
@Composable
internal fun InfoPair(label: String, value: String, valueColour: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 11.sp,
            color = valueColour ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The pre-download window's two greys.
 *
 * The theme's `onSurfaceVariant` is right for a caption and wrong for a label you are
 * meant to read, and on this dark scheme the left-hand column was dim enough that the
 * labels and the values looked the same weight. These are the values the rest of the app
 * uses for text it wants read.
 */
internal val DIALOG_PRIMARY: Color get() = AppTheme.Palette.onSurface
internal val DIALOG_SECONDARY: Color get() = AppTheme.Palette.muted
