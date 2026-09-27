package com.downloadhub.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Asks what closing should mean.
 *
 * The honest answer depends on what is running: with transfers in flight, quietly
 * disappearing would look like the app had lost them, so the two choices are spelled
 * out rather than guessed at.
 */
@Composable
fun CloseDialog(
    activeCount: Int,
    trayAvailable: Boolean,
    onMinimizeToTray: () -> Unit,
    onQuit: () -> Unit,
    onCancel: () -> Unit
) {
    val canTray = trayAvailable
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Close 1 download manager?", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                when {
                    activeCount > 0 && canTray ->
                        "$activeCount transfer(s) are still running. You can keep them going in " +
                            "the system tray, or quit and stop them."

                    activeCount > 0 ->
                        "$activeCount transfer(s) are still running and will be cancelled. " +
                            "Anything already downloaded is kept."

                    canTray ->
                        "There is nothing running. You can close to the system tray or quit."

                    else -> "There is nothing running."
                }
            )
        },
        confirmButton = {
            if (canTray) {
                Button(onClick = onMinimizeToTray) { Text("Minimise to tray") }
            } else {
                Button(onClick = onQuit) { Text("Quit") }
            }
        },
        dismissButton = {
            Row2(
                onQuit = onQuit,
                onCancel = onCancel,
                showQuit = canTray
            )
        }
    )
}

@Composable
private fun Row2(onQuit: () -> Unit, onCancel: () -> Unit, showQuit: Boolean) {
    androidx.compose.foundation.layout.Row {
        if (showQuit) {
            TextButton(onClick = onQuit) { Text("Quit anyway") }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

