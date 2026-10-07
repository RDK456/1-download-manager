package com.downloadhub.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The menu strip across the top of the window.
 *
 * Every entry does something. A menu that opens and swallows clicks is worse than
 * no menu, because it teaches the user that the window is broken.
 *
 * Three things were wrong with this bar.
 *
 * Each label was given a fixed width, and those same four numbers were also used as
 * where each menu panel opens, so a panel opened well to the left of the entry that
 * opened it. Measuring where each label lands fixes that and lets the bar be as
 * narrow as it needs to be.
 *
 * It was 930 dp wide, and the window can be 520, so Help was cut off the right. It is
 * four short labels now, and the wordmark goes first because it repeats the window
 * title.
 *
 * And the panel was a flat rectangle laid straight onto the content: no shadow, no
 * edge, nothing behind it. It read as a hole cut in the window rather than something
 * floating above it. A dimmed backdrop, a shadow and a hairline are what make it read
 * as a layer - and they are also the usual reason a dropdown in a screenshot looks
 * like part of the UI rather than pasted on top of it.
 */
@Composable
fun MenuBar(
    version: String,
    onCheckUpdates: () -> Unit,
    onOpenAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onQuit: () -> Unit,
    /** Tools > Create torrent. */
    onCreateTorrent: () -> Unit = {},
    /** Where the bundled browser extensions were unpacked, for the integration help. */
    extensionRoot: java.io.File,
    /** The loopback pairing code the extension has to present. */
    pairingToken: String
) {
    var open by remember { mutableStateOf<String?>(null) }
    var showIntegration by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) AboutDialog(version) { showAbout = false }
    // Where each label ended up, so its panel opens underneath it.
    val offsets = remember { mutableStateMapOf<String, Int>() }

    Box {
        // A hairline under the menu strip, so the window chrome ends somewhere definite.
        Surface(color = AppTheme.Palette.surface, modifier = Modifier.drawBehind {
            drawLine(AppTheme.Palette.outlineVariant, androidx.compose.ui.geometry.Offset(0f, size.height - 1f), androidx.compose.ui.geometry.Offset(size.width, size.height - 1f), 1f)
        }) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val roomForWordmark = maxWidth >= MENU_BAR_WORDMARK_MINIMUM
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MENU_BAR_HEIGHT)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (roomForWordmark) {
                        // The wordmark on a small LCD plate, as on the Android app.
                        LcdPlate("1DM")
                        Spacer(Modifier.width(20.dp))
                    }
                    MenuLabel("File", open == "File", { offsets["File"] = it }) {
                        open = toggle(open, "File")
                    }
                    MenuLabel("Tasks", open == "Tasks", { offsets["Tasks"] = it }) {
                        open = toggle(open, "Tasks")
                    }
                    MenuLabel("Tools", open == "Tools", { offsets["Tools"] = it }) {
                        open = toggle(open, "Tools")
                    }
                    MenuLabel("Help", open == "Help", { offsets["Help"] = it }) {
                        open = toggle(open, "Help")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("v$version", fontSize = 11.sp, color = AppTheme.Palette.faint)
                }
            }
        }

        // Anchored under Tools, so it reads as a submenu of the entry that opened it
        // rather than as a stray panel in the middle of the window.
        if (showIntegration) {
            Box(Modifier.fillMaxSize()) {
                Scrim { showIntegration = false }
                BrowserIntegrationMenu(
                    modifier = Modifier.padding(
                        start = (offsets["Tools"] ?: 0).dp,
                        top = MENU_BAR_HEIGHT
                    ),
                    extensionRoot = extensionRoot,
                    token = pairingToken,
                    onClose = { showIntegration = false },
                    onOpenFolder = ::revealFolder
                )
            }
        }
        // Only one menu is drawn at a time; the backdrop closes whichever is open.
        if (open != null) {
            Box(Modifier.fillMaxSize()) {
                Scrim { open = null }
                Surface(
                    modifier = Modifier
                        .padding(start = (offsets[open] ?: 0).dp, top = MENU_BAR_HEIGHT)
                        .width(MENU_PANEL_WIDTH),
                    color = MENU_PANEL_COLOUR,
                    shape = RoundedCornerShape(8.dp),
                    // A shadow and a hairline, so the panel sits above the window
                    // instead of being cut out of it.
                    shadowElevation = 14.dp,
                    border = BorderStroke(1.dp, MENU_PANEL_EDGE)
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        when (open) {
                            "File" -> {
                                MenuItem("New Download") { open = null; onOpenAdd() }
                                MenuItem("Settings") { open = null; onOpenSettings() }
                                MenuDivider()
                                MenuItem("Check for updates") { open = null; onCheckUpdates() }
                                MenuDivider()
                                MenuItem("Exit") { open = null; onQuit() }
                            }

                            "Tasks" -> {
                                MenuItem("Resume all") { open = null; onResumeAll() }
                                MenuItem("Pause all") { open = null; onPauseAll() }
                            }

                            "Tools" -> {
                                MenuItem("Create torrent...") { open = null; onCreateTorrent() }
                                MenuItem("Download Browser Integration") {
                                    open = null; showIntegration = true
                                }
                                MenuDivider()
                                MenuItem("Settings") { open = null; onOpenSettings() }
                            }

                            else -> {
                                MenuItem("Report a problem") { open = null; browse("https://github.com/${DesktopUpdateChecker.REPOSITORY}/issues") }
                                MenuItem("Source code") { open = null; browse("https://github.com/${DesktopUpdateChecker.REPOSITORY}") }
                                MenuDivider()
                                MenuItem("About 1 download manager") { open = null; showAbout = true }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What sits behind an open menu: a dim, and the click target that closes it.
 *
 * Without the dim the panel had nothing to float above, so the content it covered
 * stayed at full brightness and the panel looked painted onto it.
 */
@Composable
private fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MENU_SCRIM)
            .clickable(onClick = onClick)
    )
}

private fun browse(url: String) {
    Thread { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }.start()
}

/** What Help > About used to do was open Settings. This says what the app is, and its keys. */
@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("1 download manager") },
        text = {
            Column {
                Text("Version $version", fontSize = 12.sp, color = AppTheme.Palette.muted)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Direct downloads, YouTube and BitTorrent in one queue.",
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(14.dp))
                Text("Keyboard", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                listOf(
                    "Ctrl+N" to "New download",
                    "Ctrl+A" to "Select every row in the list",
                    "Delete" to "Remove the selected downloads",
                    "Enter / double-click" to "Open a finished file",
                    "Esc" to "Clear the selection"
                ).forEach { (key, what) ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(key, fontSize = 12.sp, color = AppTheme.Palette.accent, modifier = Modifier.width(150.dp))
                        Text(what, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { browse("https://github.com/${DesktopUpdateChecker.REPOSITORY}") }) {
                Text("GitHub")
            }
        }
    )
}

/** The height of the strip itself; a panel opens directly below it. */
private val MENU_BAR_HEIGHT = 34.dp

/** Below this the wordmark is dropped and only the menus are shown. */
private val MENU_BAR_WORDMARK_MINIMUM = 700.dp

private val MENU_PANEL_WIDTH = 210.dp
private val MENU_PANEL_COLOUR: Color get() = AppTheme.Palette.menuPanel
private val MENU_PANEL_EDGE: Color get() = AppTheme.Palette.menuEdge
/** A scrim is the same in every theme: black, at half strength, over whatever is behind it. */
private val MENU_SCRIM = Color(0x8C000000)

/** Shared by a label and the items in the panel it opens, so their text lines up. */
private val MENU_ITEM_INSET = 12.dp

private fun toggle(current: String?, name: String): String? = if (current == name) null else name

/**
 * One menu entry, as wide as its own name.
 *
 * [onPlaced] reports the x position the entry was drawn at, which is what the panel
 * that opens from it is positioned by.
 */
@Composable
private fun MenuLabel(
    label: String,
    active: Boolean,
    onPlaced: (Int) -> Unit,
    onClick: () -> Unit
) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (active) AppTheme.Palette.accentContainer else AppTheme.Palette.muted,
        modifier = Modifier
            .onGloballyPositioned { onPlaced(it.positionInRoot().x.toInt()) }
            .background(if (active) AppTheme.Palette.accent else Color.Transparent)
            .clickable(onClick = onClick)
            // The same inset the items in the panel use, so an item's text lines up
            // with the entry it belongs to. These were 12 and 14, which is a two-pixel
            // disagreement you can see once you have looked for it.
            .padding(horizontal = MENU_ITEM_INSET, vertical = 5.dp)
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = AppTheme.Palette.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MENU_ITEM_INSET, vertical = 7.dp)
    )
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .height(1.dp)
            .background(AppTheme.Palette.outlineVariant)
    )
}
