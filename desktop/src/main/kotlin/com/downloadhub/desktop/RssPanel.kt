package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.RssItem

/**
 * qBittorrent's RSS reader: subscribe to feeds, read what they offer, download an article,
 * and set rules that download matching articles as they appear.
 */
@Composable
internal fun RssPanel(
    settings: DesktopSettings,
    itemsByFeed: Map<String, List<RssItem>>,
    refreshing: Boolean,
    actions: DesktopActions,
    modifier: Modifier = Modifier
) {
    var newFeed by remember { mutableStateOf("") }
    var selectedFeed by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var editingRules by remember { mutableStateOf(false) }
    val feed = selectedFeed?.takeIf { url -> settings.rssFeeds.any { it.url == url } }
    val shown = (if (feed != null) itemsByFeed[feed].orEmpty() else itemsByFeed.values.flatten())
        .filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) }

    Column(modifier) {
        // ---- the bar: add a feed, refresh, rules ------------------------------------
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newFeed,
                onValueChange = { newFeed = it },
                singleLine = true,
                placeholder = { Text("Feed address, e.g. https://nyaa.si/?page=rss", fontSize = 12.sp) },
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).onEnter(newFeed.isNotBlank()) { actions.addRssFeed(newFeed); newFeed = "" }
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = newFeed.isNotBlank(), onClick = { actions.addRssFeed(newFeed); newFeed = "" }) { Text("Subscribe") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !refreshing && settings.rssFeeds.isNotEmpty(), onClick = actions.refreshRss) {
                Text(if (refreshing) "Refreshing..." else "Refresh")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { editingRules = true }) {
                Text("Download rules (${settings.rssRules.count { it.enabled }})")
            }
        }
        if (refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppTheme.Palette.accent, trackColor = AppTheme.Palette.raised)
        }

        Row(Modifier.fillMaxSize()) {
            // ---- the feeds -------------------------------------------------------
            Column(
                Modifier
                    .width(230.dp)
                    .fillMaxHeight()
                    .background(AppTheme.Palette.band)
                    .padding(vertical = 6.dp)
            ) {
                FeedRow("All feeds", itemsByFeed.values.sumOf { it.size }, feed == null, onRemove = null) { selectedFeed = null }
                settings.rssFeeds.forEach { entry ->
                    FeedRow(
                        entry.name.ifBlank { entry.url },
                        itemsByFeed[entry.url]?.size ?: 0,
                        feed == entry.url,
                        onRemove = { actions.removeRssFeed(entry.url); if (selectedFeed == entry.url) selectedFeed = null }
                    ) { selectedFeed = entry.url }
                }
                if (settings.rssFeeds.isEmpty()) {
                    Text(
                        "No feeds yet. Paste a feed address above and Subscribe.",
                        fontSize = 11.sp,
                        color = AppTheme.Palette.muted,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ---- the articles ----------------------------------------------------
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp)) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    placeholder = { Text("Filter articles", fontSize = 12.sp) },
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (settings.rssFeeds.isEmpty()) "Subscribe to a feed to see its articles here."
                            else "Nothing here yet. Refresh to read the feeds now.",
                            fontSize = 12.sp,
                            color = AppTheme.Palette.muted
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.guid + it.link }) { item ->
                            Row(
                                Modifier.fillMaxWidth().hoverFill(shape = RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.published.isNotBlank()) {
                                        Text(item.published, fontSize = 10.sp, color = AppTheme.Palette.muted, maxLines = 1)
                                    }
                                }
                                TextButton(onClick = { actions.downloadRssItem(item) }) { Text("Download") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingRules) {
        RssRulesDialog(
            rules = settings.rssRules,
            onSave = { actions.saveRssRules(it); editingRules = false },
            onDismiss = { editingRules = false }
        )
    }
}

@Composable
private fun FeedRow(label: String, count: Int, selected: Boolean, onRemove: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .hoverFill(selected = selected, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AppTheme.Palette.accent else AppTheme.Palette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text("$count", fontSize = 11.sp, color = AppTheme.Palette.faint)
        if (onRemove != null) {
            Text(
                "✕",
                fontSize = 11.sp,
                color = AppTheme.Palette.muted,
                modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 6.dp)
            )
        }
    }
}

/** One rule while it is being edited. */
private data class RuleEdit(
    val name: String,
    val mustContain: String,
    val mustNotContain: String,
    val useRegex: Boolean,
    val enabled: Boolean
)

/** qBittorrent's RSS Downloader: rules that download matching articles as they appear. */
@Composable
internal fun RssRulesDialog(
    rules: List<RssRuleConfig>,
    onSave: (List<RssRuleConfig>) -> Unit,
    onDismiss: () -> Unit
) {
    val edits = remember {
        mutableStateListOf<RuleEdit>().apply {
            rules.forEach { add(RuleEdit(it.name, it.mustContain, it.mustNotContain, it.useRegex, it.enabled)) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("RSS download rules") },
        text = {
            Column(
                Modifier.width(560.dp).height(420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "A new article whose title matches an enabled rule is downloaded when the feed is next read. " +
                        "Words must all appear; with Regex, each box is a regular expression.",
                    fontSize = 11.sp,
                    color = AppTheme.Palette.muted
                )
                edits.forEachIndexed { index, rule ->
                    Column(
                        Modifier.fillMaxWidth()
                            .border(1.dp, AppTheme.Palette.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = rule.name,
                                onValueChange = { edits[index] = rule.copy(name = it) },
                                label = { Text("Rule name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { edits.removeAt(index) }) { Text("Remove", color = AppTheme.Palette.error) }
                        }
                        OutlinedTextField(
                            value = rule.mustContain,
                            onValueChange = { edits[index] = rule.copy(mustContain = it) },
                            label = { Text("Must contain") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = rule.mustNotContain,
                            onValueChange = { edits[index] = rule.copy(mustNotContain = it) },
                            label = { Text("Must not contain") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            TickRow("Regex", rule.useRegex, modifier = Modifier.weight(1f)) { edits[index] = rule.copy(useRegex = it) }
                            TickRow("Enabled", rule.enabled, modifier = Modifier.weight(1f)) { edits[index] = rule.copy(enabled = it) }
                        }
                    }
                }
                OutlinedButton(onClick = { edits.add(RuleEdit("", "", "", useRegex = false, enabled = true)) }) { Text("Add rule") }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    edits.filter { it.mustContain.isNotBlank() }.map {
                        RssRuleConfig(
                            it.name.trim().ifBlank { it.mustContain.trim() },
                            it.mustContain.trim(),
                            it.mustNotContain.trim(),
                            it.useRegex,
                            it.enabled
                        )
                    }
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
