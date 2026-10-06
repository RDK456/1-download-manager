package com.downloadhub.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.downloadhub.app.download.ThumbnailCache
import com.downloadhub.core.IptvChannel
import com.downloadhub.core.IptvSource

/**
 * Free TV from iptv-org's public lists of freely broadcast channels, by category or by
 * country. A channel plays in the app's own player.
 */
@Composable
fun TvScreen(loader: ThumbnailCache, onWatching: () -> Unit) {
    val context = LocalContext.current
    var listUrl by rememberSaveable { mutableStateOf(IptvSource.categoryUrl("news")) }
    var listLabel by rememberSaveable { mutableStateOf("News") }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var hideBlocked by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(listUrl) {
        loading = true
        failed = null
        channels = runCatching { IptvSource.channels(listUrl) }
            .onFailure { failed = "Could not load the channel list." }
            .getOrDefault(emptyList())
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text("Filter $listLabel") },
                modifier = Modifier.weight(1f)
            )
            Box {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }) { Text("Country") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    IptvSource.countries.forEach { (code, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            open = false
                            listUrl = IptvSource.countryUrl(code)
                            listLabel = name
                        })
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = hideBlocked, onClick = { hideBlocked = !hideBlocked }, label = { Text("Hide geo-blocked") })
            IptvSource.categories.forEach { (id, label) ->
                FilterChip(
                    selected = listLabel == label,
                    onClick = {
                        listUrl = IptvSource.categoryUrl(id)
                        listLabel = label
                    },
                    label = { Text(label) }
                )
            }
        }
        val shown = channels.filter {
            (!hideBlocked || !it.geoBlocked) && (filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true))
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(failed ?: "No channels here.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.url }) { channel ->
                    ChannelCard(channel, loader) {
                        AppPlayer.play(context, listOf(AppPlayer.Item(channel.url, channel.name, isVideo = true, isLive = true)), 0)
                        onWatching()
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: IptvChannel, loader: ThumbnailCache, onWatch: () -> Unit) {
    var logo by remember(channel.logo) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(channel.logo) {
        logo = channel.logo?.let { runCatching { loader.load(null, it) }.getOrNull()?.asImageBitmap() }
    }
    Card(Modifier.clickable(onClick = onWatch)) {
        Column(Modifier.padding(8.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                val image = logo
                if (image != null) Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(10.dp))
                else Text(channel.name.take(20), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(6.dp))
            Text(channel.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (listOf(channel.quality) + channel.groups + channel.flags).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (channel.geoBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
