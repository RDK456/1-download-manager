package com.downloadhub.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The menu strip across the top of the window.
 *
 * Every entry does something. A menu that opens and swallows clicks is worse than
 * no menu, because it teaches the user that the window is broken.
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
    /** Where the bundled browser extensions were unpacked, for the integration help. */
    extensionRoot: java.io.File,
    /** The loopback pairing code the extension has to present. */
    pairingToken: String
) {
    var open by remember { mutableStateOf<String?>(null) }
    var showIntegration by remember { mutableStateOf(false) }

    Box {
        Surface(color = Color(0xFF1A2124)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "1 download manager",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF34D399)
                )
                Spacer(Modifier.width(20.dp))
                MenuLabel("File", 100.dp, open == "File") { open = toggle(open, "File") }
                MenuLabel("Tasks", 158.dp, open == "Tasks") { open = toggle(open, "Tasks") }
                MenuLabel("Tools", 222.dp, open == "Tools") { open = toggle(open, "Tools") }
                MenuLabel("Help", 280.dp, open == "Help") { open = toggle(open, "Help") }
                Spacer(Modifier.weight(1f))
                Text("v$version", fontSize = 11.sp, color = Color(0xFF6E7B7D))
            }
        }

        // Anchored under Tools, so it reads as a submenu of the entry that opened it
        // rather than as a stray panel in the middle of the window.
        if (showIntegration) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { showIntegration = false }
            ) {
                BrowserIntegrationMenu(
                    modifier = Modifier.padding(start = 222.dp, top = 32.dp),
                    extensionRoot = extensionRoot,
                    token = pairingToken,
                    onClose = { showIntegration = false },
                    onOpenFolder = ::revealFolder
                )
            }
        }
        // Only one menu is drawn at a time; the backdrop closes whichever is open.
        if (open != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable { open = null }
            ) {
                Surface(
                    modifier = Modifier
                        .padding(start = offsetFor(open), top = 32.dp)
                        .width(200.dp),
                    color = Color(0xFF1E2629),
                    shape = RoundedCornerShape(6.dp)
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
                                MenuDivider()
                                MenuItem("Check for updates") { open = null; onCheckUpdates() }
                            }

                            "Tools" -> {
                                MenuItem("Download Browser Integration") { open = null; showIntegration = true }
                                MenuDivider()
                                MenuItem("Settings") { open = null; onOpenSettings() }
                            }

                            else -> {
                                MenuItem("About 1 download manager") { open = null; onOpenSettings() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toggle(current: String?, name: String): String? = if (current == name) null else name

private fun offsetFor(open: String?): Dp = when (open) {
    "File" -> 100.dp
    "Tasks" -> 158.dp
    "Tools" -> 222.dp
    else -> 280.dp
}

@Composable
private fun MenuLabel(label: String, width: Dp, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = if (active) Color(0xFF0B1A14) else Color(0xFFB4C0C2),
        modifier = Modifier
            .width(width)
            .background(if (active) Color(0xFF34D399) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp)
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = Color(0xFFD6DEDF),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

@Composable
private fun MenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .height(1.dp)
            .background(Color(0xFF2C3639))
    )
}
