package com.downloadhub.app.ui.expressive.screen.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.ui.expressive.component.FilterGroup
import com.downloadhub.app.ui.expressive.component.FilterOption
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.shape.StateShape
import com.downloadhub.app.ui.expressive.theme.withExpressiveTones

/**
 * Light, dark or AMOLED, then one of the colour themes. The chosen theme is a burst and the
 * rest are plain discs, so the choice reads by shape as well as by outline. The preview card
 * is drawn from the palette being looked at, not the one the page is already wearing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemesScreen(
    appTheme: AppTheme,
    themeMode: ThemeMode,
    onThemeChange: (AppTheme) -> Unit,
    onModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleLargeEmphasized)
        FilterGroup(
            options = listOf(
                FilterOption(ThemeMode.SYSTEM, "System"),
                FilterOption(ThemeMode.LIGHT, "Light"),
                FilterOption(ThemeMode.DARK, "Dark"),
                FilterOption(ThemeMode.AMOLED, "AMOLED")
            ),
            selected = themeMode,
            onSelect = onModeChange
        )
        Text(
            "AMOLED turns the background pure black in either mode and keeps the colour you pick below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Preview(appTheme, dark, themeMode == ThemeMode.AMOLED)

        Text("Colour", style = MaterialTheme.typography.titleLargeEmphasized)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppTheme.entries.forEach { theme ->
                val selected = theme == appTheme
                Column(
                    Modifier
                        .width(76.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onThemeChange(theme) }),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StateShape(
                        kind = if (selected) StateShapeKind.BURST else StateShapeKind.CIRCLE,
                        color = theme.accent,
                        modifier = Modifier.size(60.dp),
                        spin = false
                    ) {
                        if (selected) Icon(Lucide.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(24.dp))
                    }
                    Text(theme.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Preview(theme: AppTheme, dark: Boolean, pureBlack: Boolean) {
    val scheme = remember(theme, dark, pureBlack) { theme.colorScheme(dark, pureBlack).withExpressiveTones() }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = scheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StateShape(StateShapeKind.COOKIE, scheme.primaryContainer, Modifier.size(40.dp), spin = false) {}
                    Column {
                        Text("Lecture 14.mp4", style = MaterialTheme.typography.titleSmallEmphasized, color = scheme.onSurface)
                        Text("Downloading · 62%", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = MaterialTheme.shapes.large, color = scheme.secondaryContainer, modifier = Modifier.weight(1f)) {
                    Text("Notice", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
                }
                Surface(shape = MaterialTheme.shapes.large, color = scheme.tertiaryContainer, modifier = Modifier.weight(1f)) {
                    Text("Update", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onTertiaryContainer)
                }
                Surface(shape = MaterialTheme.shapes.large, color = scheme.primary, modifier = Modifier.weight(1f)) {
                    Text("Add", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                }
            }
        }
    }
}
