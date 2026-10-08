package com.downloadhub.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.downloadhub.core.FilePriority
import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.LinkParser
import com.downloadhub.core.TorrentSelection
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** The articles each RSS feed last returned, by feed URL. */
    val rssItems: Map<String, List<com.downloadhub.core.RssItem>> = emptyMap(),
    /** Whether a feed refresh is running, for the panel's spinner. */
    val rssRefreshing: Boolean = false
) {
    /**
     * The Material scheme, read from the theme in force.
     *
     * A body property rather than a constructor field, because a field would capture the
     * scheme when the state was built and hold it: the window would change theme and this
     * would not, so a dialog opened afterwards would be a different colour from the window
     * it was opened over.
     */
    val palette: ColorScheme get() = AppTheme.schemeFor(AppTheme.Palette.colors)
}

/** Callbacks the UI is allowed to invoke. */
data class DesktopActions(
    val addDownload: (String, Boolean, String, Int?, Boolean) -> Unit,
    /**
     * Queues whatever the pre-download dialog was opened for.
     *
     * Separate from [addDownload] because it carries the file selection, save directory
     * and share limits the dialog collected, and none of that fits the link-shaped
     * signature the plain dialog used.
     */
    val addPrepared: (com.downloadhub.core.TorrentAddRequest) -> Unit,
    /**
     * Queues a video at the streams the quality chooser named.
     *
     * Separate from `addPrepared` rather than an extra argument on it, because the two
     * carry different things: that one is a torrent's file selection and save path, this
     * one is two yt-dlp format ids. Bolting the ids onto it would have meant a request type
     * that is mostly nulls depending on which kind of download it is.
     */
    val addChosenVideo: (String, com.downloadhub.core.StreamChoice, Boolean, String?) -> Unit,
    /**
     * Asks yt-dlp what a link offers.
     *
     * Runs a real process for a few seconds, so it goes on the controller's scope and hands
     * the answer back rather than being awaited by the dialog - a dialog that suspended a
     * lookup would have to be careful about being closed mid-lookup, and this way a closed
     * dialog simply stops listening.
     */
    val listVideoFormats: (String, (YtDlpEngine.FormatListing) -> Unit) -> Unit,
    /**
     * Lists what a pasted link holds: a playlist, album, channel, or one video.
     *
     * Same shape as [listVideoFormats] for the same reason: a real process takes
     * seconds, and a panel that suspended it would have to be careful about being
     * closed mid-lookup.
     */
    val fetchYouTubeListing: (String, (YtDlpEngine.PlaylistFetch) -> Unit) -> Unit,
    /**
     * Queues several videos at one quality, skipping what is already queued.
     *
     * One call rather than one per row, because the dedup has to see the whole
     * batch: two rows naming the same video - overlapping playlists do that -
     * must queue once. Returns how many were actually queued.
     */
    val queueYouTubeEntries: (
        List<com.downloadhub.core.YouTubeEntry>, Boolean, Int?, String
    ) -> Int,
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    /**
     * Stops one download, keeping what it has already fetched.
     *
     * Not the same as [pause]. Pausing asks libtorrent to stop sending and to remember
     * exactly where it was, so resuming carries on from the same piece. Stopping takes
     * the torrent out of the session altogether: nothing is holding it, nothing is
     * uploading, and resuming starts it afresh. That is what someone wants when a
     * download is going wrong and they would rather it let go than keep limping.
     *
     * Its own action rather than a second name for [pause] because the toolbar has both
     * - Pause for what might be temporary, Stop for what is finished with.
     */
    val stop: (String) -> Unit,
    /**
     * Sets one file of one torrent's priority, and tells libtorrent at once.
     *
     * Takes the file index and the priority rather than a whole map, because the only
     * caller is a row's own control and building a map to change one entry is how a
     * per-file control turns into a per-torrent one.
     */
    val setFilePriority: (String, Int, FilePriority) -> Unit,
    /** Applies one priority to every file, which is the toolbar's "Set priority". */
    val setAllFilePriorities: (String, FilePriority) -> Unit,
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
    /**
     * Browses for the temporary-file cache.
     *
     * Separate from [chooseFolder] even though both open the same dialog: the two are
     * different folders, and starting the picker in the wrong one is a small way to make
     * a cache setting feel broken.
     */
    val chooseCacheFolder: () -> File?,
    /**
     * Fetches a magnet's file list so the pre-download dialog can show it.
     *
     * Takes a magnet and a completion rather than returning a value: it waits on the
     * swarm for up to twenty seconds, which is far too long to hold up a call on the UI
     * thread, and the dialog is already on screen saying what it is waiting for.
     *
     * The completion is called with null when nothing arrives, which is not a failure -
     * a magnet with nobody on it is a magnet with no file list, and the dialog still
     * offers a folder, a name and a stop condition.
     */
    val readMagnetMetadata: (String, (com.downloadhub.core.TorrentMetainfo?) -> Unit) -> Unit,
    /** Puts text on the clipboard, for "Copy magnet link". */
    val copyToClipboard: (String) -> Unit,
    /** Saves a torrent's .torrent somewhere the user picks. */
    val exportTorrent: (String) -> Unit,
    /** Renames a finished download, on disk and in the list. */
    val renameDownload: (String, String) -> Unit,
    /** Points an unfinished download at a different folder. */
    val setDownloadLocation: (String, String) -> Unit,
    val setBrowserCapture: (Boolean) -> Unit,
    val consumeMessage: () -> Unit,
    val setTorrentsTab: (Boolean) -> Unit,
    val openExtensionFolder: () -> Unit,
    /** Opens a browser where the extension is added, with the extension ready for it. */
    val addExtensionTo: (InstalledBrowser) -> Unit = {},
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
    /** Adds a queue, or replaces the one with the same id. */
    val saveQueue: (QueueConfig) -> Unit = {},
    /** Removes a queue; its downloads go back to Main. Main itself cannot be removed. */
    val deleteQueue: (String) -> Unit = {},
    /** A queue's Start / Stop button. Stopping puts what it was running back in line. */
    val setQueueStarted: (String, Boolean) -> Unit = { _, _ -> },
    val moveToQueue: (Collection<String>, String) -> Unit = { _, _ -> },
    /** Replaces what one download sends with its requests; applies from its next request. */
    val setItemRequest: (String, com.downloadhub.core.HttpRequestOptions) -> Unit = { _, _ -> },
    // qBittorrent's per-torrent extras. The two readers block briefly on the engine, so
    // the pane calls them off the UI thread.
    val torrentTrackers: (String) -> List<com.downloadhub.core.TrackerRow> = { emptyList() },
    val torrentPeers: (String) -> List<com.downloadhub.core.PeerRow> = { emptyList() },
    val addTrackers: (String, List<String>) -> Unit = { _, _ -> },
    val forceRecheck: (String) -> Unit = {},
    val forceReannounce: (String) -> Unit = {},
    // RSS feeds and their auto-download rules.
    val addRssFeed: (String) -> Unit = {},
    val removeRssFeed: (String) -> Unit = {},
    val refreshRss: () -> Unit = {},
    val saveRssRules: (List<RssRuleConfig>) -> Unit = {},
    val downloadRssItem: (com.downloadhub.core.RssItem) -> Unit = {},
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

    // Declared before the init blocks below, which already call refresh(). The collector
    // starts lazily on first use for the same reason: the fields publishState() reads are
    // not all assigned yet at this point.
    private val refreshRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val refreshLoop = lazy {
        scope.launch {
            for (request in refreshRequests) {
                publishState()
                // At most this often, whatever the number of downloads reporting.
                delay(UI_REFRESH_MILLIS)
            }
        }
    }

    /**
     * Set by the window, and the one way anything reaches the queue.
     *
     * Takes a link, which may be a magnet, an http URL, or a path to a `.torrent` on
     * disk, and means "show this in the pre-download dialog".
     *
     * A callback rather than state, because the dialog lives in the window and the
     * controller has no window.
     *
     * It used to be `onTorrentNeedsReview` and it took a `File`, so it could only carry a
     * `.torrent` from disk. A magnet has no file, so a magnet from the browser could not
     * use it and went straight into the queue: the download appeared with no dialog, no
     * folder chosen, and no chance to say no. Every route in - a paste, a drop, Explorer,
     * a second copy, the browser extension - now comes through here.
     *
     * Null until the window has composed. Everything queued before then falls back to
     * adding directly, so the worst case is the old behaviour rather than a lost
     * download.
     */
    @Volatile
    var onDownloadNeedsReview: ((String) -> Unit)? = null

    /**
     * The current settings.
     *
     * Public because the first-run screen has to decide whether to show itself before the
     * UI state has been built, and `ui.value.settings` is only available once that has
     * happened.
     */
    val settings: StateFlow<DesktopSettings> get() = settingsState

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
            // Adopt the configured cache folder at startup as well as on save, or a folder
            // set in a previous session is ignored until the user changes it again.
            runCatching {
                val folder = settingsState.value.cacheDirFile()
                if (folder.isDirectory || folder.mkdirs()) AppPaths.cacheDirectory = folder
            }
            // Unpacks yt-dlp and ffmpeg. Both, and independently: one being present says
            // nothing about the other, and ffmpeg is what makes a chosen quality a playable
            // file rather than two loose ones.
            tools.install()
            if (extension.install()) refresh()
            // The extractor rots: YouTube changes its pages and a yt-dlp from build day
            // starts calling public videos "not available". Checked in the background
            // and at most daily, so startup never waits on it; a replacement refreshes
            // the status line so the new version is visible.
            tools.updateCheckInBackground { updated -> if (updated) refresh() }
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
        // Downloads that were moving when the app closed or updated come back queued (see
        // DesktopStore.load) and carry on by themselves: the HTTP engine and the torrent
        // loop start queued rows, and YouTube ones go back to yt-dlp here.
        engine.pump()
        torrents.startLoop()
        store.snapshot()
            .filter { it.source == DownloadSource.YOUTUBE && it.status == DownloadStatus.QUEUED }
            .forEach { item -> scope.launch(Dispatchers.IO) { runYtDlp(item.id) } }
        if (settingsState.value.browserCaptureEnabled) {
            capture.start()
        }
        runQueueScheduler()
        watchForTorrents()
        runRssLoop()
        reportLastUpdate()
        // A quiet check a little after start: only an available update surfaces a dialog,
        // so nobody is shown "you are up to date" or a network error they did not ask for.
        scope.launch {
            delay(STARTUP_UPDATE_CHECK_DELAY_MILLIS)
            if (_update.value !is UpdateCheck.Idle) return@launch
            val release = runCatching { updateChecker.pickInstallable(updateChecker.releases()) }.getOrNull()
            if (release != null && isNewerVersion(release.version, APP_VERSION) && _update.value is UpdateCheck.Idle) {
                _update.value = UpdateCheck.Available(release, release.installer()!!)
                refresh()
            }
        }
    }

    /**
     * qBittorrent's watched folder: every few seconds, each .torrent dropped into it is
     * added with the default folder and started, then renamed to `.torrent.added` so it
     * is never added twice - the same convention qBittorrent uses.
     */
    private fun watchForTorrents() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCH_FOLDER_TICK_MILLIS)
                val settings = settingsState.value
                if (!settings.watchFolderEnabled || settings.watchFolder.isBlank()) continue
                val found = File(settings.watchFolder).listFiles { file ->
                    file.isFile && file.name.endsWith(".torrent", ignoreCase = true)
                }.orEmpty()
                found.forEach { file ->
                    val meta = runCatching { com.downloadhub.core.TorrentParser.parse(file) }.getOrNull()
                    // Renamed whether or not it parsed: a broken file would otherwise be
                    // retried, and complained about, every few seconds for ever.
                    val done = File(file.parentFile, file.name + if (meta == null) ".invalid" else ".added")
                    if (meta != null) {
                        addPrepared(
                            com.downloadhub.core.TorrentAddRequest(
                                metainfo = meta,
                                metainfoFile = file,
                                saveDirectory = settings.downloadDirFile(),
                                startImmediately = true,
                                link = file.absolutePath
                            )
                        )
                        _messages.value = "Added ${meta.name.ifBlank { file.name }} from the watched folder."
                    }
                    runCatching { file.renameTo(done) }
                }
            }
        }
    }

    // --- RSS ------------------------------------------------------------------------

    private val _rssItems = MutableStateFlow<Map<String, List<com.downloadhub.core.RssItem>>>(emptyMap())
    private val _rssRefreshing = MutableStateFlow(false)
    private val rssSeenFile: File get() = File(AppPaths.home, "rss-seen.txt")

    /** "feedUrl|guid" of every article already acted on. Loaded once, appended as it grows. */
    private val rssSeen: MutableSet<String> by lazy {
        java.util.Collections.synchronizedSet(
            runCatching { rssSeenFile.readLines().filter { it.isNotBlank() }.toMutableSet() }.getOrDefault(mutableSetOf())
        )
    }

    private fun runRssLoop() {
        scope.launch(Dispatchers.IO) {
            delay(RSS_FIRST_REFRESH_DELAY_MILLIS)
            while (isActive) {
                refreshRss()
                delay(settingsState.value.rssRefreshMinutes.coerceAtLeast(5) * 60_000L)
            }
        }
    }

    /**
     * Re-reads every feed and runs the auto-download rules over what is new.
     *
     * On a feed's first read everything in it is only marked seen: qBittorrent would
     * otherwise download every back episode a new rule happens to match.
     */
    fun refreshRss() {
        if (_rssRefreshing.value) return
        _rssRefreshing.value = true
        refresh()
        scope.launch(Dispatchers.IO) {
            try {
                val settings = settingsState.value
                val rules = settings.rssRules.map { it.toRule() }
                val newlySeen = mutableListOf<String>()
                val fetched = settings.rssFeeds.associate { feed ->
                    val items = runCatching { com.downloadhub.core.RssParser.parse(fetchFeed(feed.url, settings)) }
                        .getOrElse {
                            _messages.value = "Could not read the feed ${feed.name.ifBlank { feed.url }}: ${it.message ?: it::class.simpleName}"
                            _rssItems.value[feed.url].orEmpty()
                        }
                    val firstRead = rssSeen.none { it.startsWith(feed.url + "|") }
                    items.forEach { item ->
                        val key = feed.url + "|" + item.guid
                        if (rssSeen.add(key)) {
                            newlySeen += key
                            val rule = if (firstRead) null else rules.firstOrNull { it.matches(item.title) }
                            if (rule != null) {
                                downloadRssItem(item)
                                _messages.value = "RSS rule \"${rule.name}\" added ${item.title}."
                            }
                        }
                    }
                    feed.url to items
                }
                if (newlySeen.isNotEmpty()) runCatching { rssSeenFile.appendText(newlySeen.joinToString("\n", postfix = "\n")) }
                _rssItems.value = fetched
            } finally {
                _rssRefreshing.value = false
                refresh()
            }
        }
    }

    private fun fetchFeed(url: String, settings: DesktopSettings): String {
        val target = java.net.URL(url)
        val connection = (settings.proxySetting().toProxy()?.let { target.openConnection(it) } ?: target.openConnection())
            as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "1-download-manager/$APP_VERSION")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    /** Queues an article's link through the same path as a pasted one. */
    fun downloadRssItem(item: com.downloadhub.core.RssItem) {
        addDownload(
            link = item.link,
            audioOnly = false,
            format = "m4a",
            height = null,
            playlist = false,
            preferredName = item.title
        )
    }

    fun addRssFeed(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) {
            _messages.value = "That does not look like a feed address."
            return
        }
        val settings = settingsState.value
        if (settings.rssFeeds.any { it.url == trimmed }) return
        saveSettings(settings.copy(rssFeeds = settings.rssFeeds + RssFeedConfig(trimmed, java.net.URL(trimmed).host)))
        refreshRss()
    }

    fun removeRssFeed(url: String) {
        val settings = settingsState.value
        saveSettings(settings.copy(rssFeeds = settings.rssFeeds.filter { it.url != url }))
        _rssItems.value = _rssItems.value - url
        refresh()
    }

    fun saveRssRules(rules: List<RssRuleConfig>) {
        saveSettings(settingsState.value.copy(rssRules = rules))
    }

    /** Says how the silent install that ran before this start went, once. */
    private fun reportLastUpdate() {
        val result = updateInstaller.takeLastResult() ?: return
        _messages.value = if (result.succeeded) {
            "Updated to version $APP_VERSION."
        } else {
            "The update did not install (Windows Installer code ${result.exitCode}). " +
                "Details are in ${result.logPath}."
        }
    }

    // --- queues ---------------------------------------------------------------

    /** Presses each queue's Start or Stop when its scheduled time has passed. */
    private fun runQueueScheduler() {
        scope.launch {
            var last = java.time.LocalDateTime.now()
            while (isActive) {
                delay(QUEUE_SCHEDULER_TICK_MILLIS)
                val now = java.time.LocalDateTime.now()
                settingsState.value.queuesOrDefault.forEach { queue ->
                    when (com.downloadhub.core.QueueRules.switchBetween(queue.schedule, last, now)) {
                        com.downloadhub.core.QueueSwitch.START -> setQueueStarted(queue.id, true)
                        com.downloadhub.core.QueueSwitch.STOP -> setQueueStarted(queue.id, false)
                        null -> Unit
                    }
                }
                last = now
            }
        }
    }

    private fun saveSettings(updated: DesktopSettings) {
        settingsState.value = updated
        DesktopSettings.save(updated)
        refresh()
    }

    private fun kickEngines() {
        engine.pump()
        torrents.startLoop()
    }

    fun saveQueue(queue: QueueConfig) {
        val settings = settingsState.value
        val list = settings.queuesOrDefault
        val cleaned = queue.copy(
            name = queue.name.trim().ifBlank { "Queue" },
            maxConcurrent = queue.maxConcurrent.coerceIn(0, 16)
        )
        val updated = if (list.any { it.id == cleaned.id }) {
            list.map { if (it.id == cleaned.id) cleaned else it }
        } else {
            list + cleaned
        }
        saveSettings(settings.copy(queues = updated))
        if (cleaned.started) kickEngines()
    }

    fun deleteQueue(id: String) {
        if (id == com.downloadhub.core.QueueRules.MAIN) return
        moveToQueue(store.snapshot().filter { it.queueId == id }.map { it.id }, com.downloadhub.core.QueueRules.MAIN)
        val settings = settingsState.value
        saveSettings(settings.copy(queues = settings.queuesOrDefault.filter { it.id != id }))
    }

    fun setQueueStarted(id: String, started: Boolean) {
        val settings = settingsState.value
        val queue = settings.queue(id)
        if (queue.id != id) return
        saveSettings(settings.copy(queues = settings.queuesOrDefault.map { if (it.id == id) it.copy(started = started) else it }))
        if (started) {
            kickEngines()
            return
        }
        // Stopping a queue stops what it is running and puts it back in line, rather than
        // pausing it: a paused download would not come back when the queue next starts.
        store.snapshot()
            .filter { it.queueId == id && (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.RESOLVING) }
            .forEach { item ->
                if (item.source == DownloadSource.TORRENT) torrents.pause(item.id) else engine.pause(item.id)
                store.update(item.id) { it.copy(status = DownloadStatus.QUEUED, speedBytesPerSecond = 0L) }
            }
        store.persist()
        refresh()
    }

    fun moveToQueue(ids: Collection<String>, queueId: String) {
        if (ids.isEmpty()) return
        val target = settingsState.value.queue(queueId).id
        ids.forEach { id -> store.update(id) { it.copy(queueId = target) } }
        store.persist()
        refresh()
        kickEngines()
    }

    /**
     * A link handed over by the browser extension.
     *
     * Routed to the pre-download dialog like everything else. It used to be queued
     * directly, which is how clicking a magnet in the browser produced a row with no
     * dialog: no folder chosen, no chance to see what it was, and no way to refuse it
     * once it had started.
     */
    /**
     * The page each captured link was clicked on. Sent as the Referer when the link is
     * queued, because a site that checks where a download came from refuses one without it.
     */
    private val capturedReferers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The browser's cookies for each captured link, sent so a signed-in download works. */
    private val capturedCookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun withCapturedReferer(link: String, headers: Map<String, String>): Map<String, String> {
        var result = headers
        capturedReferers.remove(link)?.let { referer ->
            if (result.keys.none { it.equals("Referer", ignoreCase = true) }) result = result + ("Referer" to referer)
        }
        capturedCookies.remove(link)?.let { cookies ->
            if (result.keys.none { it.equals("Cookie", ignoreCase = true) }) result = result + ("Cookie" to cookies)
        }
        return result
    }

    private fun acceptCapturedLink(request: CaptureRequest) {
        request.referer?.takeIf { it.isNotBlank() }?.let { capturedReferers[request.url] = it }
        request.cookies?.takeIf { it.isNotBlank() }?.let { capturedCookies[request.url] = it }
        val review = onDownloadNeedsReview
        // Everything the extension sends is shown in the pre-download window first. Only a
        // quality already picked by an older extension build counts as the answer.
        if (review != null && !request.chosen) {
            review(request.url)
            return
        }
        // Straight into the queue: the setting asks for it, or the window is not up yet
        // and a link that arrives in the first moments must not be lost.
        val name = request.fileName?.takeIf { it.isNotBlank() }
            ?: com.downloadhub.core.LinkParser.fileNameFrom(request.url)
        addDownload(
            link = request.url,
            audioOnly = request.audioOnly,
            format = "m4a",
            height = request.height,
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
            // Only the changed files when this install can take them; the full
            // installer otherwise, exactly as before.
            if (!portable) {
                val delta = tryDelta(available.release)
                if (delta != null) {
                    _updateProgress.value = -1
                    _downloadedUpdate.value = delta
                    refresh()
                    return@launch
                }
                _updateProgress.value = 0
                _updateBytes.value = 0L
                _updateTotalBytes.value = asset.size
            }
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
    /** What a downloaded delta needs at install time: its file list, the changed files, the install. */
    private class PendingDelta(val manifest: UpdateManifest, val changed: List<UpdateFile>, val root: File)

    private var pendingDelta: PendingDelta? = null

    /**
     * Fetches only the files that changed, or null to take the full installer.
     *
     * Null whenever anything is not exactly as expected: not an installed copy, no file
     * list on the release, an install that differs in a file the delta does not carry
     * (a version further back, or a modified install), or any download or hash failure.
     */
    private suspend fun tryDelta(release: GithubRelease): DownloadedUpdate? = runCatching {
        val root = UpdateInstaller.installedLauncher()?.parentFile ?: return null
        val listAsset = DeltaUpdate.manifestAsset(release) ?: return null
        val listFile = updateInstaller.download(listAsset.downloadUrl, { _, _, _ -> }, listAsset.name).getOrThrow()
        val manifest = DeltaUpdate.parse(listFile.readText())
        val changed = withContext(Dispatchers.IO) { DeltaUpdate.changedFiles(manifest, root) }
        if (!DeltaUpdate.covers(manifest, changed)) return null
        val zipAsset = release.assets.firstOrNull { it.name == manifest.delta?.asset } ?: return null
        _updateTotalBytes.value = zipAsset.size
        val zip = updateInstaller.download(zipAsset.downloadUrl, { percent, done, total ->
            _updateProgress.value = percent
            _updateBytes.value = done
            if (total > 0) _updateTotalBytes.value = total
            refresh()
        }, zipAsset.name).getOrThrow()
        val staging = File(AppPaths.updateDir, "delta-${manifest.version}")
        withContext(Dispatchers.IO) { DeltaUpdate.stage(zip, changed, staging) }
        pendingDelta = PendingDelta(manifest, changed, root)
        DownloadedUpdate(UpdateKind.DELTA, staging)
    }.getOrNull()

    fun launchInstaller() {
        val downloaded = _downloadedUpdate.value ?: return
        val delta = pendingDelta
        if (downloaded.kind == UpdateKind.DELTA && delta != null) {
            val started = DeltaUpdate.launchApply(
                staging = downloaded.file,
                files = delta.changed.map { it.path },
                stale = DeltaUpdate.staleJars(delta.manifest, delta.root),
                root = delta.root,
                relaunch = UpdateInstaller.installedLauncher(),
                resultFile = updateInstaller.resultFile,
                log = File(AppPaths.updateDir, "delta.log")
            )
            if (started.isFailure) {
                _update.value = UpdateCheck.Failed(started.exceptionOrNull()?.message ?: "Could not apply the update")
                refresh()
                return
            }
            store.persist()
            capture.stop()
            onQuitRequested()
            return
        }
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
        val started = updateInstaller.launchSilently(downloaded.file, UpdateInstaller.installedLauncher())
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
        // The script installs once this process has gone, then starts the new version.
        store.persist()
        capture.stop()
        onQuitRequested()
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

    /** Opens [browser] on its extension install, then says the one thing left to do there. */
    fun addExtensionTo(browser: InstalledBrowser) {
        val opened = BrowserInstall.open(browser) { build -> extension.folder(build) }
        _messages.value = when {
            !opened -> "Could not open ${browser.name}. The extension is in ${extension.pathForDisplay()}"
            browser.family == BrowserFamily.CHROMIUM ->
                "In ${browser.name}: turn on Developer mode, then drag the opened 'chromium' folder onto the page. It connects by itself."
            else -> "${browser.name} is asking to add the extension. Accept it and it connects by itself."
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

    /**
     * Asks for the UI to be rebuilt. Coalesced: every engine calls this on every progress
     * tick, and rebuilding the whole state - a copy of the queue plus several file checks -
     * for each one is what made the window stutter with a few downloads running.
     */
    private fun refresh() {
        refreshRequests.trySend(Unit)
        if (initialised) refreshLoop.value
    }

    /** Set at the end of construction; until then requests just wait in the channel. */
    @Volatile
    private var initialised = false

    /** Tool and extension status touch the disk; a few seconds stale is fine. */
    private var cachedToolStatus: Triple<String, Boolean, Long>? = null

    private fun toolStatus(): Pair<String, Boolean> {
        val now = System.currentTimeMillis()
        val cached = cachedToolStatus
        if (cached != null && now - cached.third < TOOL_STATUS_TTL_MILLIS) return cached.first to cached.second
        val fresh = Triple(tools.statusText(), extension.available, now)
        cachedToolStatus = fresh
        return fresh.first to fresh.second
    }

    private fun publishState() {
        val (ytDlpStatus, extensionReady) = toolStatus()
        _ui.value = DesktopUiState(
            items = store.snapshot(),
            settings = settingsState.value,
            busyCount = engine.busy.value,
            ytDlpStatus = ytDlpStatus,
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
            extensionReady = extensionReady,
            extensionPath = extension.pathForDisplay(),
            rssItems = _rssItems.value,
            rssRefreshing = _rssRefreshing.value
        )
    }

    val actions: DesktopActions = DesktopActions(
        addDownload = ::addDownload,
        addPrepared = ::addPrepared,
        addChosenVideo = ::addChosenVideo,
        listVideoFormats = { url, done ->
            // Blocking process I/O must not sit on the shared Default pool: every
            // running YouTube download already parks one of its few threads for its
            // whole duration, and on a machine with several of those the lookup
            // coroutine never gets a thread - the dialog spins for ever with no
            // timeout able to reach it, because the timeout lives inside the
            // starved coroutine. IO exists for exactly this kind of work.
            scope.launch(Dispatchers.IO) {
                // A thrown exception must still answer the dialog. Without this, any
                // unexpected failure inside the lookup skips `done` entirely and the
                // dialog sits on "Reading what this video offers..." for ever, with
                // no timeout and no Retry able to reach it.
                done(
                    runCatching { ytdlp.listFormats(url) }.getOrElse { failed ->
                        YtDlpEngine.FormatListing(
                            emptyList(), emptyList(), "", 0L,
                            "Could not read that link" +
                                (failed.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
                        )
                    }
                )
            }
        },
        fetchYouTubeListing = { url, done ->
            scope.launch(Dispatchers.IO) {
                done(
                    runCatching { ytdlp.fetchPlaylist(url) }.getOrElse { failed ->
                        YtDlpEngine.PlaylistFetch(
                            "", emptyList(), false,
                            "Could not read that link" +
                                (failed.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
                        )
                    }
                )
            }
        },
        queueYouTubeEntries = ::queueYouTubeEntries,
        setFilePriority = { id, index, priority ->
            // Torrents only. An HTTP download has no files to prioritise, and the file
            // list is only ever shown for a torrent, so nothing else can reach this.
            torrents.setFilePriority(id, index, priority)
        },
        setAllFilePriorities = { id, priority ->
            torrents.setAllFilePriorities(id, priority)
        },
        pause = { id ->
            val item = store.get(id)
            if (item?.source == DownloadSource.TORRENT) torrents.pause(id) else engine.pause(id)
        },
        resume = { id ->
            val item = store.get(id)
            // A finished download says so instead of starting again.
            //
            // Resume on a completed row used to hand it straight to the engine, which
            // re-fetched the file from the beginning - or, for a torrent, put it back in
            // the session and started seeding it again - because from the engine's point
            // of view a download that has stopped is a download that has been paused.
            // Nothing about the button said "unless this is already done", and re-
            // downloading four gigabytes because someone pressed Resume on something
            // they had just finished watching download was not a reasonable outcome.
            //
            // So a finished row is left alone. Resume is not offered for one any more, and
            // the "already finished" answer belongs to Retry, the button that is.
            if (item?.status == DownloadStatus.COMPLETED) {
                Unit
            } else if (item?.source == DownloadSource.TORRENT) {
                torrents.resume(id)
            } else if (item?.source == DownloadSource.YOUTUBE) {
                // yt-dlp, never the HTTP engine: that one fetched the YouTube page itself
                // and saved its HTML as the "video".
                scope.launch(Dispatchers.IO) { runYtDlp(id) }
            } else {
                engine.resume(id)
            }
        },
        stop = { id ->
            val item = store.get(id)
            // A torrent is taken out of libtorrent, which is what stops it for good. An
            // ordinary download has no session to be removed from, so it is paused and
            // its row says plainly that it was stopped - rather than being left looking
            // like it is still going.
            if (item?.source == DownloadSource.TORRENT) {
                torrents.stop(id)
            } else {
                engine.pause(id)
                store.update(id) {
                    it.copy(
                        status = DownloadStatus.PAUSED,
                        speedBytesPerSecond = 0L,
                        errorMessage = "Stopped"
                    )
                }
                store.persist()
                refresh()
            }
        },
        retry = { id ->
            val item = store.get(id)
            // Retrying something finished would fetch the whole file again; say where it is
            // instead. This is the one place the "already finished" message belongs.
            if (item?.status == DownloadStatus.COMPLETED) {
                _messages.value = "That one is already finished. Its file is " +
                    (item.location ?: "in the downloads folder") + "."
            } else if (item?.source == DownloadSource.TORRENT) {
                torrents.resume(id)
            } else {
                engine.retry(id)
            }
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
            // The cache folder is a process-wide path, so it is applied here rather than
            // at the next start. Saving it and still writing to the old folder until a
            // restart reads as the setting doing nothing at all, which is the complaint
            // that made it worth having.
            runCatching {
                val folder = updated.cacheDirFile()
                if (folder.isDirectory || folder.mkdirs()) AppPaths.cacheDirectory = folder
            }
            settingsState.value = updated
            DesktopSettings.save(updated)
            refresh()
        },
        chooseFolder = ::chooseFolder,
        chooseCacheFolder = ::chooseCacheFolder,
        readMagnetMetadata = ::readMagnetMetadata,
        copyToClipboard = { text -> copyToClipboard(text) },
        exportTorrent = ::exportTorrent,
        renameDownload = ::renameDownload,
        setDownloadLocation = ::setDownloadLocation,
        setBrowserCapture = ::setBrowserCapture,
        consumeMessage = ::consumeMessage,
        setTorrentsTab = ::setTorrentsTab,
        openExtensionFolder = ::openExtensionFolder,
        addExtensionTo = ::addExtensionTo,
        openDownloadFolder = ::openDownloadFolder,
        revealDownload = ::revealDownload,
        checkForUpdates = ::checkForUpdates,
        downloadUpdate = { portable -> downloadUpdate(portable) },
        revealDownloadedInstaller = ::revealDownloadedInstaller,
        setItemOptions = ::setItemOptions,
        saveQueue = ::saveQueue,
        deleteQueue = ::deleteQueue,
        setQueueStarted = ::setQueueStarted,
        moveToQueue = ::moveToQueue,
        addRssFeed = ::addRssFeed,
        removeRssFeed = ::removeRssFeed,
        refreshRss = ::refreshRss,
        saveRssRules = ::saveRssRules,
        downloadRssItem = ::downloadRssItem,
        torrentTrackers = { id -> torrents.trackers(id) },
        torrentPeers = { id -> torrents.peers(id) },
        addTrackers = { id, urls ->
            if (!torrents.addTrackers(id, urls)) _messages.value = "Start the torrent first, then add trackers to it."
        },
        forceRecheck = { id ->
            _messages.value = if (torrents.forceRecheck(id)) "Checking every piece on disk." else "Start the torrent first to recheck it."
        },
        forceReannounce = { id ->
            _messages.value = if (torrents.forceReannounce(id)) "Asking the trackers for peers now." else "Start the torrent first to reannounce it."
        },
        setItemRequest = { id, request ->
            store.update(id) {
                it.copy(
                    requestHeaders = request.headers,
                    cookies = request.cookies,
                    username = request.username,
                    password = request.password
                )
            }
            store.persist()
            refresh()
        },
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
    /**
     * Queues whatever the pre-download dialog was opened for.
     *
     * One path for all three kinds of add, because the dialog is offered for all three.
     * A `.torrent` is copied into the app's own folder first: the file the user picked
     * is usually in Downloads, which gets tidied, and a queued torrent whose .torrent has
     * gone is a permanent row that can never start.
     */
    fun addPrepared(request: com.downloadhub.core.TorrentAddRequest) {
        val file = request.metainfoFile
        val link = if (file != null) {
            val kept = File(
                AppPaths.torrentRoot,
                TorrentSelection.safeName(request.metainfo.name).ifBlank { "torrent" } + ".torrent"
            )
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes != null && runCatching {
                    kept.parentFile?.mkdirs(); kept.writeBytes(bytes); true
                }.getOrDefault(false)) {
                kept
            } else {
                file
            }
        } else {
            null
        }
        addDownload(
            link = link?.absolutePath ?: request.link,
            audioOnly = false,
            format = "m4a",
            height = null,
            playlist = false,
            preferredName = request.metainfo.name.ifBlank { LinkParser.fileNameFrom(request.link) },
            torrentFile = link,
            torrentRequest = request
        )
    }

    /**
     * Queues a YouTube link at the exact quality the chooser offered.
     *
     * The whole point of the chooser is that the streams are chosen before anything is
     * written to disk, so this takes the ids rather than a height: by the time a height
     * could be used the two would already have to be guessed apart again, and 1080p60
     * would be indistinguishable from 1080p.
     *
     * The height is still filled in, because the list column shows it. That is a display
     * field here, not the download's definition - the ids are.
     */
    fun addChosenVideo(
        link: String,
        choice: com.downloadhub.core.StreamChoice,
        audioOnly: Boolean,
        preferredName: String? = null
    ) {
        addDownload(
            link = link,
            audioOnly = audioOnly,
            format = choice.audio?.ext?.ifBlank { "m4a" } ?: "m4a",
            height = choice.video.height,
            playlist = false,
            preferredName = preferredName?.takeIf { it.isNotBlank() },
            streamFormatId = choice.video.formatId,
            streamAudioFormatId = choice.audio?.formatId,
            // Known now, from the row that was tapped - which is what makes the
            // size column and the progress bar right from the first second.
            totalBytes = choice.totalBytes
        )
    }

    /**
     * Queues a batch of videos at one quality, skipping what is already queued.
     *
     * The id is the dedup key, never the title or the URL shape: titles repeat
     * across uploads, and the same video arrives as `watch?v=`, `youtu.be` and
     * Shorts links in one afternoon. Returns how many were actually queued, so
     * the panel can say "12 queued, 3 already there" instead of just closing.
     */
    fun queueYouTubeEntries(
        entries: List<com.downloadhub.core.YouTubeEntry>,
        audioOnly: Boolean,
        maxHeight: Int?,
        audioFormat: String = "m4a"
    ): Int {
        val known = store.snapshot()
            .asSequence()
            .filter { it.source == DownloadSource.YOUTUBE }
            .mapNotNull { com.downloadhub.core.youTubeIdFromUrl(it.url) }
            .toHashSet()
        var queued = 0
        entries.forEach { entry ->
            if (!known.add(entry.id)) return@forEach
            addDownload(
                link = entry.url,
                audioOnly = audioOnly,
                format = audioFormat.ifBlank { "m4a" },
                height = if (audioOnly) null else maxHeight,
                playlist = false,
                preferredName = entry.title,
                // The batch's total is only known once a quality is chosen, and it
                // is chosen after queueing. Zero leaves the row's bar honest rather
                // than moving against a total nobody stated.
                totalBytes = 0L
            )
            queued++
        }
        return queued
    }

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
        torrentFile: File? = null,
        /**
         * The choices made in the pre-download dialog, when it was used.
         *
         * Null is the normal path for a magnet or a link, and everything then behaves
         * exactly as it did. A non-null request carries the file selection, save
         * directory and share limits the dialog collected.
         */
        torrentRequest: com.downloadhub.core.TorrentAddRequest? = null,
        /**
         * The two streams the YouTube chooser picked, as yt-dlp format ids.
         *
         * Null for everything that is not a video, and for a video added without the
         * chooser, which is why the height parameter still exists: the chooser is the
         * better path but it is not the only way in, and the other way has to keep working.
         */
        streamFormatId: String? = null,
        streamAudioFormatId: String? = null,
        /**
         * Bytes the download will take, when they are already known.
         *
         * A video's size is known the moment a row is chosen from the format list,
         * and writing it then is what makes the row's size and progress bar real
         * from the first second. Without it the total stayed zero, so the bar could
         * not move and the row read "Connecting" for the whole download.
         */
        totalBytes: Long = 0L
    ) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return
        // A local .torrent given as a bare path, with no explicit hint, still counts.
        val localTorrent = torrentFile?.takeIf { it.isFile }
            ?: trimmed.takeIf { LinkParser.sourceFor(it) == DownloadSource.TORRENT }
                ?.let { File(it) }
                ?.takeIf { it.isFile }
        val source = LinkParser.sourceFor(trimmed)

        // Anything that is not a link, a magnet or a file on this machine cannot be
        // downloaded. Queued anyway it sits in the list and then fails with "no protocol:
        // <the text>", which reads as the app failing rather than as the input being
        // wrong. Refusing here names the actual problem.
        if (localTorrent == null && !LinkParser.isFetchable(trimmed)) {
            _messages.value =
                "That is not a link, a magnet or a file on this computer, so there is " +
                    "nothing to download from it."
            refresh()
            return
        }

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
                quality = height?.toString(),
                audioFormat = if (audioOnly) format else null,
                streamFormatId = streamFormatId,
                streamAudioFormatId = streamAudioFormatId,
                // Only where something already knows it. A plain HTTP row's length
                // comes from its own headers, not from here.
                totalBytes = if (source == DownloadSource.YOUTUBE) totalBytes else 0L,
                playlist = playlist,
                torrentFilePath = localTorrent?.absolutePath,
                // "Add but do not start" has to reach the queue as a paused row. Left
                // queued it starts anyway the instant the torrent loop looks at it, which
                // is exactly what the user unticked to avoid.
                status = if (torrentRequest != null && !torrentRequest.startImmediately) {
                    DownloadStatus.PAUSED
                } else {
                    DownloadStatus.QUEUED
                },
                outputPath = torrentRequest?.saveDirectory?.absolutePath,
                shareRatioLimit = torrentRequest?.let {
                    it.stopCondition.toShareLimits(it.metainfo.totalSize).ratioLimit
                } ?: 0.0,
                seedTimeLimitMinutes = torrentRequest?.let {
                    it.stopCondition.toShareLimits(it.metainfo.totalSize).seedTimeLimitMinutes
                } ?: 0,
                torrentSelectedFiles = torrentRequest?.selectedFiles?.toList().orEmpty(),
                torrentSequential = torrentRequest?.sequentialDownload ?: false,
                torrentFirstLastPiecesFirst = torrentRequest?.downloadFirstAndLastPiecesFirst ?: false,
                torrentContentFolder = torrentRequest?.contentFolder.orEmpty(),
                requestHeaders = withCapturedReferer(link, torrentRequest?.http?.headers.orEmpty()),
                cookies = torrentRequest?.http?.cookies.orEmpty(),
                username = torrentRequest?.http?.username.orEmpty(),
                password = torrentRequest?.http?.password.orEmpty()
            )
        )
        // Keep the file list for a magnet.
        //
        // The pre-download dialog has just fetched it from the swarm, and it is the
        // only copy that will ever exist: libtorrent4j cannot write a magnet's
        // metadata back out as a .torrent file, and the Content tab reads one. So a
        // torrent that arrived as a magnet - which is every torrent found by
        // searching - had a file list drawn in the dialog and then nothing at all
        // afterwards. Saved here, where the item's id can find it again.
        if (source == DownloadSource.TORRENT) {
            com.downloadhub.core.TorrentMetainfoStore.write(
                AppPaths.home,
                id,
                torrentRequest?.metainfo?.takeIf { it.files.isNotEmpty() }
            )
        }
        store.persist()
        refresh()

        if (source == DownloadSource.YOUTUBE) {
            // IO, not the shared Default pool: a download parks its thread in
            // blocking process I/O for its whole duration, and several of those
            // would starve the pool the quality lookup also runs on.
            scope.launch(Dispatchers.IO) { runYtDlp(id) }
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
        var reviewed = 0
        targets.forEach { target ->
            // Checked here rather than after the dialog, so nothing already in the list
            // is ever put in front of the user to be looked at again.
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
            /**
             * Everything is shown in the pre-download dialog, whatever brought it here.
             *
             * A `.torrent` is a container and queueing one unexamined downloads
             * everything inside it. A magnet is the same download in a different wrapper,
             * and a plain link is the same as either: there is always a folder to choose
             * and a chance to refuse before anything starts.
             *
             * Only a `.torrent` used to be reviewed. A magnet arriving from a
             * magnet-aware browser - which is the ordinary way to start one now - went
             * straight into the queue, which is what was reported: the row appeared and
             * the dialog never did.
             *
             * With no window there is no dialog to show, so it is queued directly. That
             * is the old behaviour and is better than losing the download.
             */
            val review = onDownloadNeedsReview
            if (review != null) {
                review(target.torrentFile ?: target.link)
                reviewed++
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
        // Say nothing when everything is waiting behind a dialog. Announcing "added 1"
        // for a download that has not started and may still be refused reads as though it
        // were already running.
        if (added == 0 && reviewed > 0) return
        _messages.value = when {
            added == 0 -> "That is already in the list."
            skipped == 0 -> if (added == 1) "Added 1 download from the link you opened."
            else "Added $added downloads from the links you opened."

            else -> "Added $added; ${if (skipped == 1) "1 was already" else "$skipped were already"} in the list."
        }
        refresh()
    }

    /** YouTube downloads with a yt-dlp running, so a resume and a restart never start one twice. */
    private val ytRunning: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private suspend fun runYtDlp(id: String) {
        if (!ytRunning.add(id)) return
        try {
            runYtDlpOnce(id)
        } finally {
            ytRunning.remove(id)
        }
    }

    private suspend fun runYtDlpOnce(id: String) {
        val item = store.get(id) ?: return
        store.update(id) { it.copy(status = DownloadStatus.RESOLVING) }
        refresh()

        val jobDir = File(AppPaths.workDir, "yt-$id")
        // yt-dlp's own partial files (.part, .ytdl) are kept so it carries on where it
        // stopped - after a pause, an app restart or an update - instead of starting over.
        // Anything else left in the folder is cleared, so a stale file is never mistaken
        // for this download's result.
        jobDir.listFiles()?.filterNot { it.name.endsWith(".part") || it.name.endsWith(".ytdl") }
            ?.forEach { it.deleteRecursively() }
        val result = ytdlp.download(
            YtDlpRequest(
                url = item.url,
                audioOnly = item.audioFormat != null,
                audioFormat = item.audioFormat ?: "m4a",
                maxHeight = item.quality?.toIntOrNull(),
                playlist = item.playlist,
                videoFormatId = item.streamFormatId,
                audioFormatId = item.streamAudioFormatId
            ),
            targetDir = jobDir
        ) { percent, _ ->
            scope.launch {
                store.update(id) { current ->
                    // A progress line can still be in flight when the job finishes.
                    // Applying it afterwards would overwrite the final size with a
                    // partial one - a finished 32 MB video showing as 0 bytes.
                    if (current.status != DownloadStatus.RESOLVING &&
                        current.status != DownloadStatus.RUNNING
                    ) {
                        return@update current
                    }
                    // The first progress line is the end of "Connecting" and the
                    // start of "Downloading". It never used to happen: nothing moved
                    // the row out of RESOLVING, so a video sat on "Connecting" for
                    // its whole download and then finished in one step.
                    val total = current.totalBytes
                    current.copy(
                        status = DownloadStatus.RUNNING,
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

    /** Opens the picker at the current cache folder, not at the download folder. */
    fun chooseCacheFolder(): File? = runCatching {
        val chooser = JFileChooser(settingsState.value.cacheDirFile().takeIf { it.isDirectory })
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }.getOrNull()

    /**
     * Puts text on the clipboard.
     *
     * A failure is reported rather than swallowed: a "Copy magnet link" that silently
     * does nothing is a menu item that reads as broken.
     */
    fun copyToClipboard(text: String) {
        val done = runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(text),
                null
            )
        }.isSuccess
        if (!done) _messages.value = "Could not reach the clipboard."
        refresh()
    }

    /**
     * Saves a torrent's `.torrent` file somewhere the user picks.
     *
     * Copied from the copy the app keeps rather than re-fetched: the original the user
     * dropped is usually gone by now, and this is the same bytes that identify the
     * torrent.
     */
    fun exportTorrent(id: String) {
        val item = store.get(id)
        val source = item?.torrentFilePath?.let(::File)?.takeIf { it.isFile }
        if (item?.source != DownloadSource.TORRENT || source == null) {
            // A magnet has no file until peers have answered, so there is genuinely
            // nothing to save. Saying so beats a save dialog that produces an empty file.
            _messages.value =
                "This torrent has no .torrent file yet. A magnet only gets one once it " +
                    "has connected."
            refresh()
            return
        }
        val chooser = JFileChooser(File(System.getProperty("user.home") ?: ".", item.fileName + ".torrent"))
        chooser.dialogTitle = "Save the torrent file"
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val chosen = chooser.selectedFile
            val target = if (chosen.name.endsWith(".torrent", ignoreCase = true)) {
                chosen
            } else {
                File(chosen.parentFile, chosen.name + ".torrent")
            }
            if (!runCatching { target.writeBytes(source.readBytes()) }.isSuccess) {
                _messages.value = "Could not write that file."
            }
        }
        refresh()
    }

    /**
     * Renames a finished download.
     *
     * The file on disk is renamed, not just the row: a download whose label says one
     * thing and whose file is called another is worse than one that cannot be renamed.
     */
    fun renameDownload(id: String, name: String) {
        val item = store.get(id) ?: return
        val safe = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (safe.isEmpty()) return
        val current = item.location?.let(::File)
        if (current != null && current.isFile) {
            val target = File(current.parentFile, safe)
            if (!runCatching { current.renameTo(target) }.getOrDefault(false)) {
                _messages.value = "Could not rename that file. It may be in use."
                refresh()
                return
            }
            store.update(id) { it.copy(fileName = safe, location = target.absolutePath) }
        } else {
            store.update(id) { it.copy(fileName = safe) }
        }
        store.persist()
        refresh()
    }

    /** Points an unfinished download at a different folder. */
    fun setDownloadLocation(id: String, path: String) {
        val folder = File(path)
        if (!folder.isDirectory && !folder.mkdirs()) {
            _messages.value = "That folder could not be created."
            refresh()
            return
        }
        store.update(id) { it.copy(outputPath = folder.absolutePath) }
        store.persist()
        refresh()
    }

    /**
     * Asks the swarm what a magnet contains.
     *
     * Off the UI thread and off the caller's thread, because it blocks on peers. The
     * read is a lookup: the torrent is added paused and its session is thrown away as
     * soon as the metadata is in, so nothing appears in the list and no data is
     * downloaded on the way.
     */
    fun readMagnetMetadata(
        magnet: String,
        done: (com.downloadhub.core.TorrentMetainfo?) -> Unit
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                com.downloadhub.core.TorrentMetadataReader.read(magnet)
            }
            done(result.getOrNull())
        }
    }

    fun close() {
        store.persist()
        capture.stop()
        torrents.close()
        com.downloadhub.core.TorrentMetadataReader.shutdown()
        engine.close()
    }

    // Last in the class, so every field publishState() reads is assigned by now.
    init {
        initialised = true
        refreshLoop.value
        // So the first magnet's file list does not wait on DHT bootstrap.
        com.downloadhub.core.TorrentMetadataReader.warmUp()
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


/** How often queue schedules are checked. A start time is honoured within this. */
private const val QUEUE_SCHEDULER_TICK_MILLIS = 20_000L

/** The fastest the window is rebuilt from engine progress; about eight frames a second. */
private const val UI_REFRESH_MILLIS = 120L

/** How long the yt-dlp and extension status may be reused before checking the disk again. */
private const val TOOL_STATUS_TTL_MILLIS = 3_000L

/** How long after start the quiet update check waits, so it does not compete with startup. */
private const val STARTUP_UPDATE_CHECK_DELAY_MILLIS = 15_000L

/** How often the watched folder is looked in. */
private const val WATCH_FOLDER_TICK_MILLIS = 5_000L

/** Feeds are first read a little after start, so they do not compete with startup. */
private const val RSS_FIRST_REFRESH_DELAY_MILLIS = 20_000L
