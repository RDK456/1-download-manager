package com.downloadhub.app.ui.expressive.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.X

/**
 * Small pieces of state the list shares with the frame around it. While rows are selected
 * the add button steps aside for the selection toolbar.
 */
object ExpressiveChromeState {
    var selecting: Boolean by androidx.compose.runtime.mutableStateOf(false)
}

/** One destination in the bottom bar. [badge] is a count of things happening, 0 for none. */
class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val badge: Int = 0, val onClick: () -> Unit)

/**
 * The root tabs. The short navigation bar holds three to five items on a phone, which is
 * exactly the app's five, so there is no room for a sixth: anything new goes under one of
 * these. The indicator and the icon spring on the theme's motion scheme.
 */
@Composable
fun ExpressiveNavBar(items: List<NavItem>, modifier: Modifier = Modifier) {
    // Five labels across a phone cannot grow with the system text size: at 200% they run into
    // each other. Their size is capped (the rest of the app still follows the system setting).
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale.coerceAtMost(1.3f))
    ) {
    ShortNavigationBar(modifier = modifier, arrangement = ShortNavigationBarArrangement.EqualWeight) {
        items.forEach { item ->
            ShortNavigationBarItem(
                selected = item.selected,
                onClick = item.onClick,
                icon = {
                    BadgedBox(badge = { if (item.badge > 0) Badge { Text(if (item.badge > 99) "99+" else item.badge.toString()) } }) {
                        Icon(item.icon, contentDescription = null)
                    }
                },
                label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Clip) }
            )
        }
    }
    }
}

/**
 * The top bar. A back arrow on sub-pages, settings and the filter on the list tabs. The
 * filter shows a dot while one is active, so a list that looks short is explained.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpressiveTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onFilter: (() -> Unit)? = null,
    filterActive: Boolean = false,
    onSettings: (() -> Unit)? = null
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                // The arrow points the other way in a right-to-left layout.
                val flip = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
                IconButton(onBack) { Icon(Lucide.ArrowLeft, contentDescription = "Back", modifier = Modifier.graphicsLayer { scaleX = flip }) }
            }
        },
        actions = {
            if (onFilter != null) {
                IconButton(onFilter) {
                    BadgedBox(badge = { if (filterActive) Badge() }) {
                        Icon(Lucide.SlidersHorizontal, contentDescription = if (filterActive) "Filters, one is on" else "Filters")
                    }
                }
            }
            if (onSettings != null) IconButton(onSettings) { Icon(Lucide.Settings, contentDescription = "Settings") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background)
    )
}

/** An entry in the add menu. */
class AddAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * The add button. It opens into a menu: the button turns into a close button and the
 * choices spring in above it, one after another. Choosing one closes the menu first.
 */
@Composable
fun AddMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: List<AddAction>,
    modifier: Modifier = Modifier
) {
    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = onExpandedChange,
                modifier = Modifier.semantics { contentDescription = if (expanded) "Close add menu" else "Add download" }
            ) {
                val showClose by remember { derivedStateOf { checkedProgress > 0.5f } }
                Icon(
                    if (showClose) Lucide.X else Lucide.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) {
        actions.forEach { action ->
            FloatingActionButtonMenuItem(
                onClick = { onExpandedChange(false); action.onClick() },
                icon = { Icon(action.icon, contentDescription = null) },
                text = { Text(action.label) }
            )
        }
    }
}

/** An empty box that reserves where the add menu sits, so lists can leave room for it. */
@Composable
fun AddMenuSpace(modifier: Modifier = Modifier) = Box(modifier.size(88.dp))
