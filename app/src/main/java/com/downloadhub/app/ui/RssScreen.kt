package com.downloadhub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.download.RssFeed
import com.downloadhub.core.RssItem
import com.downloadhub.core.RssRule

/** qBittorrent's RSS reader on the phone: feeds, their articles, and auto-download rules. */
@Composable
fun RssScreen(
    feeds: List<RssFeed>,
    rules: List<RssRule>,
    items: Map<String, List<RssItem>>,
    refreshing: Boolean,
    onSubscribe: (String) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onRefresh: () -> Unit,
    onSaveRules: (List<RssRule>) -> Unit,
    onDownload: (RssItem) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var editingRules by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("Feed address") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = address.isNotBlank(), onClick = { onSubscribe(address); address = "" }) { Text("Add") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(enabled = feeds.isNotEmpty() && !refreshing, onClick = onRefresh) {
                Text(if (refreshing) "Refreshing..." else "Refresh")
            }
            OutlinedButton(onClick = { editingRules = true }) { Text("Download rules (${rules.count { it.enabled }})") }
        }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            if (feeds.isEmpty()) {
                item {
                    Text(
                        "Add a feed address to read its articles here. Articles that match an enabled rule are downloaded by themselves.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            feeds.forEach { feed ->
                item(key = "feed-" + feed.url) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            feed.name.ifBlank { feed.url },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onRemoveFeed(feed.url) }) { Text("Remove") }
                    }
                }
                items(items[feed.url].orEmpty(), key = { feed.url + it.guid + it.link }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (item.published.isNotBlank()) {
                                    Text(item.published, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { onDownload(item) }) { Text("Get") }
                        }
                    }
                }
            }
        }
    }
    if (editingRules) {
        RssRulesEditor(rules, onSave = { onSaveRules(it); editingRules = false }, onDismiss = { editingRules = false })
    }
}

@Composable
private fun RssRulesEditor(rules: List<RssRule>, onSave: (List<RssRule>) -> Unit, onDismiss: () -> Unit) {
    var edits by remember { mutableStateOf(rules) }
    fun set(index: Int, rule: RssRule) {
        edits = edits.toMutableList().also { it[index] = rule }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download rules") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Words must all appear (or use a regular expression). Checked about every 30 minutes.",
                    style = MaterialTheme.typography.bodySmall
                )
                edits.forEachIndexed { index, rule ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(rule.name, { set(index, rule.copy(name = it)) }, label = { Text("Name") }, singleLine = true)
                            OutlinedTextField(rule.mustContain, { set(index, rule.copy(mustContain = it)) }, label = { Text("Must contain") }, singleLine = true)
                            OutlinedTextField(rule.mustNotContain, { set(index, rule.copy(mustNotContain = it)) }, label = { Text("Must not contain") }, singleLine = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Regex", Modifier.weight(1f))
                                Switch(rule.useRegex, { set(index, rule.copy(useRegex = it)) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Enabled", Modifier.weight(1f))
                                Switch(rule.enabled, { set(index, rule.copy(enabled = it)) })
                            }
                            TextButton(onClick = { edits = edits.toMutableList().also { it.removeAt(index) } }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { edits = edits + RssRule("", "") }) { Text("Add rule") }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(edits.filter { it.mustContain.isNotBlank() }.map { it.copy(name = it.name.ifBlank { it.mustContain }) })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
