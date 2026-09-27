package com.downloadhub.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Windows entry point.
 *
 * The window and the queue are the same process, so closing the window ends the
 * app. Transfers in flight are marked paused on the way out and resume from their
 * partial file next launch, which is why nothing is silently lost.
 */
fun main() {
    val controller = DesktopController()
    controller.start()

    try {
        application {
            val state by controller.ui.collectAsState()
            val windowState = rememberWindowState(size = DpSize(1040.dp, 740.dp))
            Window(
                onCloseRequest = {
                    controller.close()
                    exitApplication()
                },
                state = windowState,
                title = "1 download manager"
            ) {
                DownloadHubDesktopApp(state, controller.actions)
            }
        }
    } finally {
        controller.close()
    }
}
