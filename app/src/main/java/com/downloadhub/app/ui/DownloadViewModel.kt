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
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadCreateRequest
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.download.DownloadService
import com.downloadhub.app.download.LinkParser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DownloadFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    TORRENTS
}

data class EditorSeed(
    val link: String,
    val source: DownloadSource,
    val fileName: String? = null,
    val category: DownloadCategory? = null,
    val userAgent: String? = null,
    val contentDisposition: String? = null
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

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(DownloadFilter.ALL)
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _editorSeed = MutableStateFlow<EditorSeed?>(null)
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val selectedId: StateFlow<String?> = _selectedId
    val editorSeed: StateFlow<EditorSeed?> = _editorSeed
    val currentFilter: StateFlow<DownloadFilter> = filter
    val searchQuery: StateFlow<String> = query

    val visibleDownloads: StateFlow<List<DownloadEntity>> = combine(
        allDownloads,
        query,
        filter
    ) { items, search, selectedFilter ->
        val normalized = search.trim().lowercase()
        items.filter { item ->
            val matchesSearch = normalized.isBlank() ||
                item.fileName.lowercase().contains(normalized) ||
                item.url.lowercase().contains(normalized)
            val matchesFilter = when (selectedFilter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> item.status.isActiveCompat
                DownloadFilter.COMPLETED -> item.status == com.downloadhub.app.data.model.DownloadStatus.COMPLETED
                DownloadFilter.TORRENTS -> item.source == DownloadSource.TORRENT
            }
            matchesSearch && matchesFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedDownload: StateFlow<DownloadEntity?> = combine(allDownloads, _selectedId) { items, id ->
        items.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFilter(value: DownloadFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

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
        contentDisposition: String? = null
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
            val effectiveCategory = category ?: if (source == DownloadSource.YOUTUBE) {
                DownloadCategory.VIDEO
            } else {
                null
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
                contentDisposition = contentDisposition
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

    fun addTorrentFile(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.displayName(uri) ?: "download.torrent"
                    val inputDir = File(getApplication<Application>().filesDir, "torrent-inputs")
                    inputDir.mkdirs()
                    val target = File(inputDir, "${UUID.randomUUID()}.torrent")
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Could not read the selected torrent")
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

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDestinationTreeUri(uri: String?) {
        viewModelScope.launch { settings.setDestinationTreeUri(uri) }
    }

    fun updateDownloader() {
        viewModelScope.launch {
            runCatching {
                app.container.youtubeDownloader.updateYtDlpIfNeeded(force = true)
            }.onSuccess { version ->
                _downloaderVersion.value = version ?: "Bundled"
                _events.emit(DownloadEvent.Message("yt-dlp is up to date (${version ?: "bundled"})"))
            }.onFailure { error ->
                _events.emit(DownloadEvent.Message(error.message ?: "yt-dlp update failed"))
            }
        }
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
            category = DownloadCategory.ARCHIVE,
            torrentFilePath = path
        )
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
