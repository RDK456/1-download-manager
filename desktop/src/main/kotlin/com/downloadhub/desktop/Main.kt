package com.downloadhub.desktop

import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.delay
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
 * The smallest the window is allowed to get.
 *
 * The layout gives up columns, then the search box, then the toolbar captions as the
 * window narrows, and each of those has a defined order (see LibraryLayout). This is
 * where that runs out. At 520 dp the sidebar is 120 and the table still shows a name, a
 * size and a status; at 480 the size drops too and the row is a filename next to a word
 * like "Completed", which is a worse answer than not letting the window get there.
 *
 * The height is set by the sidebar rather than the table: the rail is the tallest thing
 * in the window, and it is a list rather than something that scrolls, so below about
 * 360 dp its lower entries are simply cut off.
 */
val MINIMUM_WINDOW_SIZE = java.awt.Dimension(520, 360)

/**
 * Windows entry point.
 *
 * The window can be dismissed without stopping the app: the minimise button and the
 * close button both hide it to the system tray, and closing asks first whether to
 * keep downloading in the background or quit. That is the whole reason a download
 * manager needs a tray icon.
 */
fun main(args: Array<String>) {
    // One copy, always. Every click on the app's icon used to start another one, each
    // with its own window and its own writes to the same queue file - and a magnet link
    // opened from a browser started a second copy rather than showing the first.
    val instance = SingleInstance()
    val targets = startupTargets(args)
    if (!instance.tryAcquire()) {
        // Another copy owns the app. It is the one that should act on what Windows asked
        // us to open, so the request is handed over rather than dropped; this copy then
        // has nothing left to do.
        IntakeChannel.handOff(targets)
        instance.release()
        return
    }

    val controller = DesktopController()
    controller.start()

    // Claim magnet links and .torrent files before anything else can be queued, so a
    // click on either lands here in the first place. Skipped when the running program
    // is not the packaged launcher - a Gradle run must never take over the machine's
    // handlers.
    val launcher = FileAssociations.launcherExe()
    if (launcher != null && !FileAssociations.isRegistered(launcher)) {
        FileAssociations.register(launcher)
    }

    // Anything handed over by a copy that started while this one was already running.
    // Taken once here, and polled below for anything that arrives later.
    controller.queueTargets(IntakeChannel.take() + targets)

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
            // Material 3 otherwise falls back to its light scheme, so every dialog
            // renders as a white box on top of this dark window - and near-white body
            // text on that white surface would be unreadable. The whole UI is drawn
            // in flat dark colours, so the scheme is declared to match rather than
            // left to default.
            MaterialTheme(colorScheme = AppTheme.darkScheme) {
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

            // A magnet link or a .torrent opened while the app was already running is
            // handed over by the copy Windows started for it. Polled rather than pushed,
            // because the copy that receives it has no way to know when the other one is
            // looking - and a request that arrives between polls waits on disk instead of
            // being lost. Coming in through the tray instead of the window is deliberate:
            // the point is to queue the download, not to interrupt what was on screen.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(INTAKE_POLL_MILLIS)
                    val arrived = IntakeChannel.take()
                    if (arrived.isNotEmpty()) {
                        controller.queueTargets(arrived)
                        showWindow()
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
                    // Set the title-bar icon here rather than letting it come from the
                    // exe's resources. The exe does carry the right icon, but only
                    // because the build passes iconFile to jpackage: run from Gradle, or
                    // from a copied app directory, and the resources are jpackage's
                    // defaults instead. Saying it explicitly means every way of starting
                    // the app looks the same.
                    //
                    // The minimum size goes with it, because the layout's give-up order
                    // eventually runs out.
                    LaunchedEffect(window) {
                        window.iconImages = AppArtwork.windowIcons()
                        window.minimumSize = MINIMUM_WINDOW_SIZE
                    }

                    LibraryScreen(
                        state = state,
                        actions = controller.actions,
                        onOpenAdd = { showAdd = true },
                        onOpenSettings = { showSettings = true },
                        onQuit = { closing = true }
                    )

                    // Every dialog is composed inside this Window, and that is not a
                    // style choice. Compose Desktop's Dialog reads LocalComposeScene,
                    // which only a Window provides. Composed up here, at the
                    // application level, it throws "CompositionLocal LocalComposeScene
                    // not provided" the instant the flag flips - during
                    // recomposition, so it takes the whole JVM down, and the packaged
                    // launcher then reports that with its one available message,
                    // "Failed to launch JVM". Inside the Window they are ordinary
                    // children of its scene.
                    //
                    // Hiding to the tray disposes the Window and these with it, which
                    // is the behaviour wanted anyway: a dialog cannot be used while
                    // its window is off screen.
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
                            onToggleCapture = controller.actions.setBrowserCapture,
                            extensionReady = state.extensionReady,
                            extensionPath = state.extensionPath,
                            onOpenExtensionFolder = controller.actions.openExtensionFolder
                        )
                    }

                    if (state.update !is UpdateCheck.Idle) {
                        UpdateDialog(
                            state = state,
                            onCheck = controller.actions.checkForUpdates,
                            onDownload = controller.actions.downloadUpdate,
                            onInstall = controller.actions.launchInstaller,
                            onSwitchToNewVersion = controller.actions.switchToDownloadedVersion,
                            onReveal = controller.actions.revealDownloadedInstaller,
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
            }
            }
        }
    } finally {
        tray.remove()
        controller.close()
        // Windows releases the lock when the process dies, but not tidily: a copy closed
        // by an update or a sign-out can leave the next launch waiting out the
        // acquisition timeout. Letting go of it explicitly keeps that to nothing.
        instance.release()
    }
}

/** How often the running copy collects links handed over by later launches. */
private const val INTAKE_POLL_MILLIS = 700L
