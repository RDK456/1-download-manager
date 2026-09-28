package com.downloadhub.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the UI renders, in one immutable value. */
data class DesktopUiState(
    val items: List<QueuedDownload> = emptyList(),
    val settings: DesktopSettings = DesktopSettings(),
    val busyCount: Int = 0,
    val ytDlpStatus: String = "",
    val captureActive: Boolean = false,
    val capturePort: Int = 0,
    val message: String? = null,
    val appVersion: String = APP_VERSION,
    val torrentsTab: Boolean = false,
    val update: UpdateCheck = UpdateCheck.Idle,
    val updateProgress: Int = -1,
    val downloadedInstaller: String? = null,
    val extensionReady: Boolean = false,
    val extensionPath: String = "",
    val palette: ColorScheme = DarkPalette
)

/** Callbacks the UI is allowed to invoke. */
data class DesktopActions(
    val addDownload: (String, Boolean, String, Int?, Boolean) -> Unit,
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    val retry: (String) -> Unit,
    val remove: (String) -> Unit,
    val pauseAll: () -> Unit,
    val resumeAll: () -> Unit,
    val updateSettings: (DesktopSettings) -> Unit,
    val chooseFolder: () -> File?,
    val setBrowserCapture: (Boolean) -> Unit,
    val consumeMessage: () -> Unit,
    val setTorrentsTab: (Boolean) -> Unit,
    val openExtensionFolder: () -> Unit,
    val checkForUpdates: () -> Unit,
    val downloadUpdate: () -> Unit,
    val launchInstaller: () -> Unit,
    val dismissUpdate: () -> Unit,
    val quit: () -> Unit
)

/** The dark two-tone palette used on the desktop, matching the Android dark theme. */
val DarkPalette: ColorScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF06210F),
    primaryContainer = Color(0xFF1F3D30),
    onPrimaryContainer = Color(0xFFE6FFF2),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF06180C),
    secondaryContainer = Color(0xFF1D4C3B),
    onSecondaryContainer = Color(0xFFEFFFF7),
    background = Color(0xFF12171A),
    onBackground = Color(0xFFE6EDEE),
    surface = Color(0xFF1A2124),
    onSurface = Color(0xFFE6EDEE),
    surfaceVariant = Color(0xFF262F33),
    onSurfaceVariant = Color(0xFFB4C0C2),
    outline = Color(0xFF46545A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

/**
 * Owns application state for the desktop app.
 *
 * Mirrors the Android ViewModel's role: the UI observes one flow and calls
 * actions, and nothing in the UI touches the engine or the disk directly.
 */
class DesktopController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onQuitRequested: () -> Unit = { exitProcess(0) }
) {
    private val store = DesktopStore.load()
    private val settingsState = MutableStateFlow(DesktopSettings.load())
    private val area = DesktopWorkArea { settingsState.value }
    private val tools = YtDlpTools()
    private val ytdlp = YtDlpEngine(tools)

    private val engine = DownloadEngine(
        store = store,
        area = area,
        settingsState = settingsState,
        onChange = ::refresh
    )

    private val torrents = DesktopTorrentEngine(
        store = store,
        area = area,
        settingsState = settingsState,
        onChange = ::refresh,
        scope = scope
    )

    /**
     * Loopback endpoint the browser extension posts links to, so a download started
     * in Chrome, Edge or Firefox lands in the queue instead of the browser's own
     * downloader.
     */
    private val extension = ExtensionInstaller()

    private val capture = CaptureServer(
        token = settingsState.value.captureToken.ifBlank { CaptureServer.newToken() },
        onQueue = ::acceptCapturedLink,
        onMessage = { message -> scope.launch { _messages.value = message } }
    )

    private val _torrentsTab = MutableStateFlow(false)

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val _ui = MutableStateFlow(DesktopUiState())
    val ui: StateFlow<DesktopUiState> = _ui.asStateFlow()

    // --- in-app updates ------------------------------------------------------

    private val updateChecker = DesktopUpdateChecker()
    private val updateInstaller = UpdateInstaller()
    private val _update = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    private val _updateProgress = MutableStateFlow(-1)
    private val _downloadedInstaller = MutableStateFlow<String?>(null)
    init {
        // The bundled binaries and the browser extension are copied out of the app on
        // first run. An installed program directory can be read-only, and yt-dlp has to
        // be a real file on disk for the app to execute it, so this is not optional.
        scope.launch {
            tools.install()
            if (extension.install()) refresh()
        }
        // The pairing token is generated on first run and has to reach disk, or it
        // would change on every launch and silently unpair the extension.
        if (settingsState.value.captureToken.isBlank()) {
            settingsState.value = settingsState.value.copy(captureToken = CaptureServer.newToken())
        }
        DesktopSettings.save(settingsState.value)
        refresh()
        engine.pump()
    }

    fun start() {
        engine.pump()
        // Resume anything the user paused before closing, and pick up torrents.
        torrents.startLoop()
        if (settingsState.value.browserCaptureEnabled) {
            capture.start()
        }
    }

    /** Queues a link handed over by the browser. */
    private fun acceptCapturedLink(request: CaptureRequest) {
        val name = request.fileName?.takeIf { it.isNotBlank() }
            ?: com.downloadhub.core.LinkParser.fileNameFrom(request.url)
        addDownload(
            link = request.url,
            audioOnly = false,
            format = "m4a",
            height = null,
            playlist = false,
            preferredName = name
        )
    }

    val capturePort: Int get() = capture.boundPort
    val captureActive: Boolean get() = capture.isRunning

    init {
        scope.launch {
            _messages.collect { refresh() }
        }
    }


    /**
     * Asks GitHub whether a newer Windows build exists.
     *
     * The Android build does this once a day and installs on its own; here the user
     * asks, because a desktop app is not going to be replaced behind their back by
     * an installer they never saw start.
     */
    fun checkForUpdates() {
        if (_update.value is UpdateCheck.Checking) return
        _update.value = UpdateCheck.Checking
        _updateProgress.value = -1
        scope.launch {
            val release = updateChecker.latest()
            _update.value = when {
                release == null -> UpdateCheck.Failed("Could not reach GitHub")
                // A release with no installer is a release only the Android side can
                // use; say so instead of offering something that cannot be run.
                release.installer() == null ->
                    UpdateCheck.Failed("${release.displayName} has no Windows installer yet")

                !isNewerVersion(release.version, APP_VERSION) -> UpdateCheck.UpToDate(APP_VERSION)
                else -> UpdateCheck.Available(release, release.installer()!!)
            }
            refresh()
        }
    }

    /** Fetches the installer, then offers to run it. */
    fun downloadUpdate() {
        val available = _update.value as? UpdateCheck.Available ?: return
        if (_updateProgress.value >= 0) return
        _updateProgress.value = 0
        scope.launch {
            val result = updateInstaller.download(available.asset.downloadUrl) { percent, _, _ ->
                _updateProgress.value = percent
            }
            _updateProgress.value = -1
            result
                .onSuccess { _downloadedInstaller.value = it.absolutePath }
                .onFailure { _update.value = UpdateCheck.Failed(it.message ?: "The update download failed") }
            refresh()
        }
    }

    /**
     * Starts the downloaded installer.
     *
     * The app stays open: Windows Installer runs separately and the user can carry on
     * using this window, which is better than the window vanishing mid-install.
     */
    fun launchInstaller() {
        val path = _downloadedInstaller.value ?: return
        updateInstaller.launch(File(path))
            .onFailure { _update.value = UpdateCheck.Failed(it.message ?: "Could not start the installer") }
        _downloadedInstaller.value = null
        _update.value = UpdateCheck.Idle
        refresh()
    }

    fun dismissUpdate() {
        _downloadedInstaller.value = null
        _updateProgress.value = -1
        _update.value = UpdateCheck.Idle
        refresh()
    }

    /**
     * Reveals the unpacked browser extension in Explorer.
     *
     * Chrome and Edge will not install an extension without the user picking the
     * folder, so this opens it rather than pretending it can be done for them.
     */
    fun openExtensionFolder() {
        if (!extension.reveal()) {
            _messages.value = "Open this folder in Explorer and load it as an unpacked extension: ${extension.pathForDisplay()}"
        }
        refresh()
    }
    fun setTorrentsTab(enabled: Boolean) {
        _torrentsTab.value = enabled
    }

    fun consumeMessage() {
        _messages.value = null
    }

    fun setBrowserCapture(enabled: Boolean) {
        val updated = settingsState.value.copy(browserCaptureEnabled = enabled)
        settingsState.value = updated
        DesktopSettings.save(updated)
        if (enabled) capture.start() else capture.stop()
        refresh()
    }

    private fun refresh() {
        _ui.value = DesktopUiState(
            items = store.snapshot(),
            settings = settingsState.value,
            busyCount = engine.busy.value,
            ytDlpStatus = tools.statusText(),
            captureActive = capture.isRunning,
            capturePort = capture.boundPort,
            message = _messages.value,
            torrentsTab = _torrentsTab.value,
            update = _update.value,
            updateProgress = _updateProgress.value,
            downloadedInstaller = _downloadedInstaller.value,
            extensionReady = extension.available,
            extensionPath = extension.pathForDisplay(),
            palette = DarkPalette
        )
    }

    val actions: DesktopActions = DesktopActions(
        addDownload = ::addDownload,
        pause = { id ->
            val item = store.get(id)
            if (item?.source == DownloadSource.TORRENT) torrents.pause(id) else engine.pause(id)
        },
        resume = { id ->
            val item = store.get(id)
            if (item?.source == DownloadSource.TORRENT) torrents.resume(id) else engine.resume(id)
        },
        retry = { id ->
            val item = store.get(id)
            if (item?.source == DownloadSource.TORRENT) torrents.resume(id) else engine.retry(id)
        },
        remove = { id ->
            val item = store.get(id)
            if (item?.source == DownloadSource.TORRENT) {
                torrents.remove(id, deleteFiles = true)
                engine.remove(id)
            } else {
                engine.remove(id)
            }
            store.persist()
        },
        pauseAll = { engine.pauseAll() },
        resumeAll = { engine.resumeAll() },
        updateSettings = { updated ->
            settingsState.value = updated
            DesktopSettings.save(updated)
            refresh()
        },
        chooseFolder = ::chooseFolder,
        setBrowserCapture = ::setBrowserCapture,
        consumeMessage = ::consumeMessage,
        setTorrentsTab = ::setTorrentsTab,
        openExtensionFolder = ::openExtensionFolder,
        checkForUpdates = ::checkForUpdates,
        downloadUpdate = ::downloadUpdate,
        launchInstaller = ::launchInstaller,
        dismissUpdate = ::dismissUpdate,
        quit = {
            store.persist()
            onQuitRequested()
        }
    )

    /**
     * Queues a link.
     *
     * A magnet or a .torrent URL goes to the torrent engine, a YouTube link goes
     * to yt-dlp, and anything else is a plain HTTP transfer.
     */
    fun addDownload(
        link: String,
        audioOnly: Boolean,
        format: String,
        height: Int?,
        playlist: Boolean,
        preferredName: String? = null
    ) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return
        val source = com.downloadhub.core.LinkParser.sourceFor(trimmed)
        val id = UUID.randomUUID().toString()
        val name = preferredName?.takeIf { it.isNotBlank() }
            ?.let { com.downloadhub.core.LinkParser.sanitizeFileName(it) }
            ?: com.downloadhub.core.LinkParser.fileNameFrom(trimmed)

        store.add(
            QueuedDownload(
                id = id,
                url = trimmed,
                fileName = name,
                source = source,
                category = com.downloadhub.core.LinkParser.categoryFor(source, name),
                status = DownloadStatus.QUEUED,
                quality = height?.toString(),
                audioFormat = if (audioOnly) format else null,
                playlist = playlist
            )
        )
        store.persist()
        refresh()

        if (source == DownloadSource.YOUTUBE) {
            scope.launch { runYtDlp(id) }
        } else if (source == DownloadSource.TORRENT) {
            // libtorrent picks it up from the loop; nothing to do here.
            torrents.startLoop()
        } else {
            engine.pump()
        }
    }

    private suspend fun runYtDlp(id: String) {
        val item = store.get(id) ?: return
        store.update(id) { it.copy(status = DownloadStatus.RESOLVING) }
        refresh()

        val jobDir = File(AppPaths.workDir, "yt-$id")
        jobDir.deleteRecursively()
        val result = ytdlp.download(
            YtDlpRequest(
                url = item.url,
                audioOnly = item.audioFormat != null,
                audioFormat = item.audioFormat ?: "m4a",
                maxHeight = item.quality?.toIntOrNull(),
                playlist = item.playlist
            ),
            targetDir = jobDir
        ) { percent, _ ->
            scope.launch {
                store.update(id) { current ->
                    // A progress line can still be in flight when the job finishes.
                    // Applying it afterwards would overwrite the final size with a
                    // partial one - a finished 32 MB video showing as 0 bytes.
                    if (current.status != DownloadStatus.RESOLVING) return@update current
                    val total = current.totalBytes
                    current.copy(
                        bytesDownloaded = if (total > 0) (total * percent / 100) else 0L
                    )
                }
                refresh()
            }
        }

        if (result.success && result.producedFiles.isNotEmpty()) {
            val first = result.producedFiles.first()
            // Read the size before publishing. publishFile renames the file, so
            // afterwards first.length() is 0 and the row would show a finished
            // 32 MB video as zero bytes.
            val size = first.length()
            val published = area.publishFile(first, first.name, null)
            result.producedFiles.filter { it != first }.forEach { it.delete() }
            store.update(id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    location = published.location,
                    bytesDownloaded = size,
                    totalBytes = size,
                    fileName = File(published.location).name
                )
            }
        } else {
            store.update(id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = result.message)
            }
        }
        jobDir.deleteRecursively()
        store.persist()
        refresh()
    }

    /** Native folder picker; returns null when the user cancels. */
    fun chooseFolder(): File? = runCatching {
        val chooser = JFileChooser(settingsState.value.downloadDirFile())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }.getOrNull()

    fun close() {
        store.persist()
        capture.stop()
        torrents.close()
        engine.close()
    }
}

private fun exitProcess(code: Int) {
    kotlin.system.exitProcess(code)
}

/**
 * The app version, read from the packaged manifest.
 *
 * Reading it at runtime rather than hardcoding means the window title and the menu
 * bar can never disagree with the build that produced them.
 */
internal val APP_VERSION: String = runCatching {
    DesktopController::class.java.`package`?.implementationVersion ?: "1.0.0"
}.getOrDefault("1.0.0")