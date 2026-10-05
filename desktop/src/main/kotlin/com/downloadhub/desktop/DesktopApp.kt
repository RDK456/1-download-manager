package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DownloadStatus
import java.io.File
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import com.downloadhub.core.ThemeMode
import com.downloadhub.core.ThemePalette
import java.util.Locale

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsDialog(
    settings: DesktopSettings,
    ytDlp: String,
    captureActive: Boolean,
    capturePort: Int,
    onDismiss: () -> Unit,
    onSave: (DesktopSettings) -> Unit,
    onChooseFolder: () -> File?,
    onChooseCacheFolder: () -> File? = onChooseFolder,
    onToggleCapture: (Boolean) -> Unit,
    extensionReady: Boolean,
    extensionPath: String,
    onOpenExtensionFolder: () -> Unit
) {
    var section by remember { mutableStateOf(SettingsSection.APPEARANCE) }
    var folder by remember { mutableStateOf(settings.downloadDir) }
    var concurrent by remember { mutableStateOf(settings.maxConcurrent.toString()) }
    // Shown in KB/s, the unit it is typed and saved in. It was shown in bytes and saved as
    // KB, so every Save multiplied the limit by 1024.
    var speed by remember { mutableStateOf((settings.speedLimitBytesPerSecond / 1024L).toString()) }
    var connections by remember { mutableStateOf(settings.connectionsPerDownload.toString()) }
    var retries by remember { mutableStateOf(settings.maxRetries.toString()) }
    var closeToTray by remember { mutableStateOf(settings.closeToTray) }
    var autoQueueCaptured by remember { mutableStateOf(settings.browserCaptureAutoQueue) }
    var cache by remember { mutableStateOf(settings.cacheDir) }
    var deleteCache by remember { mutableStateOf(settings.deleteCacheWhenRemoved) }
    var themePalette by remember { mutableStateOf(ThemePalette.fromValue(settings.themePalette)) }
    var themeMode by remember { mutableStateOf(ThemeMode.fromValue(settings.themeMode)) }
    var proxyType by remember { mutableStateOf(settings.proxySetting().type) }
    var proxyHost by remember { mutableStateOf(settings.proxyHost) }
    var proxyPort by remember { mutableStateOf(if (settings.proxyPort > 0) settings.proxyPort.toString() else "") }
    fun kb(bytes: Long) = if (bytes > 0) (bytes / 1024).toString() else "0"
    var upload by remember { mutableStateOf(kb(settings.uploadLimitBytesPerSecond)) }
    var altDown by remember { mutableStateOf(kb(settings.altDownloadLimitBytesPerSecond)) }
    var altUp by remember { mutableStateOf(kb(settings.altUploadLimitBytesPerSecond)) }
    var altSchedule by remember { mutableStateOf(settings.altScheduleEnabled) }
    var altStart by remember { mutableStateOf(com.downloadhub.core.QueueRules.formatTime(settings.altScheduleStartMinute)) }
    var altStop by remember { mutableStateOf(com.downloadhub.core.QueueRules.formatTime(settings.altScheduleStopMinute)) }
    var altDays by remember { mutableStateOf(settings.altScheduleDays.toSet()) }
    var port by remember { mutableStateOf(if (settings.torrentListenPort > 0) settings.torrentListenPort.toString() else "") }
    var dht by remember { mutableStateOf(settings.torrentDht) }
    var lsd by remember { mutableStateOf(settings.torrentLocalPeerDiscovery) }
    var portForwarding by remember { mutableStateOf(settings.torrentPortForwarding) }
    var encryption by remember { mutableStateOf(settings.torrentSessionSettings().encryption) }
    var maxConnections by remember { mutableStateOf(if (settings.torrentMaxConnections > 0) settings.torrentMaxConnections.toString() else "") }
    var anonymous by remember { mutableStateOf(settings.torrentAnonymousMode) }
    var ipFilterOn by remember { mutableStateOf(settings.ipFilterEnabled) }
    var ipFilterPath by remember { mutableStateOf(settings.ipFilterPath) }
    var watchOn by remember { mutableStateOf(settings.watchFolderEnabled) }
    var watchFolder by remember { mutableStateOf(settings.watchFolder) }
    val rules = remember {
        androidx.compose.runtime.mutableStateListOf<RuleDraft>().apply {
            settings.categoryRules.forEach {
                add(RuleDraft(it.name, com.downloadhub.core.CategoryRules.formatExtensions(it.extensions), it.folder))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Wider than a plain dialog: the sections sit beside the settings, not above them.
        properties = APP_WIDE_DIALOG_PROPERTIES,
        modifier = Modifier.width(800.dp),
        title = { Text("Settings") },
        text = {
            Row(Modifier.height(470.dp)) {
                // ---- the sections ---------------------------------------------------
                Column(Modifier.width(170.dp).fillMaxHeight()) {
                    SettingsSection.entries.forEach { entry ->
                        val selected = entry == section
                        Text(
                            entry.label,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) AppTheme.Palette.accent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                                .hoverFill(selected = selected, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { section = entry }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                // ---- the settings in it ---------------------------------------------
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    when (section) {
                        SettingsSection.APPEARANCE -> {
                            SectionHeading("Theme")
                            ThemePicker(themePalette, themeMode) { p, m ->
                                themePalette = p
                                themeMode = m
                            }
                        }

                        SettingsSection.DOWNLOADS -> {
                            SectionHeading("Where files go")
                            FolderRow(
                                label = "Download folder",
                                value = folder,
                                onValueChange = { folder = it },
                                onBrowse = { onChooseFolder()?.let { folder = it.absolutePath } }
                            )
                            SectionHeading("Speed")
                            NumberRow("Downloads at once (1-8)", concurrent) { concurrent = it.filter(Char::isDigit) }
                            NumberRow("Connections per download (1-16)", connections) { connections = it.filter(Char::isDigit) }
                            NumberRow("Automatic retries (0-5)", retries) { retries = it.filter(Char::isDigit) }
                            SectionHeading("Temporary files")
                            Text(
                                "A download is written here first and moved to the download folder " +
                                    "only when it is whole. Leave it empty to use the app's own folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            FolderRow(
                                label = "Cache folder",
                                value = cache,
                                onValueChange = { cache = it },
                                onBrowse = { onChooseCacheFolder()?.let { cache = it.absolutePath } }
                            )
                            TickRow("Delete the cache when an unfinished download is removed", deleteCache) { deleteCache = it }
                            SectionHeading("Window")
                            TickRow("Close to the system tray", closeToTray) { closeToTray = it }
                        }

                        SettingsSection.SPEED -> {
                            SectionHeading("Normal limits")
                            NumberRow("Download limit in KB/s (0 = unlimited)", speed) { speed = it.filter(Char::isDigit) }
                            NumberRow("Upload limit in KB/s, torrents (0 = unlimited)", upload) { upload = it.filter(Char::isDigit) }
                            SectionHeading("Alternative limits")
                            Text(
                                "Used instead while the turtle in the status bar is on, or inside the schedule below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            NumberRow("Download limit in KB/s", altDown) { altDown = it.filter(Char::isDigit) }
                            NumberRow("Upload limit in KB/s", altUp) { altUp = it.filter(Char::isDigit) }
                            TickRow("Use the alternative limits on a schedule", altSchedule) { altSchedule = it }
                            if (altSchedule) {
                                Spacer(Modifier.height(6.dp))
                                Row {
                                    OutlinedTextField(
                                        value = altStart,
                                        onValueChange = { altStart = it.take(5) },
                                        label = { Text("From (HH:mm)") },
                                        isError = com.downloadhub.core.QueueRules.parseTime(altStart) == null,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = altStop,
                                        onValueChange = { altStop = it.take(5) },
                                        label = { Text("To (HH:mm)") },
                                        isError = com.downloadhub.core.QueueRules.parseTime(altStop) == null,
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DAY_LABELS.forEachIndexed { index, label ->
                                        val day = index + 1
                                        DayChip(label, day in altDays) { altDays = if (day in altDays) altDays - day else altDays + day }
                                    }
                                }
                            }
                        }

                        SettingsSection.BITTORRENT -> {
                            SectionHeading("Listening port")
                            NumberRow("Port for incoming peers (blank = automatic)", port) { port = it.filter(Char::isDigit).take(5) }
                            TickRow("Use UPnP / NAT-PMP to forward the port from my router", portForwarding) { portForwarding = it }
                            SectionHeading("Finding peers")
                            TickRow("Enable DHT (decentralised network) to find more peers", dht) { dht = it }
                            TickRow("Enable Local Peer Discovery to find peers on my network", lsd) { lsd = it }
                            SectionHeading("Privacy")
                            com.downloadhub.core.TorrentEncryption.entries.forEach { mode ->
                                TickRow(mode.label, encryption == mode) { if (it) encryption = mode }
                            }
                            TickRow("Enable anonymous mode", anonymous, detail = "Hides the client name and other identifying details from peers and trackers.") { anonymous = it }
                            SectionHeading("Connections")
                            NumberRow("Maximum connections (blank = default)", maxConnections) { maxConnections = it.filter(Char::isDigit).take(5) }
                            SectionHeading("IP filter")
                            TickRow("Block peers listed in a filter file", ipFilterOn, detail = "An eMule .dat or PeerGuardian .p2p blocklist.") { ipFilterOn = it }
                            if (ipFilterOn) {
                                FolderRow(
                                    label = "Filter file",
                                    value = ipFilterPath,
                                    onValueChange = { ipFilterPath = it },
                                    onBrowse = {
                                        val chooser = javax.swing.JFileChooser().apply {
                                            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("IP filter (.dat, .p2p)", "dat", "p2p", "txt")
                                        }
                                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                            ipFilterPath = chooser.selectedFile.absolutePath
                                        }
                                    }
                                )
                            }
                            SectionHeading("Watched folder")
                            TickRow("Add .torrent files saved to a folder automatically", watchOn, detail = "Each one is started with the default download folder, then renamed to .torrent.added.") { watchOn = it }
                            if (watchOn) {
                                FolderRow(
                                    label = "Folder to watch",
                                    value = watchFolder,
                                    onValueChange = { watchFolder = it },
                                    onBrowse = { onChooseFolder()?.let { watchFolder = it.absolutePath } }
                                )
                            }
                        }

                        SettingsSection.CONNECTION -> {
                            SectionHeading("Proxy")
                            Text(
                                "Used by ordinary downloads. YouTube and torrents connect on their own.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            com.downloadhub.core.ProxyType.entries.forEach { type ->
                                TickRow(type.label, proxyType == type) { if (it) proxyType = type }
                            }
                            val manual = proxyType == com.downloadhub.core.ProxyType.HTTP ||
                                proxyType == com.downloadhub.core.ProxyType.SOCKS
                            if (manual) {
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    OutlinedTextField(
                                        value = proxyHost,
                                        onValueChange = { proxyHost = it.trim() },
                                        label = { Text("Host") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = proxyPort,
                                        onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                                        label = { Text("Port") },
                                        singleLine = true,
                                        modifier = Modifier.width(110.dp)
                                    )
                                }
                            }
                        }

                        SettingsSection.CATEGORIES -> {
                            SectionHeading("Your categories")
                            Text(
                                "A finished file whose extension is listed goes to that category's folder. " +
                                    "A folder that is not a full path is inside the download folder. Files no " +
                                    "category names are sorted by type, as before.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            rules.forEachIndexed { index, rule ->
                                CategoryRuleRow(
                                    rule = rule,
                                    onChange = { rules[index] = it },
                                    onBrowse = { onChooseFolder()?.let { rules[index] = rule.copy(folder = it.absolutePath) } },
                                    onRemove = { rules.removeAt(index) }
                                )
                            }
                            OutlinedButton(onClick = { rules.add(RuleDraft("", "", "")) }) { Text("Add category") }
                        }

                        SettingsSection.BROWSER -> {
                            SectionHeading("Browser downloads")
                            TickRow(
                                "Catch downloads from the browser",
                                settings.browserCaptureEnabled,
                                detail = if (captureActive) {
                                    "Listening on 127.0.0.1:$capturePort. Install the extension and " +
                                        "paste the code below to pair it."
                                } else {
                                    "Turn this on, then install the browser extension."
                                },
                                onChange = onToggleCapture
                            )
                            TickRow(
                                "Add them to the queue straight away",
                                autoQueueCaptured,
                                detail = "Off: each one opens the pre-download window first.",
                                onChange = { autoQueueCaptured = it }
                            )
                            if (settings.browserCaptureEnabled && settings.captureToken.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Pairing code",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SelectionContainerCompat(settings.captureToken)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Install the extension: open the folder, then in Chrome or Edge choose " +
                                    "Extensions, turn on Developer mode, and pick that folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectionContainerCompat(extensionPath)
                            OutlinedButton(onClick = onOpenExtensionFolder) {
                                Text(if (extensionReady) "Open extension folder" else "Extract and open folder")
                            }
                        }

                        SettingsSection.ABOUT -> {
                            SectionHeading("Tools")
                            Text(
                                ytDlp,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    settings.copy(
                        downloadDir = folder,
                        maxConcurrent = concurrent.toIntOrNull()?.coerceIn(1, 8) ?: 3,
                        connectionsPerDownload = connections.toIntOrNull()?.coerceIn(1, 16) ?: 4,
                        speedLimitBytesPerSecond = (speed.toLongOrNull() ?: 0L) * 1024L,
                        maxRetries = retries.toIntOrNull()?.coerceIn(0, 5) ?: 2,
                        closeToTray = closeToTray,
                        browserCaptureAutoQueue = autoQueueCaptured,
                        // Trimmed: a trailing space in a path is a folder that does not exist.
                        cacheDir = cache.trim(),
                        deleteCacheWhenRemoved = deleteCache,
                        themePalette = themePalette.value,
                        themeMode = themeMode.value,
                        proxyType = proxyType.name,
                        proxyHost = proxyHost.trim(),
                        proxyPort = proxyPort.toIntOrNull()?.coerceIn(0, 65535) ?: 0,
                        uploadLimitBytesPerSecond = (upload.toLongOrNull() ?: 0L) * 1024L,
                        altDownloadLimitBytesPerSecond = (altDown.toLongOrNull() ?: 0L) * 1024L,
                        altUploadLimitBytesPerSecond = (altUp.toLongOrNull() ?: 0L) * 1024L,
                        altScheduleEnabled = altSchedule,
                        altScheduleDays = altDays.sorted(),
                        altScheduleStartMinute = com.downloadhub.core.QueueRules.parseTime(altStart) ?: settings.altScheduleStartMinute,
                        altScheduleStopMinute = com.downloadhub.core.QueueRules.parseTime(altStop) ?: settings.altScheduleStopMinute,
                        torrentListenPort = port.toIntOrNull()?.coerceIn(0, 65535) ?: 0,
                        torrentDht = dht,
                        torrentLocalPeerDiscovery = lsd,
                        torrentPortForwarding = portForwarding,
                        torrentEncryption = encryption.name,
                        torrentMaxConnections = maxConnections.toIntOrNull() ?: 0,
                        torrentAnonymousMode = anonymous,
                        ipFilterEnabled = ipFilterOn,
                        ipFilterPath = ipFilterPath.trim(),
                        watchFolderEnabled = watchOn,
                        watchFolder = watchFolder.trim(),
                        // A row with no extensions can match nothing, so it is not kept.
                        categoryRules = rules.mapNotNull { draft ->
                            val extensions = com.downloadhub.core.CategoryRules.parseExtensions(draft.extensions)
                            if (extensions.isEmpty()) null
                            else CategoryRuleConfig(draft.name.trim().ifBlank { "Category" }, extensions, draft.folder.trim())
                        }
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The pages of Settings, in the order they are listed. */
private enum class SettingsSection(val label: String) {
    APPEARANCE("Appearance"),
    DOWNLOADS("Downloads"),
    SPEED("Speed"),
    BITTORRENT("BitTorrent"),
    CONNECTION("Connection"),
    CATEGORIES("Categories"),
    BROWSER("Browser"),
    ABOUT("Tools")
}

/** One category while it is being edited; extensions stay as typed until Save. */
private data class RuleDraft(val name: String, val extensions: String, val folder: String)

@Composable
private fun CategoryRuleRow(
    rule: RuleDraft,
    onChange: (RuleDraft) -> Unit,
    onBrowse: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .border(1.dp, AppTheme.Palette.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rule.name,
                onValueChange = { onChange(rule.copy(name = it)) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.width(150.dp)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = rule.extensions,
                onValueChange = { onChange(rule.copy(extensions = it)) },
                label = { Text("Extensions (mp4, mkv)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRemove) { Text("Remove", color = AppTheme.Palette.error) }
        }
        Spacer(Modifier.height(6.dp))
        FolderRow(
            label = "Folder",
            value = rule.folder,
            onValueChange = { onChange(rule.copy(folder = it)) },
            onBrowse = onBrowse
        )
    }
}

// --- settings layout helpers ------------------------------------------------

/**
 * The nine themes and the three modes.
 *
 * Each swatch is drawn *in its own theme* - the panel behind it is that theme's surface,
 * the bar is its accent, the label is its text colour - rather than being nine coloured
 * rectangles with a name underneath. A palette you cannot see is a palette you cannot
 * choose, and the nine accents are close enough together in a list of dots that Ocean and
 * Royal are indistinguishable until one of them is applied to a whole window.
 *
 * Three rows of three rather than a wrapping flow: nine items at a fixed width fit in the
 * settings dialog's 440 dp without needing an experimental layout, and a grid lines up in
 * a way a wrapped list does not.
 */
@Composable
private fun ThemePicker(
    palette: ThemePalette,
    mode: ThemeMode,
    onChange: (ThemePalette, ThemeMode) -> Unit
) {
    Column {
        ThemePalette.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { entry ->
                    Box(Modifier.weight(1f)) {
                        ProvideDesktopTheme(entry, mode, installGlobally = false) {
                            ThemeSwatch(
                                entry = entry,
                                selected = entry == palette,
                                onClick = { onChange(entry, mode) }
                            )
                        }
                    }
                }
                // Keeps the last row's three cells the same width as the rows above even
                // when the final row has fewer entries.
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeMode.entries.forEach { entry ->
                if (entry == mode) {
                    Button(onClick = { onChange(palette, entry) }) { Text(entry.label) }
                } else {
                    OutlinedButton(onClick = { onChange(palette, entry) }) { Text(entry.label) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "AMOLED makes the background true black and leaves the accent alone, so it " +
                "works with any of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One theme's swatch, drawn in that theme. */
@Composable
private fun ThemeSwatch(entry: ThemePalette, selected: Boolean, onClick: () -> Unit) {
    // The swatch's own theme, from the local its ProvideDesktopTheme set - not the app's.
    val p = LocalDesktopPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(p.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) p.accent else p.outline,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Text(
            entry.label,
            style = MaterialTheme.typography.labelMedium,
            color = p.onSurface,
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        // A row and a bar: the two things a theme is, at a glance, side by side.
        Row(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(p.surface)
        ) {
            Box(Modifier.fillMaxHeight().weight(1f))
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(14.dp)
                    .background(p.accent)
            )
        }
    }
}

/**
 * A section heading in the settings.
 *
 * Fixed spacing above and below rather than a `Spacer` on either side of the heading. The
 * headings used to be separated by loose `Spacer(10.dp)`s and a `Spacer(16.dp)`, which is
 * why the gap above one heading was a different size from the gap below it and the
 * sections did not read as sections.
 */
@Composable
private fun SectionHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

/**
 * A folder path with a browse button beside it.
 *
 * The button is a fixed 36 dp and the icon inside it a fixed 18 dp. `IconButton` sizes
 * itself to its own default and the icon to whatever vector it was handed, so the two
 * together came out taller than the text field beside them and the row sat unevenly.
 */
@Composable
private fun FolderRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onBrowse,
            modifier = Modifier.height(36.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 0.dp
            )
        ) {
            Icon(DlmIcons.Folder, contentDescription = "Choose a folder", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Browse", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A numeric setting, in one line with the rest rather than on a row of its own. */
@Composable
private fun NumberRow(label: String, value: String, onValueChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.width(84.dp)
        )
    }
}

// --- helpers ----------------------------------------------------------------

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun Long.asSpeed(): String = if (this <= 0) {
    "starting"
} else {
    "${formatBytes(this)}/s"
}


/** Opens the finished file, or reveals it in Explorer when that fails. */
/**
 * Opens a finished download's folder in Explorer, with the file selected.
 *
 * This is what the row's folder button does, and the icon says folder, so the
 * folder is what it opens. It used to hand the file to `java.awt.Desktop.open`,
 * which is two problems at once: the icon promises a folder and the code launched
 * whatever program handles the file, and the whole thing was wrapped in
 * `runCatching`, so on a machine where Desktop is unavailable it did nothing and
 * said nothing. Explorer is called directly and the answer is returned.
 *
 * Returns false rather than throwing, so the caller can tell the user instead of
 * leaving a button that appears broken.
 */
internal fun revealInFolder(path: String?): Boolean {
    val file = path?.let { File(it) } ?: return false
    // A finished download whose file was moved or deleted still has a folder worth
    // opening, so this does not give up when the file itself is gone.
    val folder = file.parentFile ?: return false
    if (!folder.isDirectory) return false
    val explorer = File("C:/Windows/explorer.exe")
    if (!explorer.isFile) return false
    return runCatching {
        val process = if (file.exists()) {
            // /select, is Explorer's own "show me this file" switch.
            ProcessBuilder(explorer.absolutePath, "/select,", file.absolutePath)
                .redirectErrorStream(true).start()
        } else {
            ProcessBuilder(explorer.absolutePath, folder.absolutePath)
                .redirectErrorStream(true).start()
        }
        // Explorer is a single instance: a second launch just hands over to the
        // running one, so the process is left to exit on its own.
        process
        true
    }.getOrDefault(false)
}

/** Read-only, selectable token so it can be copied into the extension popup. */
@Composable
private fun SelectionContainerCompat(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}
