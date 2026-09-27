package com.downloadhub.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import kotlin.system.exitProcess
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Windows entry point.
 *
 * The window can be dismissed without stopping the app: the minimise button and the
 * close button both hide it to the system tray, and closing asks first whether to
 * keep downloading in the background or quit. That is the whole reason a download
 * manager needs a tray icon.
 */
fun main() {
    val controller = DesktopController()
    controller.start()

    // Declared outside `application` so the tray callbacks can reach the window state.
    var showWindow: () -> Unit = {}
    var hideWindow: () -> Unit = {}
    var requestExit: () -> Unit = { controller.close(); exitProcess(0) }

    val tray = SystemTrayIcon(
        onShow = { showWindow() },
        onPauseAll = { controller.actions.pauseAll() },
        onResumeAll = { controller.actions.resumeAll() },
        onExit = { requestExit() }
    )
    tray.install()

    try {
        application {
            val state by controller.ui.collectAsState()
            val windowState = rememberWindowState(size = DpSize(1180.dp, 720.dp))
            var visible by remember { mutableStateOf(true) }
            var showAdd by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var closing by remember { mutableStateOf(false) }

            showWindow = { visible = true; windowState.isMinimized = false }
            hideWindow = { visible = false }
            requestExit = { controller.close(); exitApplication() }

            // Minimising hides to the tray rather than leaving a taskbar button that
            // suggests the app is still sitting there waiting for attention.
            LaunchedEffect(windowState.isMinimized) {
                if (windowState.isMinimized) {
                    windowState.isMinimized = false
                    if (state.settings.closeToTray && tray.available) {
                        visible = false
                    }
                }
            }

            LaunchedEffect(state.busyCount) {
                val items = state.items.map { it.toCoreItem() }
                tray.update(
                    com.downloadhub.core.DownloadLibrary.activeCount(items),
                    com.downloadhub.core.DownloadLibrary.totalSpeed(items)
                )
            }

            // The window is only composed while visible, which is what removes it
            // from the taskbar when it goes to the tray.
            if (visible) {
                Window(
                    onCloseRequest = { closing = true },
                    state = windowState,
                    title = "1 download manager"
                ) {
                    LibraryScreen(
                        state = state,
                        actions = controller.actions,
                        onOpenAdd = { showAdd = true },
                        onOpenSettings = { showSettings = true },
                        onQuit = { closing = true }
                    )
                }
            }

            if (showAdd) {
                AddDownloadDialog(
                    onDismiss = { showAdd = false },
                    onAdd = { link, audioOnly, format, height, playlist ->
                        controller.actions.addDownload(link, audioOnly, format, height, playlist)
                        showAdd = false
                    }
                )
            }

            if (showSettings) {
                SettingsDialog(
                    settings = state.settings,
                    ytDlp = state.ytDlpStatus,
                    captureActive = state.captureActive,
                    capturePort = state.capturePort,
                    onDismiss = { showSettings = false },
                    onSave = { updated ->
                        controller.actions.updateSettings(updated)
                        showSettings = false
                    },
                    onChooseFolder = controller.actions.chooseFolder,
                    onToggleCapture = controller.actions.setBrowserCapture
                )
            }

            if (state.update !is UpdateCheck.Idle) {
                UpdateDialog(
                    state = state,
                    onCheck = controller.actions.checkForUpdates,
                    onDownload = controller.actions.downloadUpdate,
                    onInstall = controller.actions.launchInstaller,
                    onDismiss = controller.actions.dismissUpdate
                )
            }

            if (closing) {
                CloseDialog(
                    activeCount = state.busyCount,
                    trayAvailable = tray.available,
                    onMinimizeToTray = { closing = false; hideWindow() },
                    onQuit = { closing = false; requestExit() },
                    onCancel = { closing = false }
                )
            }
        }
    } finally {
        tray.remove()
        controller.close()
    }
}
