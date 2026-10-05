package com.downloadhub.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.downloadhub.app.data.AdvancedSettings
import com.downloadhub.core.CategoryRule
import com.downloadhub.core.CategoryRules
import com.downloadhub.core.ProxySetting
import com.downloadhub.core.ProxyType
import com.downloadhub.core.QueueRules
import com.downloadhub.core.TorrentEncryption

private val WEEK = listOf("M", "T", "W", "T", "F", "S", "S")

/** The desktop's newer settings on the phone, edited as a draft and saved together. */
@Composable
fun AdvancedSettingsScreen(
    settings: AdvancedSettings,
    onSave: (AdvancedSettings) -> Unit,
    onImportIpFilter: (Uri) -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var rules by remember(settings) {
        mutableStateOf(settings.categoryRules.map { Triple(it.name, CategoryRules.formatExtensions(it.extensions), it.folder) })
    }
    fun kb(bytes: Long) = (bytes / 1024).toString()
    var upload by remember(settings) { mutableStateOf(kb(settings.uploadLimit)) }
    var altDown by remember(settings) { mutableStateOf(kb(settings.altDownloadLimit)) }
    var altUp by remember(settings) { mutableStateOf(kb(settings.altUploadLimit)) }
    var altStart by remember(settings) { mutableStateOf(QueueRules.formatTime(settings.altStart)) }
    var altStop by remember(settings) { mutableStateOf(QueueRules.formatTime(settings.altStop)) }
    var port by remember(settings) { mutableStateOf(if (settings.torrentPort > 0) settings.torrentPort.toString() else "") }
    var maxConnections by remember(settings) {
        mutableStateOf(if (settings.maxConnections > 0) settings.maxConnections.toString() else "")
    }
    var proxyHost by remember(settings) { mutableStateOf(settings.proxy.host) }
    var proxyPort by remember(settings) { mutableStateOf(if (settings.proxy.port > 0) settings.proxy.port.toString() else "") }
    val pickFilter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImportIpFilter) }
    val number = KeyboardOptions(keyboardType = KeyboardType.Number)

    fun setRule(index: Int, value: Triple<String, String, String>) {
        rules = rules.toMutableList().also { it[index] = value }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Section("Your categories") {
            Hint("A finished file whose extension is listed goes into that category's folder inside the download folder.")
            rules.forEachIndexed { index, (name, extensions, folder) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(name, { setRule(index, Triple(it, extensions, folder)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(extensions, { setRule(index, Triple(name, it, folder)) }, label = { Text("Extensions (mp4, mkv)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(folder, { setRule(index, Triple(name, extensions, it)) }, label = { Text("Folder (blank = the name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { rules = rules.toMutableList().also { it.removeAt(index) } }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { rules = rules + Triple("", "", "") }) { Text("Add category") }
        }

        Section("Proxy") {
            ProxyType.entries.forEach { type ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.proxy.type == type, onClick = { draft = draft.copy(proxy = draft.proxy.copy(type = type)) })
                    Text(type.label.replace("Windows", "system"))
                }
            }
            if (draft.proxy.type == ProxyType.HTTP || draft.proxy.type == ProxyType.SOCKS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(proxyHost, { proxyHost = it.trim() }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(0.5f))
                }
            }
        }

        Section("Speed") {
            OutlinedTextField(upload, { upload = it.filter(Char::isDigit) }, label = { Text("Torrent upload limit, KB/s (0 = unlimited)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
            Toggle("Use alternative limits now", draft.altEnabled) { draft = draft.copy(altEnabled = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(altDown, { altDown = it.filter(Char::isDigit) }, label = { Text("Alt download KB/s") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
                OutlinedTextField(altUp, { altUp = it.filter(Char::isDigit) }, label = { Text("Alt upload KB/s") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }
            Toggle("Use them on a schedule", draft.altScheduleEnabled) { draft = draft.copy(altScheduleEnabled = it) }
            if (draft.altScheduleEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(altStart, { altStart = it.take(5) }, label = { Text("From HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(altStop, { altStop = it.take(5) }, label = { Text("To HH:mm") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEK.forEachIndexed { index, label ->
                        val day = index + 1
                        FilterChip(
                            selected = day in draft.altDays,
                            onClick = { draft = draft.copy(altDays = if (day in draft.altDays) draft.altDays - day else draft.altDays + day) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        Section("BitTorrent") {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Listening port (blank = automatic)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
            Toggle("Forward the port with UPnP / NAT-PMP", draft.portForwarding) { draft = draft.copy(portForwarding = it) }
            Toggle("DHT, to find more peers", draft.dht) { draft = draft.copy(dht = it) }
            Toggle("Local Peer Discovery", draft.lsd) { draft = draft.copy(lsd = it) }
            Toggle("Anonymous mode", draft.anonymous) { draft = draft.copy(anonymous = it) }
            TorrentEncryption.entries.forEach { mode ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.encryption == mode, onClick = { draft = draft.copy(encryption = mode) })
                    Text(mode.label)
                }
            }
            OutlinedTextField(maxConnections, { maxConnections = it.filter(Char::isDigit).take(5) }, label = { Text("Maximum connections (blank = default)") }, singleLine = true, keyboardOptions = number, modifier = Modifier.fillMaxWidth())
        }

        Section("IP filter") {
            Toggle("Block peers listed in a filter file", draft.ipFilterEnabled) { draft = draft.copy(ipFilterEnabled = it) }
            Hint(if (draft.ipFilterPath.isBlank()) "No filter file chosen yet." else "Using ${draft.ipFilterPath.substringAfterLast('/')}")
            OutlinedButton(onClick = { pickFilter.launch(arrayOf("*/*")) }) { Text("Choose .dat or .p2p file") }
        }

        Button(
            onClick = {
                onSave(
                    draft.copy(
                        categoryRules = rules.mapNotNull { (name, extensions, folder) ->
                            val parsed = CategoryRules.parseExtensions(extensions)
                            if (parsed.isEmpty()) null else CategoryRule(name.trim().ifBlank { "Category" }, parsed, folder.trim())
                        },
                        proxy = ProxySetting(draft.proxy.type, proxyHost.trim(), proxyPort.toIntOrNull() ?: 0),
                        uploadLimit = (upload.toLongOrNull() ?: 0L) * 1024,
                        altDownloadLimit = (altDown.toLongOrNull() ?: 0L) * 1024,
                        altUploadLimit = (altUp.toLongOrNull() ?: 0L) * 1024,
                        altStart = QueueRules.parseTime(altStart) ?: draft.altStart,
                        altStop = QueueRules.parseTime(altStop) ?: draft.altStop,
                        torrentPort = port.toIntOrNull() ?: 0,
                        maxConnections = maxConnections.toIntOrNull() ?: 0
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
