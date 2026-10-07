package com.downloadhub.app.ui

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Check
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode

/** Theme picker: pick a two-tone colour scheme, then light/dark/system. */
@Composable
fun ThemePickerScreen(
    appTheme: AppTheme,
    themeMode: ThemeMode,
    onThemeChange: (AppTheme) -> Unit,
    onModeChange: (ThemeMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection("Appearance") {
            Text("Light or dark", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(modeLabel(mode)) }
                    )
                }
            }
            Text(
                "AMOLED forces a pure black background in either mode and keeps the " +
                "colour theme you pick below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Off by default: the skin is flat unless asked otherwise.
            val context = androidx.compose.ui.platform.LocalContext.current
            Text("Layout", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !com.downloadhub.app.ui.theme.UiStyle.keys,
                    onClick = { com.downloadhub.app.ui.theme.UiStyle.set(context, false) },
                    label = { Text("Rail") }
                )
                FilterChip(
                    selected = com.downloadhub.app.ui.theme.UiStyle.keys,
                    onClick = { com.downloadhub.app.ui.theme.UiStyle.set(context, true) },
                    label = { Text("Keys") }
                )
            }
            Text(
                "Rail is flat and quiet. Keys turns the tabs into hardware keys with LEDs and puts the title on an LCD plate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Depth effects", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Glow, gradients and scanlines on the displays. Off keeps everything flat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = com.downloadhub.app.ui.theme.UiEffects.enabled,
                    onCheckedChange = { com.downloadhub.app.ui.theme.UiEffects.set(context, it) }
                )
            }
        }

        Text(
            "COLOUR THEME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Tap a theme to change the accent colour. The AMOLED mode above turns " +
                "every one of them into a pure black background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LiveThemePreview(
            theme = appTheme,
            dark = isDarkMode(themeMode),
            pureBlack = themeMode == ThemeMode.AMOLED
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .height(660.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(AppTheme.entries.toList(), key = { it.name }) { theme ->
                val context = androidx.compose.ui.platform.LocalContext.current
                ThemeCard(
                    theme = theme,
                    selected = appTheme == theme,
                    onClick = {
                        onThemeChange(theme)
                        // Chassis is the hardware look, so it brings the Keys layout with it;
                        // the Layout chips above switch it back at any time.
                        if (theme == AppTheme.CHASSIS) com.downloadhub.app.ui.theme.UiStyle.set(context, true)
                    }
                )
            }
        }
    }
}

/** True when the resolved palette is a dark one. */
@Composable
private fun isDarkMode(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

/**
 * A miniature of the real UI rendered in the selected palette, so the choice is
 * obvious before leaving the page. The screen behind it already uses this theme,
 * which is what made a slow or invisible change easy to miss.
 */
@Composable
private fun LiveThemePreview(theme: AppTheme, dark: Boolean, pureBlack: Boolean) {
    val scheme = theme.colorScheme(dark, pureBlack = pureBlack)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = scheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Preview",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(scheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Video \u00B7 Downloading",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(scheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSecondaryContainer
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Switch(checked = true, onCheckedChange = {}, colors = previewSwitchColors(scheme))
                }
            }
        }
    }
}

@Composable
private fun previewSwitchColors(scheme: androidx.compose.material3.ColorScheme) =
    androidx.compose.material3.SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = scheme.primary,
        checkedBorderColor = scheme.primary,
        uncheckedThumbColor = scheme.onSurface,
        uncheckedTrackColor = scheme.surfaceVariant,
        uncheckedBorderColor = scheme.outline
    )

@Composable
private fun ThemeCard(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(theme.surfaceHint),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(theme.accent)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(theme.accent.copy(alpha = 0.45f))
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        theme.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (theme == AppTheme.AMOLED) {
                        Text(
                            "Pure black",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selected) {
                    Icon(
                        Lucide.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun modeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
}
