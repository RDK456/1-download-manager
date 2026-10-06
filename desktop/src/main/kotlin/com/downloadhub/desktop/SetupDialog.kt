package com.downloadhub.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CircleCheck
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Whether the first-run setup should be shown.
 *
 * The stored flag alone decides, deliberately. A new install and an existing profile
 * upgrading into a version that has this screen are both unflagged, so both are shown it
 * once. Gating on "is this a first run" instead would mean an upgrade never showed it at
 * all, and the flag is written on the first launch either way, so afterwards there is no
 * way to tell the two cases apart.
 *
 * Shown once, ever: the flag is set whether the user presses Start or dismisses it. A
 * screen that reappears every launch until it is filled in is a startup failure.
 */
fun shouldShowSetup(settings: DesktopSettings): Boolean = !settings.setupComplete

/** One line saying whether something the app needs is actually there. */
data class SetupItem(val label: String, val detail: String, val ready: Boolean)

/**
 * What the setup screen shows, assembled from what the app already knows.
 *
 * Built here rather than in the composable so "is yt-dlp actually usable" is decided once
 * and can be tested. A setup screen that reports a feature as ready when it is not is
 * worse than no setup screen, because it is believed.
 */
fun setupItems(
    ytDlpReady: Boolean,
    ytDlpDetail: String,
    extensionReady: Boolean,
    captureEnabled: Boolean
): List<SetupItem> = listOf(
    SetupItem(
        label = "YouTube downloads",
        detail = if (ytDlpReady) ytDlpDetail.ifBlank { "yt-dlp is ready" }
        else "yt-dlp could not be unpacked, so YouTube links will not work yet",
        ready = ytDlpReady
    ),
    SetupItem(
        label = "Browser integration",
        detail = when {
            extensionReady && captureEnabled -> "Right-click a link in your browser to send it here"
            extensionReady -> "The extension is unpacked but switched off in settings"
            else -> "The extension is unpacked; add it from Tools when you want it"
        },
        // Present either way: it is unpacked, and the user turns it on when they want it.
        ready = extensionReady
    )
)

/**
 * The first-run screen.
 *
 * Deliberately one screen and one button. A wizard that asks questions before letting
 * anyone download anything is a wizard that gets skipped, and the two things worth asking
 * about - where files go, and whether the tools unpacked - are both visible from the
 * window already. So this confirms what is set up, offers the one setting worth changing
 * up front, and gets out of the way.
 */
@Composable
fun SetupDialog(
    downloadDir: String,
    items: List<SetupItem>,
    onChooseFolder: () -> File?,
    onFinish: (downloadDir: String) -> Unit,
    onDismiss: () -> Unit
) {
    var folder by remember { mutableStateOf(downloadDir) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Inside the Window, like every dialog here.
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("1 download manager is ready") },
        text = {
            Column {
                Text(
                    "Drag a link onto the window, or drop a .torrent file on it.",
                    fontSize = 12.sp,
                    color = AppTheme.Palette.muted
                )
                Spacer(Modifier.height(12.dp))

                Text("Where downloads go", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        onChooseFolder()?.let { folder = it.absolutePath }
                    }) { Text("Change") }
                }

                Spacer(Modifier.height(14.dp))
                Text("What is set up", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                items.forEach { item -> SetupRow(item) }
            }
        },
        confirmButton = { TextButton(onClick = { onFinish(folder) }) { Text("Start") } }
    )
}

@Composable
private fun SetupRow(item: SetupItem) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        // A status, not a choice, so an icon rather than a tick box nobody can press; sized
        // to the first line and spaced from it, where the box sat flush and 4 dp higher.
        androidx.compose.material3.Icon(
            if (item.ready) com.composables.icons.lucide.Lucide.CircleCheck else com.composables.icons.lucide.Lucide.CircleAlert,
            contentDescription = if (item.ready) "Ready" else "Needs attention",
            tint = if (item.ready) AppTheme.success else AppTheme.Palette.accent,
            modifier = Modifier.size(18.dp)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.label, fontSize = 12.sp)
            Text(
                item.detail,
                fontSize = 11.sp,
                color = if (item.ready) AppTheme.Palette.muted else AppTheme.Palette.accent
            )
        }
    }
}