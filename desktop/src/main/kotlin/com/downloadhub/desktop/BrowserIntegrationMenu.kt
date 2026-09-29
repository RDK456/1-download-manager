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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Desktop
import java.io.File

/** A browser the app can hand downloads over from. */
enum class BrowserTarget(
    val label: String,
    /** Firefox cannot load a Chromium extension, so it needs its own build. */
    val usesFirefoxBuild: Boolean,
    val storeUrl: String
) {
    FIREFOX("Mozilla Firefox", true, "https://addons.mozilla.org/firefox/"),
    CHROME("Google Chrome", false, "https://chromewebstore.google.com/"),
    EDGE("Microsoft Edge", false, "https://microsoftedge.microsoft.com/addons"),
    OPERA("Opera", false, "https://addons.opera.com/");
}

/**
 * Tools -> Download Browser Integration.
 *
 * Each browser is listed separately because the instructions genuinely differ.
 * Chrome, Edge and Opera all load the same Chromium extension; Firefox cannot load
 * a Chromium extension at all, so it ships its own build with the `browser.*` API.
 * Telling a Firefox user to load the Chromium folder produces an extension that
 * silently does nothing.
 */
@Composable
fun BrowserIntegrationMenu(
    extensionRoot: File,
    token: String,
    onClose: () -> Unit,
    onOpenFolder: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    var target by remember { mutableStateOf<BrowserTarget?>(null) }

    Surface(
        modifier = modifier.width(320.dp),
        color = AppTheme.Palette.raised,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                "Download Browser Integration",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.Palette.accent,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 3.dp)
                    .height(1.dp)
                    .background(AppTheme.Palette.outlineVariant)
            )

            BrowserTarget.entries.forEach { browser ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { target = browser }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrowserDot(browser)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        browser.label,
                        fontSize = 12.sp,
                        color = AppTheme.Palette.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Text("›", fontSize = 13.sp, color = AppTheme.Palette.faint)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(1.dp)
                    .background(AppTheme.Palette.outlineVariant)
            )
            MenuRow("Open extension folder", onClick = { onOpenFolder(extensionRoot) })
            MenuRow("Close", onClick = onClose)
        }
    }

    val chosen = target
    if (chosen != null) {
        BrowserHelpDialog(
            browser = chosen,
            extensionRoot = extensionRoot,
            token = token,
            // The dialog reveals the subfolder for the chosen browser, not the
            // root: a Firefox user must not be handed the Chromium build.
            onOpenFolder = onOpenFolder,
            onDismiss = { target = null }
        )
    }
}

@Composable
private fun BrowserDot(browser: BrowserTarget) {
    val colour = when (browser) {
        BrowserTarget.FIREFOX -> Color(0xFFE66000)
        BrowserTarget.CHROME -> Color(0xFF4285F4)
        BrowserTarget.EDGE -> Color(0xFF0F7BC4)
        BrowserTarget.OPERA -> Color(0xFFFF1B2D)
    }
    Box(
        Modifier
            .size(14.dp)
            .background(colour, CircleShape)
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = AppTheme.Palette.muted,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

/**
 * The per-browser instructions.
 *
 * Chromium browsers load the unpacked folder the same way; Firefox needs about:addons
 * instead, which is the whole reason this is a dialog rather than a single line of
 * text in Settings.
 */
@Composable
private fun BrowserHelpDialog(
    browser: BrowserTarget,
    extensionRoot: File,
    token: String,
    onOpenFolder: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val steps = if (browser.usesFirefoxBuild) {
        listOf(
            "Open the folder shown below.",
            "In Firefox, go to about:addons and choose Tools, then Developer, then Load Temporary Add-on.",
            "Select manifest.json from the Firefox folder.",
            "Click the extension's icon and paste the pairing code."
        )
    } else {
        listOf(
            "Open the folder shown below.",
            "Go to $browser.label's extension page and turn on Developer mode.",
            "Choose Load unpacked and select that folder.",
            "Click the extension's icon and paste the pairing code."
        )
    }
    val folder = if (browser.usesFirefoxBuild) {
        File(extensionRoot, "firefox")
    } else {
        File(extensionRoot, "chromium")
    }

    // Material 3 would otherwise paint this dialog on its light surface, which puts
    // the app's near-white body text on a white background. The whole window is dark,
    // so the dialog has to be too.
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
            properties = APP_DIALOG_PROPERTIES,
        title = { Text(browser.label, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(bottom = 7.dp)) {
                        Text(
                            "${index + 1}.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.Palette.accent,
                            modifier = Modifier.width(16.dp)
                        )
                        Text(step, fontSize = 12.sp, color = AppTheme.Palette.onSurface)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Folder to load", fontSize = 11.sp, color = AppTheme.Palette.muted)
                Text(
                    folder.absolutePath,
                    fontSize = 11.sp,
                    color = AppTheme.Palette.accent
                )
                Spacer(Modifier.height(8.dp))
                Text("Pairing code", fontSize = 11.sp, color = AppTheme.Palette.muted)
                Text(token, fontSize = 12.sp, color = AppTheme.Palette.accent)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onOpenFolder(folder) }) {
                Text("Open folder", color = AppTheme.Palette.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Close", color = AppTheme.Palette.muted)
            }
        }
    )
}

/**
 * Opens a folder in Explorer, reporting whether it worked.
 *
 * Explorer is the only way to do this, and it can be missing or refuse, so the answer
 * is returned rather than assumed - a button that silently does nothing looks broken.
 */
internal fun revealFolder(folder: File): Boolean = runCatching {
    val explorer = File("C:/Windows/explorer.exe")
    if (explorer.isFile) {
        ProcessBuilder(explorer.absolutePath, folder.absolutePath)
            .redirectErrorStream(true)
            .start()
        true
    } else {
        false
    }
}.getOrDefault(false)
