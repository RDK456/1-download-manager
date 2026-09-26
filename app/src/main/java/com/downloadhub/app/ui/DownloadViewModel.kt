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
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.AudioFormat
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadCreateRequest
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.MediaQuality
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.download.DownloadService
import com.downloadhub.app.download.LinkParser
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

    val allDownloads: StateFlow<List<DownloadEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val destinationTreeUri: StateFlow<String?> = settings.destinationTreeUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _downloaderVersion = MutableStateFlow(
        app.container.youtubeDownloader.currentVersion() ?: "Bundled"
    )
    val downloaderVersion: StateFlow<String> = _downloaderVersion

    private val _ytdlpUpdate = MutableStateFlow<YtDlpUpdateState>(YtDlpUpdateState.Idle)
    val ytdlpUpdate: StateFlow<YtDlpUpdateState> = _ytdlpUpdate.asStateFlow()

    /** Read-only check of the newest stable yt-dlp; installs nothing. */
    fun checkYtDlpUpdate() {
        if (_ytdlpUpdate.value is YtDlpUpdateState.Checking) return
        viewModelScope.launch {
            _ytdlpUpdate.value = YtDlpUpdateState.Checking
            val installed = app.container.youtubeDownloader.currentVersion()
            val latest = runCatching {
                app.container.youtubeDownloader.latestStableVersion()
            }.getOrNull()
            _ytdlpUpdate.value = when {
                latest.isNullOrBlank() ->
                    YtDlpUpdateState.Failed("Could not reach the yt-dlp release feed")
                compareVersions(latest, installed) > 0 ->
                    YtDlpUpdateState.Available(installed, latest)
                else -> YtDlpUpdateState.UpToDate(installed)
            }
        }
    }

    /** Installs the newer yt-dlp and refreshes the reported version. */
    fun applyYtDlpUpdate() {
        val target = (_ytdlpUpdate.value as? YtDlpUpdateState.Available)?.latest ?: return
        viewModelScope.launch {
            _ytdlpUpdate.value = YtDlpUpdateState.Checking
            runCatching {
                app.container.youtubeDownloader.updateYtDlpIfNeeded(force = true)
            }.onSuccess { version ->
                val installed = version ?: app.container.youtubeDownloader.currentVersion()
                _downloaderVersion.value = installed
                _ytdlpUpdate.value = if (compareVersions(target, installed) > 0) {
                    YtDlpUpdateState.Available(installed, target)
                } else {
                    YtDlpUpdateState.UpToDate(installed)
                }
                _events.emit(DownloadEvent.Message("yt-dlp updated to $installed"))
            }.onFailure { error ->
                _ytdlpUpdate.value = YtDlpUpdateState.Failed(
                    error.message ?: "yt-dlp update failed"
                )
            }
        }
    }

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(DownloadFilter.ALL)
    private val categoryFilter = MutableStateFlow<DownloadCategory?>(null)
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _editorSeed = MutableStateFlow<EditorSeed?>(null)
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val selectedId: StateFlow<String?> = _selectedId
    val editorSeed: StateFlow<EditorSeed?> = _editorSeed
    val currentFilter: StateFlow<DownloadFilter> = filter
    val currentCategory: StateFlow<DownloadCategory?> = categoryFilter
    val searchQuery: StateFlow<String> = query

    /**
     * The Downloads tab never shows torrents: magnet and .torrent transfers live
     * exclusively in the Torrents tab.
     */
    val visibleDownloads: StateFlow<List<DownloadEntity>> = combine(
        allDownloads,
        query,
        filter,
        categoryFilter
    ) { items, search, selectedFilter, selectedCategory ->
        val normalized = search.trim().lowercase()
        items.filter { item ->
            item.source != DownloadSource.TORRENT &&
                matchesSearch(item, normalized) &&
                matchesStatus(item, selectedFilter) &&
                matchesCategory(item, selectedCategory)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visibleTorrents: StateFlow<List<DownloadEntity>> = combine(
        allDownloads,
        query,
        filter
    ) { items, search, selectedFilter ->
        val normalized = search.trim().lowercase()
        items.filter { item ->
            item.source == DownloadSource.TORRENT &&
                matchesSearch(item, normalized) &&
                matchesStatus(item, selectedFilter)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mainSummary: StateFlow<TabSummary> = allDownloads
        .map { list -> list.filter { it.source != DownloadSource.TORRENT }.toSummary() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabSummary())

    val torrentSummary: StateFlow<TabSummary> = allDownloads
        .map { list -> list.filter { it.source == DownloadSource.TORRENT }.toSummary() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TabSummary())

    /** Category counts for the filter sheet, based on the Downloads tab. */
    val categoryCounts: StateFlow<Map<DownloadCategory, Int>> = allDownloads
        .map { list ->
            list.filter { it.source != DownloadSource.TORRENT }
                .groupingBy { it.category }
                .eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val selectedDownload: StateFlow<DownloadEntity?> = combine(allDownloads, _selectedId) { items, id ->
        items.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFilter(value: DownloadFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategoryFilter(value: DownloadCategory?) {
        categoryFilter.value = value
    }

    fun resetFilters() {
        filter.value = DownloadFilter.ALL
        categoryFilter.value = null
    }

    val hasActiveFilter: StateFlow<Boolean> = combine(filter, categoryFilter) { status, category ->
        status != DownloadFilter.ALL || category != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    fun addLink(
        rawLink: String,
        fileName: String? = null,
        category: DownloadCategory? = null,
        sourceOverride: DownloadSource? = null,
        userAgent: String? = null,
        contentDisposition: String? = null,
        quality: MediaQuality? = null,
        audioFormat: AudioFormat? = null
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
                }
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
    fun resume(id: String) = sendAction(DownloadService.ACTION_RESUME, id)
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

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDestinationTreeUri(uri: String?) {
        viewModelScope.launch { settings.setDestinationTreeUri(uri) }
    }

    fun pauseAll() = DownloadService.action(getApplication(), DownloadService.ACTION_PAUSE_ALL)
    fun resumeAll() = DownloadService.action(getApplication(), DownloadService.ACTION_RESUME_ALL)

    fun intentFor(item: DownloadEntity, share: Boolean = false): Intent? {
        val path = item.outputPath ?: return null
        val uri: Uri
        val isDirectory: Boolean
        if (path.startsWith("content:")) {
            uri = Uri.parse(path)
            isDirectory = runCatching {
                DocumentFile.fromSingleUri(getApplication<Application>(), uri)?.isDirectory == true
            }.getOrDefault(item.source == DownloadSource.TORRENT)
        } else {
            val file = File(path)
            if (!file.exists()) return null
            isDirectory = file.isDirectory
            uri = runCatching {
                FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.files",
                    file
                )
            }.getOrNull() ?: return null
        }
        val mime = if (isDirectory) "resource/folder" else item.mimeType ?: "application/octet-stream"
        return if (share) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
