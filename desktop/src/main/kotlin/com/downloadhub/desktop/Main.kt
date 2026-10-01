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
import androidx.compose.ui.Alignment
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import com.downloadhub.core.ThemeMode
import com.downloadhub.core.ThemePalette
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.LinkParser
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
 * The pre-download window's floor.
 *
 * 620x430, not the main window's 520x360. Two columns plus the file list's five headings
 * need more than one does, and past this width the options column and the file list start
 * taking from each other rather than from the spare room.
 */
val PRE_DOWNLOAD_MINIMUM_SIZE = java.awt.Dimension(620, 430)

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

    // Anything handed over by a copy that started while this one was already running,
    // plus whatever was on the command line.
    //
    // Held rather than queued here. A `.torrent` among them has to be shown in the
    // pre-download dialog, and that dialog belongs to the window - which does not exist
    // yet at this point. Queueing here meant the review callback was still null, so the
    // torrent was dropped: no dialog, no row, and nothing in the list to show for it.
    // It is drained once the window has composed and set the callback.
    val startupTargets = IntakeChannel.take() + targets

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
            // Material 3 otherwise falls back to its light scheme, so every dialog renders as a
            // white box on top of this dark window - and near-white body text on that white
            // surface would be unreadable. The scheme is derived from the same palette the
            // flat-drawn rows read, so a dialog and the window it sits on are the same
            // theme, and so changing the theme moves both.
            val state by controller.ui.collectAsState()
            ProvideDesktopTheme(
                palette = ThemePalette.fromValue(state.settings.themePalette),
                mode = ThemeMode.fromValue(state.settings.themeMode)
            ) {
            val windowState = rememberWindowState(size = DpSize(1180.dp, 720.dp))
            var visible by remember { mutableStateOf(true) }
            // The link-entry step, which only exists to collect a link before the
            // pre-download dialog. It used to be the only step, and when that dialog was
            // replaced this flag was left setting a piece of state nothing read, so `+`
            // did nothing at all.
            var showNew by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var closing by remember { mutableStateOf(false) }
            // The download waiting to be looked at before it is queued, whatever kind
            // of download it is.
            var pendingAdd by remember { mutableStateOf<PendingDownload?>(null) }
            /**
             * The pre-download window's size and position.
             *
             * Remembered rather than recreated per open, so a window the user has made
             * big for a forty-file release is still big for the next one. Recreating it
             * each time is what makes a resized dialog snap back to its default every
             * time it opens.
             */
            val preDownloadState = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                size = DpSize(980.dp, 640.dp)
            )
            // How a link typed into the box was going to be added, so a paste and a drop
            // reach the dialog the same way.
            var typedLink by remember { mutableStateOf("") }
            var problem by remember { mutableStateOf<String?>(null) }

            // The one-time setup screen, shown on the first launch of any profile that
            // has not seen it. The flag is written whichever button is pressed.
            var showSetup by remember { mutableStateOf(shouldShowSetup(controller.settings.value)) }

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

                        // Drag and drop for .torrent files, attached to the AWT window
                        // because this Compose version has no drop pointer event at all.
                        installTorrentDropTarget(
                            window = window,
                            onTorrent = { file ->
                                pendingAdd = PendingDownload.forLink(file.absolutePath)
                                    ?: run {
                                        problem = "${file.name} could not be read."
                                        null
                                    }
                                showWindow()
                            },
                            onProblem = { reason -> problem = reason }
                        )
                    }

                    LibraryScreen(
                        state = state,
                        actions = controller.actions,
                        onOpenAdd = { showNew = true },
            // A search result goes straight into the pre-download window, through the
            // same PendingDownload.forLink every other magnet takes - so a magnet found by
            // searching gets the file list fetched, the folder picker and the stop
            // condition exactly as a pasted one does.
            onOpenAddForLink = { link ->
                pendingAdd = PendingDownload.forLink(link)
                    ?: run {
                        problem = "That link could not be read."
                        null
                    }
            },
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
                    // Every route into the app - a `.torrent` from Explorer, a magnet from
                    // a browser, a link from a second copy - comes through here and is
                    // shown in the pre-download dialog. It used to take a File and so
                    // could only carry a `.torrent` off disk, which meant a magnet from
                    // the browser had nowhere to go and was queued unseen.
                    LaunchedEffect(Unit) {
                        controller.onDownloadNeedsReview = { link ->
                            val pending = PendingDownload.forLink(link)
                            if (pending == null) {
                                problem = "That cannot be downloaded: $link"
                            } else {
                                pendingAdd = pending
                            }
                            // Raised whether or not it parsed. A link that cannot be read
                            // has to say so, and the message would otherwise sit behind a
                            // window the user cannot see.
                            showWindow()
                        }
                        // Only now that the callback exists can a link be shown in the
                        // dialog, so the command line and any hand-over are acted on
                        // after this rather than before.
                        controller.queueTargets(startupTargets)
                    }

                    if (showSetup) {
                        SetupDialog(
                            downloadDir = state.settings.downloadDir,
                            items = setupItems(
                                ytDlpReady = state.ytDlpStatus.isNotBlank() &&
                                    !state.ytDlpStatus.contains("could not", ignoreCase = true),
                                ytDlpDetail = state.ytDlpStatus,
                                extensionReady = state.extensionReady,
                                captureEnabled = state.settings.browserCaptureEnabled
                            ),
                            onChooseFolder = { pickFolder(File(state.settings.downloadDir)) },
                            onFinish = { dir ->
                                showSetup = false
                                // Applied through settings rather than directly, so a bad
                                // path is caught the same way it is everywhere else.
                                controller.actions.updateSettings(
                                    state.settings.copy(downloadDir = dir, setupComplete = true)
                                )
                            },
                            onDismiss = {
                                showSetup = false
                                // Marked complete even when dismissed. A screen that
                                // reappears every launch until it is filled in is not a
                                // setup screen, it is a startup failure.
                                controller.actions.updateSettings(
                                    state.settings.copy(setupComplete = true)
                                )
                            }
                        )
                    }

                    // The link-entry step. Always leads to the pre-download dialog, so
                    // there is no way to queue anything that has not been looked at.
                    if (showNew) {
                        NewDownloadDialog(
                            onPickTorrent = { chooseTorrentFile(window) },
                            onSubmit = { pending ->
                                showNew = false
                                pendingAdd = pending
                            },
                            onDismiss = { showNew = false }
                        )
                    }

                    /**
                     * The pre-download window is a real window, not a dialog.
                     *
                     * It has to be. The things asked for - click outside and it stays,
                     * drag it somewhere, drag it bigger, maximise it - are all things a
                     * hand-drawn panel over the main window cannot do, because it is part
                     * of that window and shares its bounds. An OS window gets them from
                     * the window manager, which already knows how.
                     *
                     * It is a `Window` rather than a `Dialog`, so it brings its own compose
                     * scene and needs none from the main one. Opening the magnet's file
                     * list raises the *main* window, not this one, so it comes forward to
                     * be seen.
                     */
                    pendingAdd?.let { pending ->
                        // A video goes to the quality chooser instead of this window.
                        //
                        // Everything this window offers is about a torrent: which files out of
                        // forty, what to call the content folder, when to stop seeding. A video
                        // has no file list and no share ratio, so those options would be a pane
                        // of disabled controls - and the one thing a video does need choosing,
                        // which is its quality, is not here at all. The old path took a height
                        // dropdown in a dialog and guessed at what was on offer.
                        if (LinkParser.sourceFor(pending.link) == DownloadSource.YOUTUBE) {
                            YouTubeQualityDialog(
                                url = pending.link,
                                loader = controller.actions.listVideoFormats,
                                onPick = { choice, audioOnly ->
                                    // Closed before the row appears, for the same reason the
                                    // window below is: two things on screen at once reads as
                                    // neither having been asked for.
                                    pendingAdd = null
                                    controller.actions.addChosenVideo(
                                        pending.link, choice, audioOnly, null
                                    )
                                },
                                onDismiss = { pendingAdd = null }
                            )
                            return@let
                        }
                        Window(
                            onCloseRequest = { pendingAdd = null },
                            title = if (pending.isTorrent) {
                                "Add torrent - 1 download manager"
                            } else {
                                "Add download - 1 download manager"
                            },
                            state = preDownloadState,
                            resizable = true
                        ) {
                            // The same icon as the main window, set explicitly rather than
                            // left to the exe's resources. Set here and not inherited, this
                            // window carried Compose's own default - a running Java cup -
                            // while the app it belongs to had an icon everywhere else.
                            LaunchedEffect(window) {
                                window.iconImages = AppArtwork.windowIcons()
                                // A floor, and a deliberate one. The window is free to be
                                // resized to any size at all, and below about this the two
                                // columns have nothing to give each other: the options lose
                                // the file list and the file list loses its headings. A
                                // resizable window needs a point where "smaller" stops
                                // meaning "worse".
                                window.minimumSize = PRE_DOWNLOAD_MINIMUM_SIZE
                            }
                            AddDownloadDialog(
                                pending = pending,
                                defaultDirectory = state.settings.downloadDir,
                                deleteCacheWhenRemoved = state.settings.deleteCacheWhenRemoved,
                                // The window is the parent, so the chooser is owned by the
                                // app and appears in front of it rather than behind.
                                onPickDirectory = { pickFolder(File(state.settings.downloadDir)) },
                                // A magnet carries no file list. It has one - in the
                                // swarm's metadata, a few kilobytes away - and until it was
                                // asked for, a magnet was the one kind of download where
                                // you could not see what you were about to get, take three
                                // files out of forty, or say no.
                                onLoadMetadata = controller.actions.readMagnetMetadata,
                                onConfirm = { request ->
                                    // Close first: leaving the window open while the row
                                    // appears underneath reads as two things at once.
                                    pendingAdd = null
                                    controller.actions.addPrepared(request)
                                },
                                onDismiss = { pendingAdd = null }
                            )
                        }
                    }

                    problem?.let { reason ->
                        TorrentProblemDialog(message = reason) { problem = null }
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
                            onChooseCacheFolder = controller.actions.chooseCacheFolder,
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
