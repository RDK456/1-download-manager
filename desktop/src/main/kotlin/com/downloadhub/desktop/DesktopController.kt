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

    private val _ui = MutableStateFlow(DesktopUiState())
    val ui: StateFlow<DesktopUiState> = _ui.asStateFlow()

    init {
        // Make the bundled binaries available in a writable location on first run.
        scope.launch { tools.install() }
        refresh()
        engine.pump()
    }

    fun start() = engine.pump()

    private fun refresh() {
        _ui.value = DesktopUiState(
            items = store.snapshot(),
            settings = settingsState.value,
            busyCount = engine.busy.value,
            ytDlpStatus = tools.statusText(),
            palette = DarkPalette
        )
    }

    val actions: DesktopActions = DesktopActions(
        addDownload = ::addDownload,
        pause = { engine.pause(it) },
        resume = { engine.resume(it) },
        retry = { engine.retry(it) },
        remove = { id ->
            engine.remove(id)
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
    fun addDownload(link: String, audioOnly: Boolean, format: String, height: Int?, playlist: Boolean) {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return
        val source = com.downloadhub.core.LinkParser.sourceFor(trimmed)
        val id = UUID.randomUUID().toString()
        val name = com.downloadhub.core.LinkParser.fileNameFrom(trimmed)

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
            store.update(id) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "Torrents are not available in this build yet"
                )
            }
            refresh()
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
            val published = area.publishFile(first, first.name, null)
            result.producedFiles.filter { it != first }.forEach { it.delete() }
            store.update(id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    location = published.location,
                    bytesDownloaded = first.length(),
                    totalBytes = first.length(),
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
        engine.close()
    }
}

private fun exitProcess(code: Int) {
    kotlin.system.exitProcess(code)
}
