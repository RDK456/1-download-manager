package com.downloadhub.app.ui.expressive.screen.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.downloadhub.app.ui.DiscoverTile
import com.downloadhub.app.ui.AppDestination
import com.downloadhub.app.ui.expressive.model.StateShapeKind
import com.downloadhub.app.ui.expressive.shape.StateShape

/** Decorative silhouettes, handed out in turn so neighbouring tiles never match. */
private val SHAPES = listOf(StateShapeKind.COOKIE, StateShapeKind.BURST, StateShapeKind.SQUIRCLE, StateShapeKind.PENTAGON, StateShapeKind.CIRCLE)

/**
 * The Discover tab. Featured cards scroll sideways in a multi-browse carousel (large, medium
 * and small cards at once), and the same destinations are listed below as plain rows, which
 * is the way in for screen readers, switch access and anyone who does not swipe sideways.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverHome(tiles: List<DiscoverTile>, onOpen: (AppDestination) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tones = listOf(
        scheme.primaryContainer to scheme.onPrimaryContainer,
        scheme.tertiaryContainer to scheme.onTertiaryContainer,
        scheme.secondaryContainer to scheme.onSecondaryContainer
    )
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "featured") {
            Column(Modifier.padding(bottom = 16.dp)) {
                Text("Free to download", style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalMultiBrowseCarousel(
                    state = rememberCarouselState { tiles.size },
                    preferredItemWidth = 210.dp,
                    itemSpacing = 8.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                ) { index ->
                    val tile = tiles[index]
                    val (container, content) = tones[index % tones.size]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .maskClip(MaterialTheme.shapes.extraLarge)
                            .background(container)
                            .clickable(role = Role.Button, onClickLabel = tile.title) { onOpen(tile.destination) }
                    ) {
                        StateShape(SHAPES[index % SHAPES.size], content.copy(alpha = 0.12f), Modifier.size(104.dp).align(Alignment.TopEnd).padding(12.dp), spin = false) {}
                        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                            Icon(tile.icon, contentDescription = null, tint = content, modifier = Modifier.size(32.dp))
                            Text(tile.title, style = MaterialTheme.typography.titleLargeEmphasized, color = content, maxLines = 1)
                            Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        itemsIndexed(tiles, key = { _, t -> t.destination.name }) { index, tile ->
            SegmentedListItem(
                onClick = { onOpen(tile.destination) },
                shapes = ListItemDefaults.segmentedShapes(index, tiles.size),
                modifier = Modifier.padding(horizontal = 16.dp),
                leadingContent = {
                    StateShape(SHAPES[index % SHAPES.size], scheme.primaryContainer, Modifier.size(44.dp), spin = false) {
                        Icon(tile.icon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                },
                supportingContent = { Text(tile.subtitle) },
                content = { Text(tile.title, style = MaterialTheme.typography.titleMediumEmphasized) }
            )
        }
    }
}
