package com.downloadhub.app.ui.expressive.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.BatteryWarning
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WifiOff
import com.downloadhub.app.ui.expressive.model.BannerKind
import com.downloadhub.app.ui.expressive.model.content

/**
 * A notice above the list. Colour carries severity (storage is an error container, the rest
 * are tonal), the icon and the words carry it too, so it still reads without colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoticeBanner(
    kind: BannerKind,
    onAction: (label: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (kind) {
        BannerKind.STORAGE -> scheme.errorContainer to scheme.onErrorContainer
        BannerKind.NETWORK -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BannerKind.NOTIFICATIONS, BannerKind.BATTERY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BannerKind.UPDATE -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    val text = kind.content()
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(iconFor(kind), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(text.title, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(text.body, style = MaterialTheme.typography.bodyMedium)
                if (text.actions.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        text.actions.forEach { action ->
                            TextButton(onClick = { if (action.dismisses) onDismiss() else onAction(action.label) }) {
                                Text(action.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun iconFor(kind: BannerKind): ImageVector = when (kind) {
    BannerKind.STORAGE -> Lucide.HardDrive
    BannerKind.NETWORK -> Lucide.WifiOff
    BannerKind.NOTIFICATIONS -> Lucide.Bell
    BannerKind.BATTERY -> Lucide.BatteryWarning
    BannerKind.UPDATE -> Lucide.CloudDownload
}
