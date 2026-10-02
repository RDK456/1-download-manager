package com.downloadhub.app.ui

import android.app.Application
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.downloadhub.app.DownloadHubApplication
import com.downloadhub.app.data.DownloadSettings
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadCreateRequest
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.download.DownloadService
import com.downloadhub.app.download.LinkParser
import com.downloadhub.app.download.MediaCandidate
import com.downloadhub.app.download.MediaKind
import com.downloadhub.app.download.PageScanState
import com.downloadhub.app.download.PageScanner
import com.downloadhub.app.download.AppPlaylistFetch
import com.downloadhub.app.download.YouTubeFormatListing
import com.downloadhub.app.download.youTubeVideoId
import com.downloadhub.core.LibraryKind
import com.downloadhub.core.YouTubeEntry
import com.downloadhub.app.update.YtDlpUpdateState
import com.downloadhub.app.update.compareVersions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadFilter {
    ALL,
    ACTIVE,
    COMPLETED
}

/** Per-tab counters shown in the summary band. */
data class TabSummary(
    val active: Int = 0,
    val paused: Int = 0,
    val completed: Int = 0,
    val total: Int = 0
)

data class EditorSeed(
    val link: String,
    val source: DownloadSource,
    val fileName: String? = null,
    val category: DownloadCategory? = null,
    val userAgent: String? = null,
    val contentDisposition: String? = null,
    val quality: MediaQuality = MediaQuality.BEST,
    val audioFormat: AudioFormat = AudioFormat.M4A
)

sealed interface DownloadEvent {
    data class Message(val text: String) : DownloadEvent
}

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DownloadHubApplication
    private val repository = app.container.repository
    private val settings = app.container.settings

    /** Shared artwork cache handed to the download cards. */
    val thumbnailCache = app.container.thumbnailCache

    // SharingStarted.Lazily, and this is the whole fix for a download list that
    // would appear and then vanish.
    //
    // WhileSubscribed(5_000) stops collecting the moment the last screen watching
    // the list goes away, and restarts it later with the *initial* value it was
    // given. For a list that initial value is empty, so navigating to another tab
    // and coming back after five seconds put an empty list on screen and left every
    // list derived from it - the torrent list, both tab summaries, the category
    // counts - showing nothing but their defaults until the database happened to
    // re-emit. The list came and went on its own, and only a restart cleared it.
    //
    // Lazily starts the collection on the first subscription and then keeps it, so
    // the last real list is held for as long as the screen exists. It costs nothing
    // to keep alive: Room's flow is an invalidation tracker that only re-queries
    // when the table actually changes.
    val allDownloads: StateFlow<List<DownloadEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Lazily, ThemeMode.SYSTEM)
    val appTheme: StateFlow<AppTheme> = settings.appTheme
        .stateIn(viewModelScope, SharingStarted.Lazily, AppTheme.MINT)
    val downloadSettings: StateFlow<DownloadSettings> = settings.downloadSettings
        .stateIn(viewModelScope, SharingStarted.Lazily, DownloadSettings())
    val destinationTreeUri: StateFlow<String?> = settings.destinationTreeUri
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    private val _downloaderVersion = MutableStateFlow(
        app.container.youtubeDownloader.currentVersion() ?: "Bundled"
    )
    val downloaderVersion: StateFlow<String> = _downloaderVersion

    private val _ytdlpUpdate = MutableStateFlow<YtDlpUpdateState>(YtDlpUpdateState.Idle)
    val ytdlpUpdate: StateFlow<YtDlpUpdateState> = _ytdlpUpdate.asStateFlow()

    /**
     * Checks the yt-dlp release feed and installs a newer build without asking.
     *
     * This used to be a "Check for update" button followed by a second tap to
     * install, and the checking state flickered because both steps rewrote the
     * status row. One pass now owns the whole thing, so the row can only move
     * Idle -> Checking -> a settled result.
     *
     * It is throttled per day: yt-dlp releases often, and a network round trip on
     * every launch would be wasteful.
     */
    fun syncYtDlpInBackground() {
        if (_ytdlpUpdate.value is YtDlpUpdateState.Checking) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val last = settings.lastYtDlpCheck()
            if (now - last < YTDLP_CHECK_INTERVAL_MILLIS) return@launch

            _ytdlpUpdate.value = YtDlpUpdateState.Checking
            val installed = app.container.youtubeDownloader.currentVersion()
            val latest = runCatching {
                app.container.youtubeDownloader.latestStableVersion()
            }.getOrNull()

            if (latest.isNullOrBlank()) {
                // Do not mark the day as checked: a network blip should be retried
                // on the next launch rather than silently waiting a full day.
                _ytdlpUpdate.value = YtDlpUpdateState.Failed("Could not reach the yt-dlp release feed")
                return@launch
            }
            settings.markYtDlpCheck()

            if (compareVersions(latest, installed) <= 0) {
                _ytdlpUpdate.value = YtDlpUpdateState.UpToDate(installed)
                return@launch
            }

            // A newer release exists, so install it rather than asking.
            _ytdlpUpdate.value = YtDlpUpdateState.Updating(installed, latest)
            runCatching {
                app.container.youtubeDownloader.updateYtDlpIfNeeded(force = true)
            }.onSuccess { version ->
                val now2 = version ?: app.container.youtubeDownloader.currentVersion()
                _downloaderVersion.value = now2
                if (compareVersions(latest, now2) > 0) {
                    // The install did not take. Leave the target visible so the
                    // next launch retries instead of claiming success.
                    _ytdlpUpdate.value = YtDlpUpdateState.Available(now2, latest)
                } else {
                    _ytdlpUpdate.value = YtDlpUpdateState.UpToDate(now2)
                    _events.emit(DownloadEvent.Message("yt-dlp updated to $now2"))
                }
            }.onFailure { error ->
                _ytdlpUpdate.value = YtDlpUpdateState.Failed(
                    error.message ?: "yt-dlp update failed"
                )
            }
        }
    }

    /** Retries a failed check immediately; only offered when something went wrong. */
    fun retryYtDlpSync() {
        viewModelScope.launch { settings.markYtDlpCheck(0L) }
        _ytdlpUpdate.value = YtDlpUpdateState.Idle
        syncYtDlpInBackground()
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(DownloadFilter.ALL)
    private val categoryFilter = MutableStateFlow<DownloadCategory?>(null)
    private val kindFilter = MutableStateFlow(LibraryKind.ALL)
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _editorSeed = MutableStateFlow<EditorSeed?>(null)
    private val _pageScan = MutableStateFlow<PageScanState>(PageScanState.Idle)
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val selectedId: StateFlow<String?> = _selectedId
    val editorSeed: StateFlow<EditorSeed?> = _editorSeed
    val pageScan: StateFlow<PageScanState> = _pageScan.asStateFlow()
    val currentFilter: StateFlow<DownloadFilter> = filter
    val currentCategory: StateFlow<DownloadCategory?> = categoryFilter
    val currentKind: StateFlow<LibraryKind> = kindFilter
    val searchQuery: StateFlow<String> = query

    /**
     * The Downloads tab, narrowed to one kind of download.
     *
     * It used to draw a line through the whole list and show only what was not a
     * torrent, which left YouTube downloads and ordinary links in one list of
     * unrelated things. The kinds are now a filter with All as the default, so All
     * still means the entire queue - torrents included - and the Torrents tab beside
     * it is a shortcut rather than the only way to see one.
     */
    val visibleDownloads: StateFlow<List<DownloadEntity>> = combine(
        allDownloads,
        query,
        filter,
        categoryFilter,
        kindFilter
    ) { items, search, selectedFilter, selectedCategory, selectedKind ->
        val normalized = search.trim().lowercase()
        items.filter { item ->
            selectedKind.matches(item.source.name) &&
                matchesSearch(item, normalized) &&
                matchesStatus(item, selectedFilter) &&
                matchesCategory(item, selectedCategory)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val visibleTorrents: StateFlow<List<DownloadEntity>> = combine(
        allDownloads,
        query,
        filter,
        categoryFilter
    ) { items, search, selectedFilter, selectedCategory ->
        val normalized = search.trim().lowercase()
        items.filter { item ->
            item.source == DownloadSource.TORRENT &&
                matchesSearch(item, normalized) &&
                matchesStatus(item, selectedFilter) &&
                matchesCategory(item, selectedCategory)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mainSummary: StateFlow<TabSummary> = combine(allDownloads, kindFilter) { list, kind ->
        list.filter { kind.matches(it.source.name) }.toSummary()
    }.stateIn(viewModelScope, SharingStarted.Lazily, TabSummary())

    val torrentSummary: StateFlow<TabSummary> = allDownloads
        .map { list -> list.filter { it.source == DownloadSource.TORRENT }.toSummary() }
        .stateIn(viewModelScope, SharingStarted.Lazily, TabSummary())

    /** Category counts for the filter sheet; torrents and files are counted apart. */
    val categoryCounts: StateFlow<Map<DownloadCategory, Int>> = allDownloads
        .map { list -> list.countCategories { it.source != DownloadSource.TORRENT } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /**
     * How many of each kind there are, for the row of kind chips.
     *
     * Counted over the whole queue rather than over the narrowed list, so a chip does
     * not read zero merely because it is not the one being shown - a zero beside
     * Torrents means there are no torrents, and a count has to be able to say that.
     */
    val kindCounts: StateFlow<Map<LibraryKind, Int>> = allDownloads
        .map { list ->
            LibraryKind.entries.associateWith { kind ->
                list.count { kind.matches(it.source.name) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    /** Counts for the Torrents tab, whose categories are derived from the payload. */
    val torrentCategoryCounts: StateFlow<Map<DownloadCategory, Int>> = allDownloads
        .map { list -> list.countCategories { it.source == DownloadSource.TORRENT } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val selectedDownload: StateFlow<DownloadEntity?> = combine(allDownloads, _selectedId) { items, id ->
        items.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun setFilter(value: DownloadFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategoryFilter(value: DownloadCategory?) {
        categoryFilter.value = value
    }

    fun setKindFilter(value: LibraryKind) {
        kindFilter.value = value
    }

    fun resetFilters() {
        filter.value = DownloadFilter.ALL
        categoryFilter.value = null
        kindFilter.value = LibraryKind.ALL
    }

    /**
     * Whether anything is narrowing the list.
     *
     * Says yes for a kind that is not All, so choosing one and then hitting reset
     * still looks like there is something to clear.
     */
    val hasActiveFilter: StateFlow<Boolean> = combine(filter, categoryFilter, kindFilter) {
            status, category, kind ->
        status != DownloadFilter.ALL || category != null || kind != LibraryKind.ALL
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun select(id: String?) {
        _selectedId.value = id
    }

    fun openEditor(seed: EditorSeed? = null) {
        _editorSeed.value = seed ?: EditorSeed(
            link = "",
            source = DownloadSource.HTTP
        )
    }

    fun closeEditor() {
        _editorSeed.value = null
    }

    /**
     * Opens the add sheet on a magnet the search found.
     *
     * The same seed a pasted magnet produces, so the sheet is the sheet: the file list, the
     * folder picker and the stop condition are all already there and none of them had to be
     * written a second time for search results. Returns to the list afterwards, because the
     * sheet is a sheet over the list and a sheet over a search box would be a sheet over a
     * screen the user came to leave.
     */
    fun prepareSearchResult(magnet: String) {
        openEditor(
            EditorSeed(
                link = magnet,
                source = DownloadSource.TORRENT
            )
        )
    }

    /**
     * Looks for video, audio and downloadable files on a pasted page. Links that
     * are already media files skip the scan and queue straight away.
     */
    fun scanPageForMedia(url: String) {
        if (_pageScan.value is PageScanState.Scanning) return
        val target = LinkParser.extractFirstLink(url) ?: url.trim()
        viewModelScope.launch {
            _pageScan.value = PageScanState.Scanning
            when (val result = PageScanner().scan(target)) {
                is PageScanState.AlreadyMedia -> {
                    _pageScan.value = PageScanState.Idle
                    _events.emit(DownloadEvent.Message("That link is already a media file"))
                    addLink(target)
                }
                is PageScanState.Failed -> {
                    _pageScan.value = PageScanState.Failed(result.message)
                }
                is PageScanState.Found -> _pageScan.value = result
                else -> _pageScan.value = PageScanState.Idle
            }
        }
    }

    fun dismissPageScan() {
        _pageScan.value = PageScanState.Idle
    }

    /** Queues one item discovered by the page scan. */
    fun addScannedMedia(candidate: MediaCandidate) {
        viewModelScope.launch {
            val category = when (candidate.kind) {
                MediaKind.VIDEO -> DownloadCategory.VIDEO
                MediaKind.AUDIO -> DownloadCategory.AUDIO
                MediaKind.PLAYER -> DownloadCategory.VIDEO
                MediaKind.FILE -> LinkParser.categoryFor(
                    DownloadSource.HTTP,
                    candidate.label.ifBlank { "download.${candidate.extension}" },
                    null
                )
            }
            val request = DownloadCreateRequest(
                source = if (candidate.kind == MediaKind.PLAYER) {
                    DownloadSource.YOUTUBE
                } else {
                    DownloadSource.HTTP
                },
                url = candidate.url,
                fileName = LinkParser.sanitizeFileName(candidate.label),
                category = category
            )
            runCatching { repository.create(request) }
                .onSuccess {
                    DownloadService.start(getApplication(), listOf(it.id))
                    _events.emit(DownloadEvent.Message("Added \"${it.fileName}\" to the queue"))
                }
                .onFailure { _events.emit(DownloadEvent.Message(it.message ?: "Could not add download")) }
        }
    }

    /** Queues every item the scan found. */
    fun addAllScannedMedia() {
        val items = (_pageScan.value as? PageScanState.Found)?.items.orEmpty()
        if (items.isEmpty()) return
        viewModelScope.launch {
            val ids = mutableListOf<String>()
            items.forEach { candidate ->
                runCatching {
                    repository.create(
                        DownloadCreateRequest(
                            source = if (candidate.kind == MediaKind.PLAYER) {
                                DownloadSource.YOUTUBE
                            } else {
                                DownloadSource.HTTP
                            },
                            url = candidate.url,
                            fileName = LinkParser.sanitizeFileName(candidate.label),
                            category = when (candidate.kind) {
                                MediaKind.VIDEO -> DownloadCategory.VIDEO
                                MediaKind.AUDIO -> DownloadCategory.AUDIO
                                MediaKind.PLAYER -> DownloadCategory.VIDEO
                                MediaKind.FILE -> null
                            }
                        )
                    )
                }.onSuccess { ids += it.id }
            }
            if (ids.isNotEmpty()) {
                DownloadService.start(getApplication(), ids)
                _events.emit(DownloadEvent.Message("Added ${ids.size} item(s) to the queue"))
            }
            _pageScan.value = PageScanState.Idle
        }
    }

    /**
     * A YouTube link the add sheet handed over, loaded once by the YouTube tab.
     *
     * One-shot: the tab consumes it on arrival, so later recompositions do not
     * re-fetch it over what the user typed since.
     */
    private val _youTubePrefill = MutableStateFlow<String?>(null)
    val youTubePrefill: StateFlow<String?> = _youTubePrefill.asStateFlow()

    fun setYouTubePrefill(link: String) {
        _youTubePrefill.value = link.trim().takeIf { it.isNotEmpty() }
    }

    fun consumeYouTubePrefill() {
        _youTubePrefill.value = null
    }

    /**
     * What one video offers, with every format ungrouped, for the YouTube tab.
     *
     * The raw list - every height, frame rate and codec - because collapsing
     * one row per height is what once hid a 4K option behind a 1080p row.
     */
    suspend fun listYouTubeFormats(url: String): YouTubeFormatListing =
        app.container.youtubeDownloader.listFormats(url)

    /**
     * What a pasted link holds, for the YouTube section.
     *
     * A playlist, an album, a channel, or one video - the section lists whatever
     * it was and queues the checked rows.
     */
    suspend fun fetchYouTubeListing(url: String): AppPlaylistFetch =
        app.container.youtubeDownloader.fetchPlaylist(url)

    /**
     * Queues several videos at one quality, skipping what is already queued.
     *
     * The id is the dedup key, never the title or the URL shape. Reports what
     * happened - "Queued 12, 3 already in the queue" - because a batch that just
     * closes leaves no way to tell a success from a skip.
     */
    fun queueYouTubeEntries(
        entries: List<YouTubeEntry>,
        audioOnly: Boolean,
        maxHeight: Int?,
        onDone: (queued: Int, skipped: Int) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val known = allDownloads.value
                .asSequence()
                .filter { it.source == DownloadSource.YOUTUBE }
                .mapNotNull { youTubeVideoId(it.url) }
                .toHashSet()
            val quality = if (audioOnly) {
                MediaQuality.AUDIO
            } else {
                MediaQuality.entries.firstOrNull { it.maxHeight == maxHeight }
                    ?: MediaQuality.BEST
            }
            val ids = ArrayList<String>()
            var skipped = 0
            entries.forEach { entry ->
                if (!known.add(entry.id)) {
                    skipped++
                    return@forEach
                }
                runCatching {
                    repository.create(
                        DownloadCreateRequest(
                            source = DownloadSource.YOUTUBE,
                            url = entry.url,
                            fileName = entry.title,
                            category = if (audioOnly) {
                                DownloadCategory.AUDIO
                            } else {
                                DownloadCategory.VIDEO
                            },
                            quality = quality.value,
                            audioFormat = AudioFormat.M4A.value
                        )
                    )
                }.onSuccess { ids += it.id }.onFailure { skipped++ }
            }
            if (ids.isNotEmpty()) {
                DownloadService.start(getApplication(), ids)
            }
            onDone(ids.size, skipped)
            _events.emit(
                DownloadEvent.Message(
                    if (ids.isEmpty() && skipped > 0) {
                        "Everything selected is already in the queue"
                    } else if (skipped > 0) {
                        "Queued ${ids.size}, $skipped already in the queue"
                    } else {
                        "Queued ${ids.size}"
                    }
                )
            )
        }
    }

    fun addLink(
        rawLink: String,
        fileName: String? = null,
        category: DownloadCategory? = null,
        sourceOverride: DownloadSource? = null,
        userAgent: String? = null,
        contentDisposition: String? = null,
        quality: MediaQuality? = null,
        audioFormat: AudioFormat? = null,
        /**
         * Exact streams the picker named, as yt-dlp format ids.
         *
         * Null keeps the height ceiling, which is what every row queued before the
         * picker existed - and what a row added without it still uses.
         */
        streamFormatId: String? = null,
        streamAudioFormatId: String? = null
    ) {
        viewModelScope.launch {
            val link = LinkParser.extractFirstLink(rawLink) ?: rawLink.trim()
            val detectedSource = LinkParser.sourceFor(link)
            val source = if (sourceOverride == null ||
                (sourceOverride == DownloadSource.HTTP && detectedSource != DownloadSource.HTTP)
            ) {
                detectedSource
            } else {
                sourceOverride
            }
            val chosenQuality = quality ?: MediaQuality.BEST
            val audioOnly = source == DownloadSource.YOUTUBE && chosenQuality.isAudioOnly
            val effectiveCategory = category ?: when {
                source == DownloadSource.YOUTUBE && audioOnly -> DownloadCategory.AUDIO
                source == DownloadSource.YOUTUBE -> DownloadCategory.VIDEO
                else -> null
            }
            if (source == DownloadSource.TORRENT && !link.startsWith("magnet:", ignoreCase = true)) {
                if (!link.startsWith("http", ignoreCase = true)) {
                    _events.emit(DownloadEvent.Message("Paste a magnet link or choose a .torrent file"))
                    return@launch
                }
            }
            if (source != DownloadSource.TORRENT && !link.startsWith("http", ignoreCase = true)) {
                _events.emit(DownloadEvent.Message("Enter a valid http or https link"))
                return@launch
            }
            val request = DownloadCreateRequest(
                source = source,
                url = link,
                fileName = fileName,
                category = effectiveCategory,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                quality = if (source == DownloadSource.YOUTUBE) chosenQuality.value else null,
                audioFormat = if (source == DownloadSource.YOUTUBE) {
                    (audioFormat ?: AudioFormat.M4A).value
                } else {
                    null
                },
                streamFormatId = streamFormatId?.takeIf { source == DownloadSource.YOUTUBE },
                streamAudioFormatId = streamAudioFormatId?.takeIf { source == DownloadSource.YOUTUBE }
            )
            runCatching { repository.create(request) }
                .onSuccess {
                    closeEditor()
                    DownloadService.start(getApplication(), listOf(it.id))
                    _events.emit(DownloadEvent.Message("Added to the download queue"))
                }
                .onFailure { _events.emit(DownloadEvent.Message(it.message ?: "Could not add download")) }
        }
    }

    /**
     * Copies a picked .torrent file into app-private storage (so the download can
     * be resumed later without holding onto a transient content grant) and queues it.
     */
    fun addTorrentFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val name = app.contentResolver.displayName(uri)
                        ?: uri.lastPathSegment
                        ?: "download.torrent"
                    if (!LinkParser.looksLikeTorrent(name, app.contentResolver.getType(uri))) {
                        error("Select a .torrent file")
                    }
                    val inputDir = File(app.filesDir, "torrent-inputs")
                    inputDir.mkdirs()
                    val target = File(inputDir, "${UUID.randomUUID()}.torrent")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Could not read the selected torrent")
                    if (target.length() <= 0L) error("The selected .torrent file is empty")
                    requestFromTorrent(name, target.absolutePath)
                }
            }.onSuccess { request ->
                repository.create(request)
                    .also { DownloadService.start(getApplication(), listOf(it.id)) }
                closeEditor()
                _events.emit(DownloadEvent.Message("Torrent added to the queue"))
            }.onFailure {
                _events.emit(DownloadEvent.Message(it.message ?: "Could not read the torrent"))
            }
        }
    }

    fun pause(id: String) = sendAction(DownloadService.ACTION_PAUSE, id)
    /**
     * Resumes a download, unless it is already finished.
     *
     * Resume handed the row straight to the service, which started the transfer again -
     * so resuming something that had just finished re-fetched it from the beginning,
     * which is not what anyone pressing Resume on a finished download means and is an
     * expensive way to find out. A finished download says so instead.
     */
    fun resume(id: String) {
        viewModelScope.launch {
            val item = repository.get(id) ?: return@launch
            if (item.status == DownloadStatus.COMPLETED) {
                _events.emit(
                    DownloadEvent.Message(
                        if (item.outputPath.isNullOrBlank()) {
                            "That one is already finished."
                        } else {
                            "That one is already finished. Its file is at ${item.outputPath}."
                        }
                    )
                )
                return@launch
            }
            sendAction(DownloadService.ACTION_RESUME, id)
        }
    }

    fun retry(id: String) = sendAction(DownloadService.ACTION_RETRY, id)

    fun delete(id: String) {
        viewModelScope.launch {
            repository.get(id)?.let { item ->
                if (item.status.isActiveCompat) {
                    DownloadService.action(getApplication(), DownloadService.ACTION_CANCEL, id)
                }
                repository.delete(item)
                if (_selectedId.value == id) _selectedId.value = null
                _events.emit(DownloadEvent.Message("Download removed"))
            }
        }
    }

    fun notify(text: String) {
        viewModelScope.launch { _events.emit(DownloadEvent.Message(text)) }
    }

    fun setAppTheme(theme: AppTheme) {
        viewModelScope.launch { settings.setAppTheme(theme) }
    }

    fun setMaxConcurrent(value: Int) {
        viewModelScope.launch {
            settings.setMaxConcurrent(value)
            // Apply immediately without waiting for a restart.
            DownloadService.start(getApplication(), emptyList())
        }
    }

    fun setSpeedLimit(bytesPerSecond: Long) {
        viewModelScope.launch {
            settings.setSpeedLimit(bytesPerSecond)
            app.container.speedLimiter.setLimit(bytesPerSecond)
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setWifiOnly(enabled) }
    }

    fun setMaxRetries(value: Int) {
        viewModelScope.launch { settings.setMaxRetries(value) }
    }

    /** True when Android will not throttle this app for battery use. */
    fun isBatteryExempt(): Boolean = app.container.batteryOptimisation.isExempt()

    /** Sends the user to the system prompt that exempts the app from throttling. */
    fun requestBatteryExemption() {
        val context = getApplication<Application>()
        val intent = app.container.batteryOptimisation.requestIntent()
            ?: app.container.batteryOptimisation.settingsIntent()
        runCatching { context.startActivity(intent) }
            .onFailure { notify("Open Settings > Apps > 1 download manager > Battery > Unrestricted") }
    }

    fun setAutoRemoveCompleted(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoRemoveCompleted(enabled) }
    }
    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDestinationTreeUri(uri: String?) {
        viewModelScope.launch { settings.setDestinationTreeUri(uri) }
    }

    fun pauseAll() = DownloadService.action(getApplication(), DownloadService.ACTION_PAUSE_ALL)
    fun resumeAll() = DownloadService.action(getApplication(), DownloadService.ACTION_RESUME_ALL)

    /** Result of trying to hand a finished download to another app. */
    sealed interface OpenResult {
        data class Ready(val intent: Intent) : OpenResult
        data class Unavailable(val reason: String) : OpenResult
    }

    /**
     * Builds a VIEW intent for a finished download, verifying that some app can
     * actually handle it. Torrent output is a folder, so a single file inside it
     * is opened when possible and the folder itself otherwise.
     */
    fun openFor(item: DownloadEntity): OpenResult {
        if (item.status != DownloadStatus.COMPLETED) {
            return OpenResult.Unavailable("This download has not finished yet")
        }
        val path = item.outputPath
        if (path.isNullOrBlank()) return OpenResult.Unavailable("This download has no saved file")

        val context = getApplication<Application>()
        if (path.startsWith("content:")) {
            val uri = Uri.parse(path)
            val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            val isDirectory = document?.isDirectory == true
            val name = document?.name ?: item.fileName
            val mime = document?.type ?: mimeGuess(name)
            return resolveView(context, uri, if (isDirectory) FOLDER_MIME else mime, name, isDirectory)
        }

        val file = File(path)
        if (!file.exists()) return OpenResult.Unavailable("The saved file is no longer on this device")
        if (file.isDirectory) {
            val playable = largestPlayableFile(file)
            if (playable != null) {
                return resolveView(
                    context,
                    fileUri(playable),
                    mimeGuess(playable.name),
                    playable.name,
                    isDirectory = false
                )
            }
            return resolveView(context, fileUri(file), FOLDER_MIME, file.name, isDirectory = true)
        }
        return resolveView(context, fileUri(file), mimeGuess(file.name), file.name, isDirectory = false)
    }

    /** Wraps [openFor] in a system chooser so the user can pick "Open with". */
    fun openWithFor(item: DownloadEntity): OpenResult = when (val open = openFor(item)) {
        is OpenResult.Unavailable -> open
        is OpenResult.Ready -> OpenResult.Ready(
            Intent.createChooser(open.intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun shareFor(item: DownloadEntity): OpenResult {
        if (item.status != DownloadStatus.COMPLETED) {
            return OpenResult.Unavailable("This download has not finished yet")
        }
        val path = item.outputPath
            ?: return OpenResult.Unavailable("This download has no saved file")
        val context = getApplication<Application>()
        val uri: Uri
        val isDirectory: Boolean
        if (path.startsWith("content:")) {
            uri = Uri.parse(path)
            isDirectory = runCatching {
                DocumentFile.fromSingleUri(context, uri)?.isDirectory == true
            }.getOrDefault(item.source == DownloadSource.TORRENT)
        } else {
            val file = File(path)
            if (!file.exists()) return OpenResult.Unavailable("The saved file is no longer on this device")
            isDirectory = file.isDirectory
            uri = fileUri(file) ?: return OpenResult.Unavailable("This file cannot be shared")
        }
        val mime = if (isDirectory) FOLDER_MIME else (item.mimeType ?: mimeGuess(item.fileName))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return OpenResult.Ready(Intent.createChooser(intent, "Share").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun resolveView(
        context: android.content.Context,
        uri: Uri?,
        mime: String,
        displayName: String,
        isDirectory: Boolean
    ): OpenResult {
        if (uri == null) return OpenResult.Unavailable("This file could not be shared")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvable = runCatching {
            if (intent.resolveActivity(context.packageManager) != null) {
                true
            } else {
                // Some file managers only respond without an explicit MIME type.
                val relaxed = Intent(Intent.ACTION_VIEW).apply {
                    setData(uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                relaxed.resolveActivity(context.packageManager) != null
            }
        }.getOrDefault(false)
        if (resolvable) return OpenResult.Ready(intent)
        return OpenResult.Unavailable(
            if (isDirectory) {
                "No app can open this folder"
            } else {
                "No app on this device can open $displayName"
            }
        )
    }

    private fun fileUri(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.files",
            file
        )
    }.getOrNull()

    private fun mimeGuess(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "mp3" -> "audio/mpeg"
            "m4a", "m4b" -> "audio/mp4"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "txt" -> "text/plain"
            "srt", "vtt" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "apk" -> "application/vnd.android.package-archive"
            "torrent" -> "application/x-bittorrent"
            else -> "application/octet-stream"
        }
    }

    /** Largest video/audio file in a torrent payload, used for open and preview. */
    private fun largestPlayableFile(directory: File): File? =
        directory.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val extension = file.extension.lowercase()
                extension in PLAYABLE_EXTENSIONS
            }
            .maxByOrNull { it.length() }
    /** Starts the resolved open/open-with intent, or explains why it cannot. */
    fun launchOpen(item: DownloadEntity, open: Boolean) {
        val result = if (open) openFor(item) else openWithFor(item)
        startOrExplain(result)
    }

    fun launchShare(item: DownloadEntity) {
        startOrExplain(shareFor(item))
    }

    private fun startOrExplain(result: OpenResult) {
        when (result) {
            is OpenResult.Ready -> runCatching {
                getApplication<Application>().startActivity(result.intent)
            }.onFailure {
                viewModelScope.launch {
                    _events.emit(DownloadEvent.Message("Could not open that file"))
                }
            }
            is OpenResult.Unavailable -> viewModelScope.launch {
                _events.emit(DownloadEvent.Message(result.reason))
            }
        }
    }
    private fun sendAction(action: String, id: String) {
        DownloadService.action(getApplication(), action, id)
    }

    private fun requestFromTorrent(name: String, path: String): DownloadCreateRequest =
        DownloadCreateRequest(
            source = DownloadSource.TORRENT,
            url = path,
            fileName = name,
            category = null,
            torrentFilePath = path
        )
}

private fun matchesSearch(item: DownloadEntity, normalized: String): Boolean =
    normalized.isBlank() ||
        item.fileName.lowercase().contains(normalized) ||
        item.url.lowercase().contains(normalized)

private fun matchesStatus(item: DownloadEntity, filter: DownloadFilter): Boolean = when (filter) {
    DownloadFilter.ALL -> true
    DownloadFilter.ACTIVE -> item.status.isActiveCompat
    DownloadFilter.COMPLETED -> item.status == DownloadStatus.COMPLETED
}

private fun matchesCategory(item: DownloadEntity, category: DownloadCategory?): Boolean =
    category == null || item.category.matchesFilter(category)

fun List<DownloadEntity>.countCategories(
    predicate: (DownloadEntity) -> Boolean
): Map<DownloadCategory, Int> = filter(predicate)
    .groupingBy { it.category }
    .eachCount()

/**
 * A torrent arrives as one row whose type is unknown until it lands, so the
 * category is refined from the published payload (or the torrent name) once known.
 */
fun classifyTorrent(item: DownloadEntity): DownloadCategory {
    val output = item.outputPath
    if (!output.isNullOrBlank() && !output.startsWith("content:")) {
        val file = File(output)
        val payload = if (file.isDirectory) largestFileIn(file) else file.takeIf { it.isFile }
        if (payload != null) {
            val byExtension = LinkParser.categoryFor(DownloadSource.HTTP, payload.name, null)
            if (byExtension.isSpecific()) return byExtension
        }
    }
    val byName = LinkParser.categoryFor(DownloadSource.HTTP, item.fileName, item.mimeType)
    if (byName.isSpecific()) return byName
    return DownloadCategory.FILE
}

private fun DownloadCategory.isSpecific(): Boolean =
    this != DownloadCategory.FILE && this != DownloadCategory.OTHER

fun largestFileIn(directory: File): File? = runCatching {
    directory.walkTopDown().filter { it.isFile }.maxByOrNull { it.length() }
}.getOrNull()

private fun List<DownloadEntity>.toSummary() = TabSummary(
    active = count { it.status.isActiveCompat },
    paused = count { it.status == DownloadStatus.PAUSED },
    completed = count { it.status == DownloadStatus.COMPLETED },
    total = size
)

/** A "Compressed" chip should also catch rows that were stored as ARCHIVE. */
private fun DownloadCategory.matchesFilter(filter: DownloadCategory): Boolean = when (filter) {
    DownloadCategory.COMPRESSED -> this == DownloadCategory.COMPRESSED || this == DownloadCategory.ARCHIVE
    DownloadCategory.ARCHIVE -> this == DownloadCategory.ARCHIVE || this == DownloadCategory.COMPRESSED
    else -> this == filter
}

private val PLAYABLE_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "3gp", "ts", "mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")
private const val FOLDER_MIME = "resource/folder"

/**
 * yt-dlp publishes releases often, so the automatic check runs at most once a day.
 * A failed check is deliberately not recorded, so a network blip retries on the
 * next launch instead of being remembered for a whole day.
 */
private const val YTDLP_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

private val DownloadStatus.isActiveCompat: Boolean
    get() = this == com.downloadhub.app.data.model.DownloadStatus.QUEUED ||
        this == com.downloadhub.app.data.model.DownloadStatus.RESOLVING ||
        this == com.downloadhub.app.data.model.DownloadStatus.RUNNING

private fun android.content.ContentResolver.displayName(uri: Uri): String? {
    val cursor: Cursor? = query(uri, null, null, null, null)
    return cursor?.use {
        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) it.getString(index) else null
    }
}
