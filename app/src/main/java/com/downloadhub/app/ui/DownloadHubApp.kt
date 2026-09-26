package com.downloadhub.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.data.model.label
import com.downloadhub.app.ui.theme.DownloadHubTheme
import kotlinx.coroutines.flow.collectLatest

private enum class AppDestination {
    DOWNLOADS,
    TORRENTS,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHubApp(
    viewModel: DownloadViewModel,
    incomingLink: String?,
    incomingDownloadId: String?,
    onIncomingConsumed: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    DownloadHubTheme(themeMode) {
        val context = LocalContext.current
        val allItems by viewModel.allDownloads.collectAsStateWithLifecycle()
        val visibleItems by viewModel.visibleDownloads.collectAsStateWithLifecycle()
        val selectedItem by viewModel.selectedDownload.collectAsStateWithLifecycle()
        val editorSeed by viewModel.editorSeed.collectAsStateWithLifecycle()
        val filter by viewModel.currentFilter.collectAsStateWithLifecycle()
        val category by viewModel.currentCategory.collectAsStateWithLifecycle()
        val query by viewModel.searchQuery.collectAsStateWithLifecycle()
        val destinationTreeUri by viewModel.destinationTreeUri.collectAsStateWithLifecycle()
        val downloaderVersion by viewModel.downloaderVersion.collectAsStateWithLifecycle()
        var destination by remember { mutableStateOf(AppDestination.DOWNLOADS) }
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(viewModel) {
            viewModel.events.collectLatest { event ->
                when (event) {
                    is DownloadEvent.Message -> snackbarHostState.showSnackbar(event.text)
                }
            }
        }
        LaunchedEffect(incomingLink) {
            incomingLink?.let { link ->
                val source = com.downloadhub.app.download.LinkParser.sourceFor(link)
                viewModel.openEditor(EditorSeed(link = link, source = source))
                onIncomingConsumed()
            }
        }
        LaunchedEffect(incomingDownloadId) {
            incomingDownloadId?.let {
                viewModel.select(it)
                onIncomingConsumed()
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(destinationTitle(destination)) },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.openEditor() }) {
                            Icon(Icons.Default.Add, contentDescription = "Add download")
                        }
                    },
                    actions = {
                        IconButton(onClick = { destination = AppDestination.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    NavigationBarItem(
                        selected = destination == AppDestination.DOWNLOADS,
                        onClick = { destination = AppDestination.DOWNLOADS },
                        icon = { Icon(Icons.Default.Download, contentDescription = null) },
                        label = { Text("Downloads") }
                    )
                    NavigationBarItem(
                        selected = destination == AppDestination.TORRENTS,
                        onClick = { destination = AppDestination.TORRENTS },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label = { Text("Torrents") }
                    )
                    NavigationBarItem(
                        selected = destination == AppDestination.SETTINGS,
                        onClick = { destination = AppDestination.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            },
            floatingActionButton = {
                if (destination == AppDestination.DOWNLOADS || destination == AppDestination.TORRENTS) {
                    FloatingActionButton(onClick = { viewModel.openEditor() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add download")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (destination) {
                    AppDestination.DOWNLOADS -> DownloadsScreen(
                        items = visibleItems,
                        allItems = allItems,
                        filter = filter,
                        category = category,
                        query = query,
                        onQueryChange = viewModel::setQuery,
                        onFilterChange = viewModel::setFilter,
                        onCategoryChange = viewModel::setCategoryFilter,
                        onSelect = viewModel::select,
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onPauseAll = viewModel::pauseAll,
                        onResumeAll = viewModel::resumeAll,
                        onRetry = viewModel::retry,
                        onDelete = viewModel::delete,
                        onPickTorrent = viewModel::addTorrentFile
                    )
                    AppDestination.TORRENTS -> DownloadsScreen(
                        items = allItems.filter { it.source == DownloadSource.TORRENT },
                        allItems = allItems,
                        filter = DownloadFilter.TORRENTS,
                        category = null,
                        query = query,
                        onQueryChange = viewModel::setQuery,
                        onFilterChange = viewModel::setFilter,
                        onCategoryChange = viewModel::setCategoryFilter,
                        onSelect = viewModel::select,
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onPauseAll = viewModel::pauseAll,
                        onResumeAll = viewModel::resumeAll,
                        onRetry = viewModel::retry,
                        onDelete = viewModel::delete,
                        emptyTitle = "No torrents yet",
                        emptyAction = "Add torrent",
                        onPickTorrent = viewModel::addTorrentFile
                    )
                    AppDestination.SETTINGS -> SettingsScreen(
                        themeMode = themeMode,
                        destinationTreeUri = destinationTreeUri,
                        downloaderVersion = downloaderVersion,
                        onThemeChange = viewModel::setTheme,
                        onDestinationChange = viewModel::setDestinationTreeUri,
                        onUpdateDownloader = viewModel::updateDownloader
                    )
                }
            }
        }

        editorSeed?.let { seed ->
            AddDownloadSheet(
                seed = seed,
                onDismiss = viewModel::closeEditor,
                onAdd = viewModel::addLink,
                onPickTorrent = viewModel::addTorrentFile
            )
        }
        selectedItem?.let { item ->
            DownloadDetailsSheet(
                item = item,
                onDismiss = { viewModel.select(null) },
                onPause = { viewModel.pause(item.id) },
                onResume = { viewModel.resume(item.id) },
                onRetry = { viewModel.retry(item.id) },
                onDelete = { viewModel.delete(item.id) },
                onOpen = {
                    viewModel.intentFor(item)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                },
                onShare = {
                    viewModel.intentFor(item, share = true)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                }
            )
        }
    }
}

@Composable
private fun DownloadsScreen(
    items: List<DownloadEntity>,
    allItems: List<DownloadEntity>,
    filter: DownloadFilter,
    category: DownloadCategory?,
    query: String,
    onQueryChange: (String) -> Unit,
    onFilterChange: (DownloadFilter) -> Unit,
    onCategoryChange: (DownloadCategory?) -> Unit,
    onSelect: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    emptyTitle: String = "Your queue is empty",
    emptyAction: String = "Add a download",
    onPickTorrent: (Uri) -> Unit
) {
    val torrentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPickTorrent) }
    val activeCount = allItems.count { it.status.isActiveUi }
    val pausedCount = allItems.count { it.status == DownloadStatus.PAUSED }
    val completedCount = allItems.count { it.status == DownloadStatus.COMPLETED }
    val categoryCounts = remember(allItems) { allItems.groupingBy { it.category }.eachCount() }
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryBand(activeCount, completedCount, allItems.size)
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = { Text("Search downloads") },
            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
        )
        if (emptyTitle == "Your queue is empty") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(DownloadFilter.ALL, DownloadFilter.ACTIVE, DownloadFilter.COMPLETED).forEach { value ->
                    androidx.compose.material3.FilterChip(
                        selected = filter == value,
                        onClick = { onFilterChange(value) },
                        label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            CategoryFilterRow(
                selected = category,
                counts = categoryCounts,
                onSelect = onCategoryChange
            )
        }
        if (activeCount > 0 || pausedCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeCount > 0) {
                    OutlinedButton(onClick = onPauseAll) {
                        Text("Pause all")
                    }
                }
                if (pausedCount > 0) {
                    Button(onClick = onResumeAll) {
                        Text("Resume all")
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyDownloads(
                    modifier = Modifier.weight(1f),
                    title = emptyTitle,
                    action = emptyAction
                )
                OutlinedButton(
                    onClick = {
                        torrentPicker.launch(
                            arrayOf(
                                "application/x-bittorrent",
                                "application/vnd.torrent",
                                "application/octet-stream"
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open a .torrent file")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onClick = { onSelect(item.id) },
                        onPause = { onPause(item.id) },
                        onResume = { onResume(item.id) },
                        onRetry = { onRetry(item.id) },
                        onDelete = { onDelete(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryBand(active: Int, completed: Int, total: Int) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryValue(active, "Active")
            SummaryValue(completed, "Complete")
            SummaryValue(total, "Total")
        }
    }
}

@Composable
private fun SummaryValue(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryFilterRow(
    selected: DownloadCategory?,
    counts: Map<DownloadCategory, Int>,
    onSelect: (DownloadCategory?) -> Unit
) {
    val options = remember(counts) {
        listOf<DownloadCategory?>(null) + DownloadCategory.entries
            .filterNot { it == DownloadCategory.ARCHIVE }
            .filter { candidate ->
                val count = counts[candidate] ?: 0
                val legacy = if (candidate == DownloadCategory.COMPRESSED) {
                    counts[DownloadCategory.ARCHIVE] ?: 0
                } else {
                    0
                }
                count + legacy > 0 || candidate in setOf(
                    DownloadCategory.PROGRAM,
                    DownloadCategory.COMPRESSED,
                    DownloadCategory.FILE
                )
            }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            val label = option?.label ?: "All types"
            val count = if (option == null) {
                counts.values.sum()
            } else {
                (counts[option] ?: 0) +
                    if (option == DownloadCategory.COMPRESSED) counts[DownloadCategory.ARCHIVE] ?: 0 else 0
            }
            androidx.compose.material3.FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(if (count > 0) "$label ($count)" else label) }
            )
        }
    }
}

@Composable
private fun EmptyDownloads(
    title: String,
    action: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            action,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsScreen(
    themeMode: ThemeMode,
    destinationTreeUri: String?,
    downloaderVersion: String,
    onThemeChange: (ThemeMode) -> Unit,
    onDestinationChange: (String?) -> Unit,
    onUpdateDownloader: () -> Unit
) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            onDestinationChange(uri.toString())
        }
    }
    val folderName = destinationTreeUri?.let { uriString ->
        runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(uriString))?.name
        }.getOrNull()
    } ?: "Download/DownloadHub"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                androidx.compose.material3.FilterChip(
                    selected = mode == themeMode,
                    onClick = { onThemeChange(mode) },
                    label = { Text(themeLabel(mode)) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Download folder", style = MaterialTheme.typography.titleMedium)
        Text(
            folderName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose folder")
        }
        if (destinationTreeUri != null) {
            TextButton(
                onClick = { onDestinationChange(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Use default Download/DownloadHub")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("YouTube downloader", style = MaterialTheme.typography.titleMedium)
        Text(
            "yt-dlp $downloaderVersion",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onUpdateDownloader,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Update yt-dlp now")
        }
        Text(
            "Share links from other apps or paste one into the add sheet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun destinationTitle(destination: AppDestination): String = when (destination) {
    AppDestination.DOWNLOADS -> "1 download manager"
    AppDestination.TORRENTS -> "Torrents"
    AppDestination.SETTINGS -> "Settings"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
}

private val DownloadStatus.isActiveUi: Boolean
    get() = this == DownloadStatus.QUEUED ||
        this == DownloadStatus.RESOLVING ||
        this == DownloadStatus.RUNNING
