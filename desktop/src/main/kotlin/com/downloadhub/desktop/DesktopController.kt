package com.downloadhub.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.LinkParser
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
    /**
     * Bytes fetched so far and in total, so the dialog can say "12 MB of 78 MB"
     * rather than only showing a bar nobody can read a number off.
     */
    val updateBytes: Long = 0L,
    val updateTotalBytes: Long = 0L,
    /**
     * The release the dialog is about, kept even after a failure.
     *
     * So the portable zip stays on offer when the installer download is what failed -
     * which is the situation the zip exists for.
     */
    val updateReleaseName: String? = null,
    /** What was fetched, and what it is. Null until something has downloaded. */
    val downloadedUpdate: DownloadedUpdate? = null,
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
    /**
     * [deleteFile] decides whether the bytes on disk go too. Removing a finished
     * download has two reasonable answers and guessing wrong is destructive, so the
     * caller is made to pick rather than assumed.
     */
    val removeSelectingFiles: (String, Boolean) -> Unit,
    val pauseAll: () -> Unit,
    val resumeAll: () -> Unit,
    val updateSettings: (DesktopSettings) -> Unit,
    val chooseFolder: () -> File?,
    val setBrowserCapture: (Boolean) -> Unit,
    val consumeMessage: () -> Unit,
    val setTorrentsTab: (Boolean) -> Unit,
    val openExtensionFolder: () -> Unit,
    /**
     * Opens the folder a finished download sits in, with the file selected.
     *
     * Takes the item's location rather than doing the work here, because the button
     * is per row and the reporting is shared.
     */
    val revealDownload: (String?) -> Unit,
    /** Reveals the folder finished downloads are published into. */
    val openDownloadFolder: () -> Unit,
    val checkForUpdates: () -> Unit,
    val downloadUpdate: (Boolean) -> Unit,
    val launchInstaller: () -> Unit,
    /** Shows where a downloaded update was saved, to run it by hand. */
    val revealDownloadedInstaller: () -> Unit,
    /**
     * Sets one download's own settings: priority, its own speed cap, when it may start,
     * and how long a finished torrent keeps seeding.
     *
     * Takes the id first, so the dialog knows which row it is for beyond that and a
     * stale dialog cannot write to whatever took its place.
     */
    val setItemOptions: (String, Int, Long, Long, Double, Int) -> Unit,
    /**
     * Unpacks the portable build and starts it, then closes this copy.
     *
     * Separate from the installer because they are not interchangeable: a zip cannot
     * be run as one, and offering it as if it could was the dead end at the end of the
     * portable path.
     */
    val switchToDownloadedVersion: () -> Unit,
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
    private val _updateBytes = MutableStateFlow(0L)
    private val _updateTotalBytes = MutableStateFlow(0L)
    private val _downloadedUpdate = MutableStateFlow<DownloadedUpdate?>(null)

    /**
     * The release the current dialog is about, kept across failures.
     *
     * The dialog used to ask [UpdateCheck.Available] whether a portable zip existed, so
     * the moment a download failed the fallback button disappeared - which is precisely
     * when it is wanted.
     */
    private val _availableRelease = MutableStateFlow<GithubRelease?>(null)
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
        refresh()
        scope.launch {
            val all = updateChecker.releases()
            val release = updateChecker.pickInstallable(all)
            _update.value = when {
                all.isEmpty() -> UpdateCheck.Failed("Could not reach GitHub")
                // Releases exist but none of them carry a Windows build. Worth saying
                // precisely, because "no installer" and "cannot reach GitHub" send the
                // user looking in completely different places.
                release == null -> UpdateCheck.Failed(
                    "None of the published releases has a Windows installer yet"
                )

                !isNewerVersion(release.version, APP_VERSION) -> UpdateCheck.UpToDate(APP_VERSION)
                else -> UpdateCheck.Available(release, release.installer()!!)
            }
            refresh()
        }
    }

    /**
     * Fetches an update asset, then offers to run it.
     *
     * [portable] picks the zip instead of the .msi. Windows Installer fails on some
     * machines in a way nothing in the app can fix, and the zip needs no installer at
     * all, so it is a real alternative rather than a consolation prize.
     */
    fun downloadUpdate(portable: Boolean = false) {
        val available = _update.value as? UpdateCheck.Available ?: return
        val asset = if (portable) available.release.portableZip() ?: available.asset else available.asset
        if (_updateProgress.value >= 0) return
        // Remembered before the transfer starts, so a failure part-way through still
        // leaves the portable zip on offer. Reading it back off the dialog's own state
        // meant a failed download quietly removed the fallback the user needed.
        _availableRelease.value = available.release
        val kind = if (asset.name.endsWith(".zip", ignoreCase = true)) {
            UpdateKind.PORTABLE
        } else {
            UpdateKind.INSTALLER
        }
        _updateProgress.value = 0
        _updateBytes.value = 0L
        _updateTotalBytes.value = asset.size
        refresh()
        scope.launch {
            val result = updateInstaller.download(asset.downloadUrl, { percent, done, total ->
                _updateProgress.value = percent
                _updateBytes.value = done
                // The asset may not declare a size; the transfer itself knows.
                if (total > 0) _updateTotalBytes.value = total
                // The progress bar and the byte count are only on screen if the UI is
                // told they moved. This runs on the download thread, so without it the
                // dialog sat on "Update available" for the whole transfer with nothing
                // moving at all, which is what made the updater look broken.
                refresh()
            }, asset.name)
            _updateProgress.value = -1
            result
                .onSuccess { _downloadedUpdate.value = DownloadedUpdate(kind, it) }
                .onFailure {
                    _update.value = UpdateCheck.Failed(
                        it.message ?: "The update download failed"
                    )
                }
            refresh()
        }
    }

    /**
     * Starts the downloaded installer.
     *
     * The app stays open: Windows Installer runs separately and the user can carry on
     * using this window, which is better than the window vanishing mid-install.
     *
     * The previous version set the failure and then immediately reset the state to
     * Idle, so a download that could not be started reported nothing at all and the
     * dialog simply closed. The state is only cleared once the installer is really
     * away.
     */
    fun launchInstaller() {
        val downloaded = _downloadedUpdate.value ?: return
        if (!downloaded.isInstaller) {
            // Refusing here rather than trying: a zip is not an installer, and this is
            // the path that used to hand one to the shell and appear to do nothing.
            _update.value = UpdateCheck.Failed(
                "That is the portable zip, not an installer. Use \"Switch to this version\" " +
                    "to unpack it and start the new version."
            )
            refresh()
            return
        }
        val started = updateInstaller.launch(downloaded.file)
        if (started.isFailure) {
            // Keep the dialog open, keep the file, and say what went wrong. A security
            // agent blocking the installer is the common case and the message is the
            // only clue the user gets.
            _update.value = UpdateCheck.Failed(
                started.exceptionOrNull()?.message
                    ?: "Could not start the installer. If your security software is blocking it, " +
                    "download the portable zip instead - it needs no installer."
            )
            refresh()
            return
        }
        _downloadedUpdate.value = null
        _update.value = UpdateCheck.Idle
        refresh()
    }

    /**
     * Unpacks the portable zip and starts that version.
     *
     * This is the whole portable path, and it used to not exist: the zip downloaded
     * fine and then the only thing offered was "Install now", which handed a .zip to
     * the shell. On a machine where Windows Installer is blocked - which is the only
     * reason the zip is offered in the first place - that was a dead end at the last
     * step.
     *
     * The new version is unpacked into its own folder and started, and this copy then
     * closes. The old install is left alone, so if the new one will not start the
     * previous version is still there to go back to.
     */
    fun switchToDownloadedVersion() {
        val downloaded = _downloadedUpdate.value ?: return
        if (downloaded.isInstaller) {
            launchInstaller()
            return
        }
        if (_updateProgress.value >= 0) return
        // Back to the release's own state, which the dialog needs in order to show its
        // notes and the "switching" wording. Reaching here after a failed installer
        // download, the state would still be Failed.
        val release = _availableRelease.value ?: return
        val zip = release.portableZip() ?: return
        _update.value = UpdateCheck.Available(release, zip)
        _updateProgress.value = 0
        _updateBytes.value = 0L
        _updateTotalBytes.value = downloaded.file.length()
        refresh()
        scope.launch {
            val result = runCatching {
                val launcher = PortableBuild.extract(downloaded.file, AppPaths.updateNextDir).getOrThrow()
                ProcessBuilder(launcher.absolutePath).start()
                launcher
            }
            _updateProgress.value = -1
            result
                .onSuccess {
                    _messages.value = "Starting ${it.name}. This window will close."
                    refresh()
                    // Only leave once the new copy is genuinely running.
                    onQuitRequested()
                }
                .onFailure {
                    _update.value = UpdateCheck.Failed(
                        "Could not start the new version: ${it.message}. " +
                            "The previous version is untouched."
                    )
                    refresh()
                }
        }
    }

    /**
     * Sets one download's own settings.
     *
     * Per-item controls from AB Download Manager and qBittorrent, in one action so the
     * dialog does not have to know how they are stored or what they interact with.
     * Every argument is nullable-by-convention only in the sense that zero means "off":
     * [speedLimit] of 0 means "use the app-wide limit", not "download at full speed".
     */
    fun setItemOptions(
        id: String,
        priorityRank: Int,
        speedLimitBytesPerSecond: Long,
        startAfterEpochMillis: Long,
        shareRatioLimit: Double,
        seedTimeLimitMinutes: Int
    ) {
        if (store.get(id) == null) return
        store.update(id) {
            it.copy(
                priorityRank = priorityRank.coerceIn(0, 4),
                speedLimitBytesPerSecond = speedLimitBytesPerSecond.coerceAtLeast(0L),
                startAfterEpochMillis = startAfterEpochMillis.coerceAtLeast(0L),
                shareRatioLimit = shareRatioLimit.coerceAtLeast(0.0),
                seedTimeLimitMinutes = seedTimeLimitMinutes.coerceAtLeast(0)
            )
        }
        store.persist()
        refresh()

        // A download whose time has just arrived, or that has been raised to the top of
        // the queue, should not sit idle until the next user action: the queue loop
        // notices on its own, but only once it next runs, and only if something is free.
        engine.pump()
        torrents.startLoop()
    }

    /** Shows where the download was saved, for running it by hand. */
    fun revealDownloadedInstaller() {
        val downloaded = _downloadedUpdate.value
        if (downloaded == null) {
            _messages.value = "Nothing has been downloaded yet."
        } else if (!revealInFolder(downloaded.file.absolutePath)) {
            _messages.value = "The file is here: ${downloaded.file.absolutePath}"
        }
        refresh()
    }

    fun dismissUpdate() {
        // The staged file is kept on purpose: closing the dialog should not throw away
        // a 78 MB download the user may want to come back to. It is replaced by the
        // next download of the same name.
        _downloadedUpdate.value = null
        _updateProgress.value = -1
        _updateBytes.value = 0L
        _availableRelease.value = null
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

    /**
     * Opens the download folder in Explorer.
     *
     * The folder is created if it is missing: a button that does nothing the first
     * time it is pressed, because nothing has been downloaded yet, reads as broken.
     */
    /**
     * Shows a finished download in Explorer.
     *
     * Reports what went wrong when it cannot, because a button that silently does
     * nothing is indistinguishable from a broken app - which is exactly what the
     * previous version of this was.
     */
    fun revealDownload(location: String?) {
        if (location.isNullOrBlank()) {
            _messages.value = "That download has no file yet."
            refresh()
            return
        }
        if (!revealInFolder(location)) {
            val folder = File(location).parentFile
            _messages.value = if (folder != null && folder.isDirectory) {
                "Could not open Explorer. The file is here: ${folder.absolutePath}"
            } else {
                "That file is no longer where it was: $location"
            }
        }
        refresh()
    }

    fun openDownloadFolder() {
        val dir = settingsState.value.downloadDirFile()
        if (!dir.isDirectory && !dir.mkdirs()) {
            _messages.value = "Could not create the download folder: ${dir.absolutePath}"
            refresh()
            return
        }
        if (!revealFolder(dir)) {
            _messages.value = "Open this folder yourself: ${dir.absolutePath}"
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
            updateBytes = _updateBytes.value,
            updateTotalBytes = _updateTotalBytes.value,
            downloadedUpdate = _downloadedUpdate.value,
            updateReleaseName = _availableRelease.value?.displayName,
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
        removeSelectingFiles = { id, deleteFile ->
            val item = store.get(id)
            if (item?.source == DownloadSource.TORRENT) {
                // A torrent's payload is a whole directory, so "just the list" has to
                // stop it first either way; only the files are conditional.
                torrents.remove(id, deleteFiles = deleteFile)
            }
            // Dropping the row is the store's business, and it is the only place that
            // touches the file, so the choice is honoured in one spot.
            store.remove(id, deleteFiles = deleteFile)
            store.persist()
            refresh()
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
        openDownloadFolder = ::openDownloadFolder,
        revealDownload = ::revealDownload,
        checkForUpdates = ::checkForUpdates,
        downloadUpdate = { portable -> downloadUpdate(portable) },
        revealDownloadedInstaller = ::revealDownloadedInstaller,
        setItemOptions = ::setItemOptions,
        switchToDownloadedVersion = ::switchToDownloadedVersion,
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
        preferredName: String? = null,
        /**
         * Set when the link is a path to a .torrent file on this machine.
         *
         * A torrent is read from disk rather than fetched, so the engine has to be told
         * which of the two it is. Nothing set this before, which is why a .torrent
         * picked from the machine could sit in the list for ever without starting.
         */
        torrentFile: File? = null
    ) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return
        // A local .torrent given as a bare path, with no explicit hint, still counts.
        val localTorrent = torrentFile?.takeIf { it.isFile }
            ?: trimmed.takeIf { LinkParser.sourceFor(it) == DownloadSource.TORRENT }
                ?.let { File(it) }
                ?.takeIf { it.isFile }
        val source = LinkParser.sourceFor(trimmed)
        val id = UUID.randomUUID().toString()
        val name = preferredName?.takeIf { it.isNotBlank() }
            ?.let { LinkParser.sanitizeFileName(it) }
            ?: localTorrent?.name
            ?: LinkParser.fileNameFrom(trimmed)

        store.add(
            QueuedDownload(
                id = id,
                url = trimmed,
                fileName = name,
                source = source,
                category = LinkParser.categoryFor(source, name),
                status = DownloadStatus.QUEUED,
                quality = height?.toString(),
                audioFormat = if (audioOnly) format else null,
                playlist = playlist,
                torrentFilePath = localTorrent?.absolutePath
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

    /**
     * Queues what Windows asked this copy to open.
     *
     * Used both at startup and for the arguments a second copy hands over, so a magnet
     * link or a .torrent opened while the app was already running behaves exactly like
     * one opened while it was closed.
     *
     * Duplicates are dropped rather than queued. The same torrent arrives from a browser,
     * a double-click and an extension in the space of a few seconds, and three copies of
     * one torrent means three times the seeding - which is exactly what happened: the
     * queue had three identical entries of one file.
     */
    fun queueTargets(targets: List<QueueTarget>) {
        if (targets.isEmpty()) return
        var added = 0
        var skipped = 0
        targets.forEach { target ->
            val candidate = DownloadItem(
                id = "candidate",
                url = target.link,
                fileName = target.name.orEmpty(),
                source = com.downloadhub.core.LinkParser.sourceFor(target.link),
                torrentFilePath = target.torrentFile
            )
            if (com.downloadhub.core.DuplicateRules.isDuplicate(store.snapshot().map { it.toCoreItem() }, candidate)) {
                skipped++
                return@forEach
            }
            addDownload(
                link = target.link,
                audioOnly = false,
                format = "m4a",
                height = null,
                playlist = false,
                preferredName = target.name,
                torrentFile = target.torrentFile?.let(::File)
            )
            added++
        }
        _messages.value = when {
            added == 0 -> "That is already in the list."
            skipped == 0 -> if (added == 1) "Added 1 download from the link you opened."
            else "Added $added downloads from the links you opened."

            else -> "Added $added; ${if (skipped == 1) "1 was already" else "$skipped were already"} in the list."
        }
        refresh()
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
            val published = area.publishFile(first, first.name, null, item.category)
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
 * Reading it at runtime rather than hardcoding means the window title, the menu bar
 * and the update check can never disagree with the build that produced them.
 *
 * The build writes `Implementation-Version` into the jar manifest; see the `jar` task
 * in desktop/build.gradle.kts. When it is missing - running from the IDE, or a build
 * that skipped it - this falls back to the version recorded at startup, so the app
 * still says something plausible instead of a bare 1.0.0.
 */
internal val APP_VERSION: String = runCatching {
    DesktopController::class.java.`package`?.implementationVersion
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
