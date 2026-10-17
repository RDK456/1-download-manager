package com.downloadhub.app.ui.expressive.shell

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * The navigation rail for windows wider than a phone. Medium windows get the collapsed
 * form (icon over label); expanded windows get it open, labels beside the icons. The same
 * five destinations as the bottom bar, in the same order, so nothing moves in meaning when
 * a window is resized or a foldable is opened.
 */
@Composable
fun ExpressiveRail(items: List<NavItem>, expanded: Boolean, modifier: Modifier = Modifier) {
    val state = rememberWideNavigationRailState(
        initialValue = if (expanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed
    )
    LaunchedEffect(expanded) { if (expanded) state.expand() else state.collapse() }
    val open = state.targetValue == WideNavigationRailValue.Expanded
    WideNavigationRail(modifier = modifier, state = state) {
        items.forEach { item ->
            WideNavigationRailItem(
                selected = item.selected,
                onClick = item.onClick,
                icon = {
                    BadgedBox(badge = { if (item.badge > 0) Badge { Text(if (item.badge > 99) "99+" else item.badge.toString()) } }) {
                        Icon(item.icon, contentDescription = null)
                    }
                },
                label = { Text(item.label, maxLines = 1) },
                railExpanded = open
            )
        }
    }
}
