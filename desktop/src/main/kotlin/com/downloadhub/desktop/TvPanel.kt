package com.downloadhub.desktop

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.IptvChannel
import com.downloadhub.core.IptvSource
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Free TV: iptv-org's public lists of freely broadcast channels, by category or by country,
 * as a grid of channel cards. Play watches a channel right here, in the app; VLC
 * or mpv is offered as well when one is installed.
 */
@Composable
fun TvPanel(modifier: Modifier = Modifier) {
    var listUrl by remember { mutableStateOf(IptvSource.categoryUrl("news")) }
    var listLabel by remember { mutableStateOf("News") }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var hideBlocked by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    val hasPlayer = remember { DesktopPlayer.externalPlayer() != null }
    // The channel playing in the app, filling the panel; null shows the grid.
    var watching by remember { mutableStateOf<IptvChannel?>(null) }
    watching?.let { channel ->
        LiveTvPlayer(
            channel = channel,
            onClose = { watching = null },
            onOpenExternal = if (hasPlayer) {
                {
                    watching = null
                    if (!DesktopPlayer.openStream(channel.url, channel.userAgent, channel.referrer)) notice = "Could not start the video player."
                }
            } else null,
            modifier = modifier
        )
        return
    }

    // Every country iptv-org lists, from its catalogue; the short built-in list until then.
    var countries by remember { mutableStateOf(IptvSource.countries) }
    LaunchedEffect(Unit) { countries = IptvSource.allCountries() }

    LaunchedEffect(listUrl) {
        loading = true
        failed = null
        channels = runCatching { IptvSource.channels(listUrl) }
            .onFailure { failed = "Could not load the channel list: ${it.message}" }
            .getOrDefault(emptyList())
        loading = false
    }

    Column(modifier.fillMaxSize().background(AppTheme.Palette.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text("Filter $listLabel channels", fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            ListMenu("Country", countries) { code, name ->
                listUrl = IptvSource.countryUrl(code)
                listLabel = name
            }
            Spacer(Modifier.width(8.dp))
            ListMenu("Language", IptvSource.languages) { code, name ->
                listUrl = IptvSource.languageUrl(code)
                listLabel = name
            }
            Spacer(Modifier.width(8.dp))
            FilterPill(if (hideBlocked) "Hiding geo-blocked" else "Showing geo-blocked", hideBlocked) { hideBlocked = !hideBlocked }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // Every channel iptv-org has, then its categories.
            FilterPill("All channels", listUrl == IptvSource.ALL_URL) {
                listUrl = IptvSource.ALL_URL
                listLabel = "All"
            }
            IptvSource.categories.forEach { (id, label) ->
                FilterPill(label, listLabel == label) {
                    listUrl = IptvSource.categoryUrl(id)
                    listLabel = label
                }
            }
        }
        notice?.let { Text(it, fontSize = 11.sp, color = AppTheme.Palette.accent, modifier = Modifier.padding(horizontal = 16.dp)) }
        val shown = channels.filter {
            (!hideBlocked || !it.geoBlocked) && (filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true))
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(failed ?: "No channels here.", color = AppTheme.Palette.muted, fontSize = 13.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(shown, key = { it.url }) { channel ->
                    ChannelCard(
                        channel = channel,
                        onPlay = { watching = channel },
                        onExternal = if (hasPlayer) {
                            { if (!DesktopPlayer.openStream(channel.url, channel.userAgent, channel.referrer)) notice = "Could not start the video player." }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: IptvChannel, onPlay: () -> Unit, onExternal: (() -> Unit)?) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .clip(shape)
            .background(AppTheme.Palette.surface)
            .border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.3f), shape)
            .clickable(onClick = onPlay)
            .padding(10.dp)
    ) {
        val logo = rememberRemoteImage(channel.logo)
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(AppTheme.Palette.raised),
            contentAlignment = Alignment.Center
        ) {
            if (logo != null) Image(logo, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
            else Text(channel.name.take(24), color = AppTheme.Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Text(channel.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            (listOf(channel.quality) + channel.groups + channel.flags).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 10.sp,
            color = if (channel.geoBlocked) AppTheme.Palette.error else AppTheme.Palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            // Plays here, in the app; VLC is offered too when it is installed.
            TextButton(onClick = onPlay) { Text("Play", fontSize = 12.sp) }
            if (onExternal != null) TextButton(onClick = onExternal) { Text("VLC", fontSize = 12.sp) }
        }
    }
}

/** A button that drops a scrolling list of (code, name) choices - countries or languages. */
@Composable
private fun ListMenu(title: String, options: List<Pair<String, String>>, onPick: (code: String, name: String) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { open = true }) {
            Text(title, fontSize = 12.sp)
            Icon(Lucide.ChevronDown, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    onPick(code, name)
                })
            }
        }
    }
}
