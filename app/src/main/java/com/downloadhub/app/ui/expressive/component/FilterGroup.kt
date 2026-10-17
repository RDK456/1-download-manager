package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

class FilterOption<T>(val value: T, val label: String, val count: Int? = null)

/**
 * A connected group of toggle buttons: one choice, always one selected. The chosen button
 * springs to a pill and its neighbours square off, so the choice is visible in the shape as
 * well as the colour. Counts sit beside the labels.
 */
@Composable
fun <T> FilterGroup(
    options: List<FilterOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /** For use on a tonal card: unselected buttons would otherwise match the card and vanish. */
    unselectedContainer: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    // With large system text four labels no longer fit side by side: let them wrap onto more
    // rows instead of cutting the words off.
    val roomy = androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.3f
    // Equal shares only for two or fewer. With more, a share is a fraction of a narrow screen and
    // the button's own side padding leaves too little for the word: it was cut to "Syste" and
    // "YouTub" on a 360 dp phone. Buttons size to their label instead and wrap onto a new row.
    val equalShares = roomy && options.size <= 2
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { i, option ->
            val shapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = option.value == selected,
                onCheckedChange = { if (it) onSelect(option.value) },
                shapes = shapes,
                // Tighter than the default 24 dp a side, so four short labels share one row on a narrow phone.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (options.size > 2) 14.dp else 24.dp),
                colors = if (unselectedContainer.isSpecified) ToggleButtonDefaults.colors(containerColor = unselectedContainer) else ToggleButtonDefaults.colors(),
                modifier = (if (equalShares) Modifier.weight(1f) else Modifier).semantics { role = Role.RadioButton }
            ) {
                Text(if (option.count != null) "${option.label} ${option.count}" else option.label, maxLines = 2)
            }
        }
    }
}
