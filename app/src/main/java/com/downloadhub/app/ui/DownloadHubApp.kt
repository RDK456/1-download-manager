package com.downloadhub.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import com.downloadhub.app.BuildConfig
import com.downloadhub.app.R
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.data.model.label
import com.downloadhub.app.update.YtDlpUpdateState
import com.downloadhub.app.ui.theme.DownloadHubTheme
import kotlinx.coroutines.flow.collectLatest

private enum class AppDestination {
    DOWNLOADS,
    TORRENTS,
    SETTINGS,
    ABOUT
}

private const val APP_TITLE = "1 download manager"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHubApp(
    viewModel: DownloadViewModel,
    updateViewModel: AppUpdateViewModel,
    incomingLink: String?,
    incomingDownloadId: String?,
    onIncomingConsumed: () -> Unit
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    DownloadHubTheme(themeMode) {
        val context = LocalContext.current
        val visibleItems by viewModel.visibleDownloads.collectAsStateWithLifecycle()
        val visibleTorrents by viewModel.visibleTorrents.collectAsStateWithLifecycle()
        val mainSummary by viewModel.mainSummary.collectAsStateWithLifecycle()
        val torrentSummary by viewModel.torrentSummary.collectAsStateWithLifecycle()
        val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
        val hasActiveFilter by viewModel.hasActiveFilter.collectAsStateWithLifecycle()
        val selectedItem by viewModel.selectedDownload.collectAsStateWithLifecycle()
        val editorSeed by viewModel.editorSeed.collectAsStateWithLifecycle()
        val filter by viewModel.currentFilter.collectAsStateWithLifecycle()
        val category by viewModel.currentCategory.collectAsStateWithLifecycle()
        var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
        val query by viewModel.searchQuery.collectAsStateWithLifecycle()
        val destinationTreeUri by viewModel.destinationTreeUri.collectAsStateWithLifecycle()
        val downloaderVersion by viewModel.downloaderVersion.collectAsStateWithLifecycle()
        val ytdlpUpdate by viewModel.ytdlpUpdate.collectAsStateWithLifecycle()
        val update by updateViewModel.snapshot.collectAsStateWithLifecycle()
        val autoCheckUpdates by updateViewModel.autoCheckUpdates.collectAsStateWithLifecycle()
        val updateMessage by updateViewModel.messages.collectAsStateWithLifecycle()
        var rootDestination by remember { mutableStateOf(AppDestination.DOWNLOADS) }
        var destination by remember { mutableStateOf(AppDestination.DOWNLOADS) }
        var dismissedRelease by rememberSaveable { mutableStateOf<String?>(null) }
        val snackbarHostState = remember { SnackbarHostState() }

        fun navigate(target: AppDestination) {
            if (target == AppDestination.DOWNLOADS || target == AppDestination.TORRENTS) {
                rootDestination = target
            }
            destination = target
        }

        val crumbs: List<Breadcrumb> = when (destination) {
            AppDestination.DOWNLOADS, AppDestination.TORRENTS -> listOf(
                Breadcrumb(APP_TITLE) { navigate(rootDestination) },
                Breadcrumb(destinationTitle(destination))
            )
            AppDestination.SETTINGS -> listOf(
                Breadcrumb(APP_TITLE) { navigate(rootDestination) },
                Breadcrumb("Settings")
            )
            AppDestination.ABOUT -> listOf(
                Breadcrumb(APP_TITLE) { navigate(rootDestination) },
                Breadcrumb("Settings") { navigate(AppDestination.SETTINGS) },
                Breadcrumb("About us")
            )
        }

        BackHandler(enabled = destination == AppDestination.ABOUT || destination == AppDestination.SETTINGS) {
            if (destination == AppDestination.ABOUT) {
                navigate(AppDestination.SETTINGS)
            } else {
                navigate(rootDestination)
            }
        }

        fun openExternal(url: String) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        Uri.parse(url)
                    )
                )
            }
        }

        LaunchedEffect(viewModel) {
            viewModel.events.collectLatest { event ->
                when (event) {
                    is DownloadEvent.Message -> snackbarHostState.showSnackbar(event.text)
                }
            }
        }
        LaunchedEffect(updateMessage) {
            updateMessage?.let { message ->
                snackbarHostState.showSnackbar(message)
                updateViewModel.consumeMessage()
            }
        }
        LaunchedEffect(Unit) {
            updateViewModel.checkOnLaunch()
            updateViewModel.refreshInstallPermission()
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
                TopAppBar(
                    title = { Breadcrumbs(crumbs) },
                    navigationIcon = {
                        // Adding a download is the floating action button's job, so the
                        // root tabs show the app mark instead of a second add button.
                        when (destination) {
                            AppDestination.DOWNLOADS, AppDestination.TORRENTS -> {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(28.dp)
                                )
                            }
                            else -> {
                                IconButton(onClick = {
                                    if (destination == AppDestination.ABOUT) {
                                        navigate(AppDestination.SETTINGS)
                                    } else {
                                        navigate(rootDestination)
                                    }
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        }
                    },
                    actions = {
                        if (destination == AppDestination.DOWNLOADS || destination == AppDestination.TORRENTS) {
                            BadgedBox(
                                badge = {
                                    if (hasActiveFilter) {
                                        Badge { Text(" ") }
                                    }
                                }
                            ) {
                                IconButton(onClick = { filterSheetOpen = true }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_filter),
                                        contentDescription = "Filters"
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { openExternal(BuildConfig.GITHUB_URL) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = "Open GitHub repository"
                            )
                        }
                        if (destination != AppDestination.SETTINGS && destination != AppDestination.ABOUT) {
                            IconButton(onClick = { navigate(AppDestination.SETTINGS) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (destination != AppDestination.ABOUT) {
                    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                        NavigationBarItem(
                            selected = destination == AppDestination.DOWNLOADS,
                            onClick = { navigate(AppDestination.DOWNLOADS) },
                            icon = { Icon(Icons.Default.Download, contentDescription = null) },
                            label = { Text("Downloads") }
                        )
                        NavigationBarItem(
                            selected = destination == AppDestination.TORRENTS,
                            onClick = { navigate(AppDestination.TORRENTS) },
                            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                            label = { Text("Torrents") }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (destination == AppDestination.DOWNLOADS || destination == AppDestination.TORRENTS) {
                    FloatingActionButton(onClick = { viewModel.openEditor() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add download")
                    }
                }
            },            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        summary = mainSummary,
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
                        items = visibleTorrents,
                        summary = torrentSummary,
                        filter = filter,
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
                        emptyAction = "Add a magnet link or open a .torrent file",
                        showTorrentAction = true,
                        onPickTorrent = viewModel::addTorrentFile
                    )
                    AppDestination.SETTINGS -> SettingsScreen(
                        themeMode = themeMode,
                        destinationTreeUri = destinationTreeUri,
                        downloaderVersion = downloaderVersion,
                        appVersion = updateViewModel.currentVersion,
                        update = update,
                        ytdlpUpdate = ytdlpUpdate,
                        onThemeChange = viewModel::setTheme,
                        onDestinationChange = viewModel::setDestinationTreeUri,
                        onCheckYtDlp = viewModel::checkYtDlpUpdate,
                        onApplyYtDlp = viewModel::applyYtDlpUpdate,
                        onAbout = { navigate(AppDestination.ABOUT) },
                        onCheckUpdates = updateViewModel::checkForUpdatesNow
                    )
                    AppDestination.ABOUT -> AboutScreen(
                        appVersion = updateViewModel.currentVersion,
                        versionCode = updateViewModel.currentVersionCode,
                        ytdlpVersion = downloaderVersion,
                        repoUrl = BuildConfig.GITHUB_URL,
                        update = update,
                        autoCheckUpdates = autoCheckUpdates,
                        onAutoCheckChange = updateViewModel::setAutoCheck,
                        onCheckUpdates = updateViewModel::checkForUpdatesNow,
                        onDownloadUpdate = updateViewModel::downloadUpdate,
                        onInstallUpdate = { updateViewModel.installUpdate(context) },
                        onOpenRepo = { openExternal(BuildConfig.GITHUB_URL) },
                        onOpenUrl = ::openExternal
                    )
                }
            }
        }

        editorSeed?.let { seed ->
            AddDownloadSheet(
                seed = seed,
                allowTorrentFile = destination == AppDestination.TORRENTS,
                onDismiss = viewModel::closeEditor,
                onAdd = viewModel::addLink,
                onPickTorrent = viewModel::addTorrentFile
            )
        }
        if (filterSheetOpen) {
            FilterSheet(
                filter = filter,
                category = category,
                categoryCounts = categoryCounts,
                showCategories = destination == AppDestination.DOWNLOADS,
                onFilterChange = viewModel::setFilter,
                onCategoryChange = viewModel::setCategoryFilter,
                onReset = viewModel::resetFilters,
                onDismiss = { filterSheetOpen = false }
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

        // The "update available" dialog is shown once per version; dismissing it
        // leaves the offer on the Settings and About pages instead of nagging.
        val availableRelease = (update.status as? UpdateStatus.Available)?.release
        val showUpdateDialog = availableRelease != null && dismissedRelease != availableRelease.version
        if (availableRelease != null && showUpdateDialog) {
            UpdateAvailableDialog(
                release = availableRelease,
                currentVersion = updateViewModel.currentVersion,
                onDownload = updateViewModel::downloadUpdate,
                onSkip = updateViewModel::skipVersion,
                onDismiss = {
                    dismissedRelease = availableRelease.version
                    updateViewModel.dismiss()
                }
            )
        } else if (update.progress != null || update.pending != null) {
            UpdateFlowDialog(
                update = update,
                onInstall = { updateViewModel.installUpdate(context) },
                onAllowInstalls = { updateViewModel.openInstallPermissionSettings(context) },
                onDismiss = updateViewModel::dismiss
            )
        }
    }
}

@Composable
private fun DownloadsScreen(
    items: List<DownloadEntity>,
    summary: TabSummary,
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
    showTorrentAction: Boolean = false,
    onPickTorrent: (Uri) -> Unit
) {
    val torrentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPickTorrent) }
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryBand(summary.active, summary.completed, summary.total)
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            label = {
                Text(if (showTorrentAction) "Search torrents" else "Search downloads")
            },
            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
        )
        if (filter != DownloadFilter.ALL || category != null) {
            ActiveFilterRow(
                filter = filter,
                category = category,
                onFilterChange = onFilterChange,
                onCategoryChange = onCategoryChange
            )
        }
        if (summary.active > 0 || summary.paused > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (summary.active > 0) {
                    OutlinedButton(onClick = onPauseAll) {
                        Text("Pause all")
                    }
                }
                if (summary.paused > 0) {
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
                if (showTorrentAction) {
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

/**
 * Compact reminder of what the filter sheet currently applies. Tapping a chip
 * clears it; the filter button in the top bar opens the full sheet.
 */
@Composable
private fun ActiveFilterRow(
    filter: DownloadFilter,
    category: DownloadCategory?,
    onFilterChange: (DownloadFilter) -> Unit,
    onCategoryChange: (DownloadCategory?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (filter != DownloadFilter.ALL) {
            androidx.compose.material3.InputChip(
                selected = true,
                onClick = { onFilterChange(DownloadFilter.ALL) },
                label = { Text(filterLabel(filter)) },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Clear status filter",
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
        if (category != null) {
            androidx.compose.material3.InputChip(
                selected = true,
                onClick = { onCategoryChange(null) },
                label = { Text(category.label) },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Clear category filter",
                        modifier = Modifier.size(16.dp)
                    )
                }
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
    appVersion: String,
    update: UpdateSnapshot,
    ytdlpUpdate: YtDlpUpdateState,
    onThemeChange: (ThemeMode) -> Unit,
    onDestinationChange: (String?) -> Unit,
    onCheckYtDlp: () -> Unit,
    onApplyYtDlp: () -> Unit,
    onAbout: () -> Unit,
    onCheckUpdates: () -> Unit
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
            .verticalScroll(rememberScrollState())
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
            ytdlpSummary(downloaderVersion, ytdlpUpdate),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Checking is always available; installing is only offered when a newer
        // stable release actually exists.
        OutlinedButton(
            onClick = onCheckYtDlp,
            enabled = ytdlpUpdate !is YtDlpUpdateState.Checking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_system_update),
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (ytdlpUpdate) {
                    is YtDlpUpdateState.Checking -> "Checking yt-dlp…"
                    is YtDlpUpdateState.UpToDate -> "Check for update"
                    else -> "Check for update"
                }
            )
        }
        val ytdlpTarget = (ytdlpUpdate as? YtDlpUpdateState.Available)?.latest
        if (ytdlpTarget != null) {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onApplyYtDlp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_system_update),
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("Update yt-dlp to $ytdlpTarget")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("App updates", style = MaterialTheme.typography.titleMedium)
        Text(
            updateStatusText(update, appVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onCheckUpdates,
            enabled = update.status !is UpdateStatus.Checking && update.progress == null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_system_update),
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text("Check for updates")
        }
        Spacer(Modifier.height(8.dp))
        Text("About", style = MaterialTheme.typography.titleMedium)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAbout),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text("About 1 download manager", modifier = Modifier.weight(1f))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "Share links from other apps or paste one into the add sheet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun destinationTitle(destination: AppDestination): String = when (destination) {
    AppDestination.DOWNLOADS -> APP_TITLE
    AppDestination.TORRENTS -> "Torrents"
    AppDestination.SETTINGS -> "Settings"
    AppDestination.ABOUT -> "About us"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
}

/** One line describing the installed yt-dlp and the result of the last check. */
fun ytdlpSummary(installed: String, state: YtDlpUpdateState): String = when (state) {
    YtDlpUpdateState.Idle -> "yt-dlp $installed"
    YtDlpUpdateState.Checking -> "yt-dlp $installed - checking for a newer release…"
    is YtDlpUpdateState.UpToDate -> "yt-dlp $installed is up to date"
    is YtDlpUpdateState.Available -> "yt-dlp $installed - version ${state.latest} is available"
    is YtDlpUpdateState.Failed -> "yt-dlp $installed - ${state.message}"
}

private val DownloadStatus.isActiveUi: Boolean
    get() = this == DownloadStatus.QUEUED ||
        this == DownloadStatus.RESOLVING ||
        this == DownloadStatus.RUNNING
