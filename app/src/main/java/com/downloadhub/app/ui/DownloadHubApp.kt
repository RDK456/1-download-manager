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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.documentfile.provider.DocumentFile
import com.downloadhub.app.BuildConfig
import com.downloadhub.core.FreeCatalog
import com.downloadhub.core.LibraryKind
import com.downloadhub.app.R
import com.downloadhub.app.data.local.DownloadEntity
import com.downloadhub.app.data.model.DownloadCategory
import com.downloadhub.app.data.model.DownloadSource
import com.downloadhub.app.data.model.DownloadStatus
import com.downloadhub.app.data.model.ThemeMode
import com.downloadhub.app.data.model.label
import com.downloadhub.app.download.PageScanState
import com.downloadhub.app.download.youTubeVideoId
import com.downloadhub.app.update.YtDlpUpdateState
import com.downloadhub.app.ui.theme.DownloadHubTheme
import kotlinx.coroutines.flow.collectLatest

private const val APP_TITLE = "1 download manager"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHubApp(
    viewModel: DownloadViewModel,
    updateViewModel: AppUpdateViewModel,
    incomingLink: String?,
    incomingDownloadId: String?,
    /** True when the update notification was tapped; jumps to the update screen. */
    openUpdates: Boolean = false,
    onIncomingConsumed: () -> Unit,
    onUpdatesConsumed: () -> Unit = {}
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val queues by viewModel.queues.collectAsStateWithLifecycle()
    val advanced by viewModel.advanced.collectAsStateWithLifecycle()
    val rssFeeds by viewModel.rssFeeds.collectAsStateWithLifecycle()
    val rssRules by viewModel.rssRules.collectAsStateWithLifecycle()
    val rssItems by viewModel.rssItems.collectAsStateWithLifecycle()
    val rssRefreshing by viewModel.rssRefreshing.collectAsStateWithLifecycle()
    DownloadHubTheme(themeMode, appTheme) {
        val context = LocalContext.current
        val visibleItems by viewModel.visibleDownloads.collectAsStateWithLifecycle()
        val visibleTorrents by viewModel.visibleTorrents.collectAsStateWithLifecycle()
        // Unfiltered, so the YouTube section's dedup sees the whole queue rather
        // than just the rows the current filter shows.
        val allItems by viewModel.allDownloads.collectAsStateWithLifecycle()
        val knownYouTubeIds = remember(allItems) {
            allItems.asSequence()
                .filter { it.source == DownloadSource.YOUTUBE }
                .mapNotNull { youTubeVideoId(it.url) }
                .toSet()
        }
        val mainSummary by viewModel.mainSummary.collectAsStateWithLifecycle()
        val torrentSummary by viewModel.torrentSummary.collectAsStateWithLifecycle()
        val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val kind by viewModel.currentKind.collectAsStateWithLifecycle()
    val kindCounts by viewModel.kindCounts.collectAsStateWithLifecycle()
        val torrentCategoryCounts by viewModel.torrentCategoryCounts.collectAsStateWithLifecycle()
        val hasActiveFilter by viewModel.hasActiveFilter.collectAsStateWithLifecycle()
        val selectedItem by viewModel.selectedDownload.collectAsStateWithLifecycle()
        val editorSeed by viewModel.editorSeed.collectAsStateWithLifecycle()
        val youTubePrefill by viewModel.youTubePrefill.collectAsStateWithLifecycle()
        val filter by viewModel.currentFilter.collectAsStateWithLifecycle()
        val category by viewModel.currentCategory.collectAsStateWithLifecycle()
        var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
        val query by viewModel.searchQuery.collectAsStateWithLifecycle()
        val destinationTreeUri by viewModel.destinationTreeUri.collectAsStateWithLifecycle()
        val downloaderVersion by viewModel.downloaderVersion.collectAsStateWithLifecycle()
        val ytdlpUpdate by viewModel.ytdlpUpdate.collectAsStateWithLifecycle()
        val downloadSettings by viewModel.downloadSettings.collectAsStateWithLifecycle()
        val update by updateViewModel.snapshot.collectAsStateWithLifecycle()
        val autoCheckUpdates by updateViewModel.autoCheckUpdates.collectAsStateWithLifecycle()
        val updateMessage by updateViewModel.messages.collectAsStateWithLifecycle()
        // Navigation is a real stack, so back walks parent -> child in the order
        // the user came from. Flat "current page" state made the back gesture
        // close the app from Download settings and Themes, because the handler
        // only covered Settings and About.
        //
        // The stack is persisted as one string ("ROOT>CHILD>GRANDCHILD") so it
        // survives rotation and process death through the default saver.
        var navKey by rememberSaveable { mutableStateOf(AppDestination.DOWNLOADS.name) }
        // The current search, held above the destination switch.
        //
        // The screen keeps its own query, results and filter, but the state belonged to
        // the screen's place in the composition - so queuing a download navigated away,
        // the screen left the composition, and everything went with it. Coming back to
        // Search showed an empty box, which made the one thing this screen is for -
        // reading several results and choosing between them - impossible to do across a
        // single download. Holding it here survives navigation, and `remember` rather
        // than `rememberSaveable` is deliberate: a `Job` cannot be put in a bundle, and
        // a search worth keeping across a rotation is the recent one.
        val searchState = remember { SearchScreenState() }
        val nav = remember(navKey) { decodeNav(navKey) }
        val rootDestination = nav.root
        val destination = nav.current
        // Torrent screens are where a magnet's file list will be wanted soon, so the lookup
        // session starts here rather than at launch, which would cost battery for nothing.
        LaunchedEffect(destination) {
            if (destination == AppDestination.SEARCH || destination == AppDestination.TORRENTS) {
                com.downloadhub.core.TorrentMetadataReader.warmUp()
            }
        }
        var dismissedRelease by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmExit by rememberSaveable { mutableStateOf(false) }
        val snackbarHostState = remember { SnackbarHostState() }

        fun navigate(target: AppDestination) {
            navKey = encodeNav(nav.navigate(target))
        }

        // Always enabled so back is never swallowed: on a sub-page it pops the
        // stack, and on a root tab it asks before leaving the app.
        BackHandler {
            val parent = nav.back()
            if (parent == null) confirmExit = true else navKey = encodeNav(parent)
        }

        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("Close $APP_TITLE?") },
                text = {
                    Text(
                        if (mainSummary.active > 0) {
                            "${mainSummary.active} download(s) are still running. " +
                                "They will keep going after the app closes."
                        } else {
                            "You can reopen the app from your launcher at any time."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmExit = false
                        context.findActivity()?.finish()
                    }) { Text("Close") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmExit = false }) { Text("Keep open") }
                }
            )
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
            // yt-dlp updates itself; no button to press.
            viewModel.syncYtDlpInBackground()
        }
        LaunchedEffect(incomingLink) {
            incomingLink?.let { link ->
                viewModel.handleIncoming(link)
                onIncomingConsumed()
            }
        }
        LaunchedEffect(incomingDownloadId) {
            incomingDownloadId?.let {
                viewModel.select(it)
                onIncomingConsumed()
            }
        }
        LaunchedEffect(openUpdates) {
            if (!openUpdates) return@LaunchedEffect
            // From the "update ready" notification: land on About, which is where
            // the install hand-off lives.
            navigate(AppDestination.SETTINGS)
            navigate(AppDestination.ABOUT)
            onUpdatesConsumed()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(destinationTitle(destination)) },
                    navigationIcon = {
                        // Adding a download is the floating action button's job, so the
                        // root tabs show the app mark instead of a second add button.
                        when (destination) {
                            AppDestination.DOWNLOADS, AppDestination.TORRENTS, AppDestination.SEARCH, AppDestination.YOUTUBE -> {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(28.dp)
                                )
                            }
                            else -> {
                                // Same behaviour as the system back gesture: pop
                                // one level rather than jumping to a parent by hand.
                                IconButton(onClick = { nav.back()?.let { navKey = encodeNav(it) } }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            }
                        }
                    },
                    actions = {
                        if (destination != AppDestination.SETTINGS && destination != AppDestination.ABOUT && destination != AppDestination.DOWNLOAD_SETTINGS && destination != AppDestination.THEMES && destination != AppDestination.QUEUES && destination != AppDestination.ADVANCED && destination != AppDestination.RSS && !destination.isDiscover) {
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
                if (destination != AppDestination.SETTINGS && destination != AppDestination.ABOUT && destination != AppDestination.DOWNLOAD_SETTINGS && destination != AppDestination.THEMES && destination != AppDestination.QUEUES && destination != AppDestination.ADVANCED && destination != AppDestination.RSS) {
                    Column {
                    // Above the tabs on every screen while something is playing.
                    if (destination != AppDestination.PLAYER) MiniPlayer(onOpen = { navigate(AppDestination.PLAYER) })
                    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                        NavigationBarItem(
                            selected = destination == AppDestination.DOWNLOADS,
                            onClick = { navigate(AppDestination.DOWNLOADS) },
                            icon = { ActiveBadge(mainSummary.active) { Icon(Icons.Default.Download, contentDescription = null) } },
                            label = { Text("Downloads") }
                        )
                        NavigationBarItem(
                            selected = destination == AppDestination.TORRENTS,
                            onClick = { navigate(AppDestination.TORRENTS) },
                            icon = { ActiveBadge(torrentSummary.active) { Icon(Icons.Default.Folder, contentDescription = null) } },
                            label = { Text("Torrents") }
                        )
                        NavigationBarItem(
                            selected = destination == AppDestination.SEARCH,
                            onClick = { navigate(AppDestination.SEARCH) },
                            icon = { Icon(Icons.Default.Search, contentDescription = null) },
                            label = { Text("Search") }
                        )
                        NavigationBarItem(
                            selected = destination == AppDestination.YOUTUBE,
                            onClick = { navigate(AppDestination.YOUTUBE) },
                            icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                            label = { Text("YouTube") }
                        )
                        NavigationBarItem(
                            selected = destination.isDiscover,
                            onClick = { navigate(AppDestination.DISCOVER) },
                            icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                            label = { Text("Discover") }
                        )
                    }
                    }
                }
            },
            floatingActionButton = {
                if (destination != AppDestination.SETTINGS && destination != AppDestination.ABOUT && destination != AppDestination.DOWNLOAD_SETTINGS && destination != AppDestination.THEMES && destination != AppDestination.QUEUES && destination != AppDestination.ADVANCED && destination != AppDestination.RSS && !destination.isDiscover) {
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
                // A short crossfade between tabs, so switching reads as a change of place
                // rather than a flash.
                androidx.compose.animation.Crossfade(
                    targetState = destination,
                    animationSpec = androidx.compose.animation.core.tween(180),
                    label = "tab"
                ) { destination ->
                when (destination) {
                    AppDestination.DOWNLOADS -> DownloadsScreen(
                        items = visibleItems,
                        summary = mainSummary,
                        categoryCounts = categoryCounts,
                        loader = viewModel.thumbnailCache,
                        filter = filter,
                        category = category,
                        kind = kind,
                        kindCounts = kindCounts,
                        query = query,
                        onQueryChange = viewModel::setQuery,
                        onFilterChange = viewModel::setFilter,
                        onCategoryChange = viewModel::setCategoryFilter,
                        onKindChange = viewModel::setKindFilter,
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
                        categoryCounts = torrentCategoryCounts,
                        loader = viewModel.thumbnailCache,
                        filter = filter,
                        category = category,
                        kind = LibraryKind.TORRENT,
                        kindCounts = kindCounts,
                        query = query,
                        onQueryChange = viewModel::setQuery,
                        onFilterChange = viewModel::setFilter,
                        onCategoryChange = viewModel::setCategoryFilter,
                        // Never offered on this tab, so anything arriving here is the
                        // Torrents tab itself asking for torrents.
                        onKindChange = { viewModel.setKindFilter(it) },
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
                    AppDestination.SEARCH -> SearchScreen(
                        // The search itself is held above the destination switch, so
                        // queuing a download and coming back finds the same query and
                        // the same results rather than an empty box. Comparing one
                        // torrent against the next few is the whole use of this screen,
                        // and navigating to the downloads list to queue one used to end
                        // the comparison.
                        state = searchState,
                        // Straight into the add sheet, the same one a pasted magnet goes
                        // through, so a search result gets the file list and the folder
                        // picker without any of it being written twice.
                        onPick = { magnet ->
                            viewModel.prepareSearchResult(magnet)
                            navigate(AppDestination.DOWNLOADS)
                        }
                    )
                    AppDestination.YOUTUBE -> YouTubeScreen(
                        knownIds = knownYouTubeIds,
                        prefill = youTubePrefill,
                        onPrefillConsumed = viewModel::consumeYouTubePrefill,
                        onFetch = viewModel::fetchYouTubeListing,
                        onFetchFormats = viewModel::listYouTubeFormats,
                        onQueue = { entries, audioOnly, ceiling ->
                            viewModel.queueYouTubeEntries(entries, audioOnly, ceiling)
                            navigate(AppDestination.DOWNLOADS)
                        },
                        onAddExact = viewModel::addLink,
                        onDone = { navigate(AppDestination.DOWNLOADS) }
                    )
                    AppDestination.SETTINGS -> SettingsScreen(
                        themeMode = themeMode,
                        destinationTreeUri = destinationTreeUri,
                        downloaderVersion = downloaderVersion,
                        appVersion = updateViewModel.currentVersion,
                        update = update,
                        onThemeChange = viewModel::setTheme,
                        onDestinationChange = viewModel::setDestinationTreeUri,
                        onAbout = { navigate(AppDestination.ABOUT) },
                        onDownloadSettings = { navigate(AppDestination.DOWNLOAD_SETTINGS) },
                        onThemes = { navigate(AppDestination.THEMES) },
                        onQueues = { navigate(AppDestination.QUEUES) },
                        onAdvanced = { navigate(AppDestination.ADVANCED) },
                        onRss = { navigate(AppDestination.RSS) },
                        onCheckUpdates = updateViewModel::checkForUpdatesNow
                    )
                    AppDestination.DOWNLOAD_SETTINGS -> DownloadSettingsScreen(
                        settings = downloadSettings,
                        destinationTreeUri = destinationTreeUri,
                        isBatteryExempt = viewModel.isBatteryExempt(),
                        onMaxConcurrentChange = viewModel::setMaxConcurrent,
                        onConnectionsChange = viewModel::setConnectionsPerDownload,
                        onSpeedLimitChange = viewModel::setSpeedLimit,
                        onWifiOnlyChange = viewModel::setWifiOnly,
                        onAutoQueueChange = viewModel::setAutoQueueIncoming,
                        onMaxRetriesChange = viewModel::setMaxRetries,
                        onAutoRemoveChange = viewModel::setAutoRemoveCompleted,
                        onDestinationChange = viewModel::setDestinationTreeUri,
                        onRequestBatteryExemption = viewModel::requestBatteryExemption,
                        downloaderVersion = downloaderVersion,
                        ytdlpUpdate = ytdlpUpdate,
                        onRetryYtDlp = viewModel::retryYtDlpSync
                    )
                    AppDestination.DISCOVER -> DiscoverScreen(
                        tiles = discoverTiles(),
                        onOpen = { navigate(it) }
                    )
                    AppDestination.BOOKS, AppDestination.MOVIES, AppDestination.MUSIC -> {
                        val catalog = when (destination) {
                            AppDestination.MOVIES -> FreeCatalog.MOVIES
                            AppDestination.MUSIC -> FreeCatalog.MUSIC
                            else -> FreeCatalog.BOOKS
                        }
                        BooksScreen(
                            loader = viewModel.thumbnailCache,
                            catalog = catalog,
                            onDownload = { book, file ->
                                viewModel.addLink(
                                    file.url,
                                    com.downloadhub.core.BookSources.fileName(book, file),
                                    when (catalog) {
                                        FreeCatalog.MOVIES -> DownloadCategory.VIDEO
                                        FreeCatalog.MUSIC -> DownloadCategory.AUDIO
                                        FreeCatalog.BOOKS -> DownloadCategory.DOCUMENT
                                    }
                                )
                            }
                        )
                    }
                    AppDestination.TV -> TvScreen(
                        loader = viewModel.thumbnailCache,
                        onWatching = { navigate(AppDestination.PLAYER) }
                    )
                    AppDestination.ARCHIVE -> ArchiveScreen(
                        loader = viewModel.thumbnailCache,
                        onDownload = viewModel::addArchiveFiles
                    )
                    AppDestination.PLAYER -> PlayerScreen(
                        library = remember(allItems) { AppPlayer.libraryItems(allItems) }
                    )
                    AppDestination.RSS -> RssScreen(
                        feeds = rssFeeds,
                        rules = rssRules,
                        items = rssItems,
                        refreshing = rssRefreshing,
                        onSubscribe = viewModel::subscribeRss,
                        onRemoveFeed = viewModel::removeRssFeed,
                        onRefresh = viewModel::refreshRss,
                        onSaveRules = viewModel::saveRssRules,
                        onDownload = viewModel::downloadRssItem
                    )
                    AppDestination.ADVANCED -> AdvancedSettingsScreen(
                        settings = advanced,
                        onSave = viewModel::saveAdvanced,
                        onImportIpFilter = viewModel::importIpFilter
                    )
                    AppDestination.QUEUES -> QueuesScreen(
                        queues = queues,
                        onSave = viewModel::saveQueue,
                        onDelete = viewModel::deleteQueue,
                        onToggle = viewModel::setQueueStarted
                    )
                    AppDestination.THEMES -> ThemePickerScreen(
                        appTheme = appTheme,
                        themeMode = themeMode,
                        onThemeChange = viewModel::setAppTheme,
                        onModeChange = viewModel::setTheme
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
        }

        val scanState by viewModel.pageScan.collectAsStateWithLifecycle()
        editorSeed?.let { seed ->
            AddDownloadSheet(
                seed = seed,
                allowTorrentFile = destination == AppDestination.TORRENTS,
                scanning = scanState is PageScanState.Scanning,
                onDismiss = viewModel::closeEditor,
                onAdd = viewModel::addLink,
                onRequestOptions = viewModel::setPendingRequest,
                // A YouTube link in the sheet belongs to the YouTube tab: the
                // sheet closes, the tab opens with the link already loading.
                onOpenYouTubeTab = { link ->
                    viewModel.closeEditor()
                    viewModel.setYouTubePrefill(link)
                    navigate(AppDestination.YOUTUBE)
                },
                onPickTorrent = viewModel::addTorrentFile,
                onScanPage = viewModel::scanPageForMedia
            )
        }
        val torrentPreview by viewModel.torrentPreview.collectAsStateWithLifecycle()
        torrentPreview?.let { preview ->
            TorrentPreviewSheet(
                preview = preview,
                onConfirm = viewModel::confirmTorrentPreview,
                onDismiss = viewModel::dismissTorrentPreview,
                onRetry = viewModel::retryTorrentPreview
            )
        }
        if (scanState is PageScanState.Found || scanState is PageScanState.Failed) {
            MediaScanSheet(
                state = scanState,
                onPick = viewModel::addScannedMedia,
                onPickAll = viewModel::addAllScannedMedia,
                onRescan = { viewModel.scanPageForMedia(editorSeed?.link.orEmpty()) },
                onDismiss = viewModel::dismissPageScan
            )
        }
        if (filterSheetOpen) {
            FilterSheet(
                filter = filter,
                category = category,
                categoryCounts = categoryCounts,
                showCategories = true,
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
                onOpen = { viewModel.launchOpen(item, open = true) },
                onOpenWith = { viewModel.launchOpen(item, open = false) },
                onShare = { viewModel.launchShare(item) },
                trackersOf = { viewModel.torrentTrackers(item) },
                peersOf = { viewModel.torrentPeers(item) },
                contentOf = { viewModel.torrentContent(item) },
                onFilesWanted = { indices, wanted -> viewModel.setTorrentFilesWanted(item.id, indices, wanted) },
                onForceRecheck = { viewModel.forceRecheck(item) },
                onForceReannounce = { viewModel.forceReannounce(item) },
                queues = queues,
                onMoveToQueue = { viewModel.moveToQueue(item.id, it) },
                onSaveRequest = { viewModel.setItemRequest(item.id, it) }
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

/**
 * All, Torrents, YouTube and Downloads, as a row of chips.
 *
 * Always visible rather than hidden until something is chosen: the whole point of
 * the split is that the three kinds can be told apart at a glance, and a control
 * that only appears once it has been used is a control nobody finds. All is first
 * and stays the default, so the tab still opens on the entire queue.
 *
 * A kind with nothing in it is still shown, dimmed rather than hidden. A chip that
 * appears only once you have something of that kind is a chip you cannot press to go
 * there and be told the truth.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KindFilterRow(
    kind: LibraryKind,
    counts: Map<LibraryKind, Int>,
    onKindChange: (LibraryKind) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryKind.entries.forEach { option ->
            val count = counts[option] ?: 0
            val selected = option == kind
            FilterChip(
                selected = selected,
                onClick = { onKindChange(option) },
                enabled = option == LibraryKind.ALL || count > 0 || selected,
                label = {
                    Text(if (count > 0) "${option.label} $count" else option.label)
                }
            )
        }
    }
}

@Composable
private fun DownloadsScreen(
    items: List<DownloadEntity>,
    summary: TabSummary,
    categoryCounts: Map<DownloadCategory, Int>,
    loader: com.downloadhub.app.download.ThumbnailCache,
    filter: DownloadFilter,
    category: DownloadCategory?,
    kind: LibraryKind,
    kindCounts: Map<LibraryKind, Int>,
    query: String,
    onQueryChange: (String) -> Unit,
    onFilterChange: (DownloadFilter) -> Unit,
    onCategoryChange: (DownloadCategory?) -> Unit,
    onKindChange: (LibraryKind) -> Unit,
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
        SummaryBand(summary)
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
        // The kinds, on the Downloads tab only. The Torrents tab is already one kind,
        // and offering three ways to ask for torrents on a screen that is torrents is
        // a row of chips where one of them is always right.
        if (!showTorrentAction) {
            KindFilterRow(
                kind = kind,
                counts = kindCounts,
                onKindChange = onKindChange
            )
        }
        if (kind != LibraryKind.ALL || filter != DownloadFilter.ALL || category != null) {
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
                    // Slides into place on add, remove and re-sort instead of jumping.
                    Box(Modifier.animateItem()) {
                        DownloadCard(
                            item = item,
                            loader = loader,
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
}

/**
 * The numbers you open the list to find out, as a row of small cards: what is running,
 * how fast, what is waiting, what is done. Scrolls sideways on a narrow phone rather than
 * squeezing the numbers.
 */
@Composable
private fun SummaryBand(summary: TabSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(summary.active.toString(), "Active", Icons.Default.Download, MaterialTheme.colorScheme.primary)
        StatCard(com.downloadhub.core.DisplayFormat.speed(summary.speed), "Speed", Icons.Default.Speed, MaterialTheme.colorScheme.primary)
        StatCard(summary.queued.toString(), "Queued", Icons.Default.Pause, MaterialTheme.colorScheme.onSurfaceVariant)
        StatCard(summary.completed.toString(), "Completed", Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun StatCard(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = tint.copy(alpha = 0.14f)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(7.dp).size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
    onThemeChange: (ThemeMode) -> Unit,
    onDestinationChange: (String?) -> Unit,
    onAbout: () -> Unit,
    onDownloadSettings: () -> Unit,
    onThemes: () -> Unit,
    onQueues: () -> Unit = {},
    onAdvanced: () -> Unit = {},
    onRss: () -> Unit = {},
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
        SettingsSection("General") {
            SettingsNavRow(
                icon = Icons.Default.Download,
                title = "Download settings",
                subtitle = "Folder, simultaneous downloads, speed limit, retries"
            ) { onDownloadSettings() }
            SettingsNavRow(
                icon = Icons.Default.Palette,
                title = "Themes",
                subtitle = "Two-tone colour scheme, light or dark"
            ) { onThemes() }
            SettingsNavRow(
                icon = Icons.Default.Schedule,
                title = "Queues",
                subtitle = "Named queues that start and stop on a schedule"
            ) { onQueues() }
            SettingsNavRow(
                icon = Icons.Default.Tune,
                title = "Advanced",
                subtitle = "Categories, proxy, speed limits, BitTorrent, IP filter"
            ) { onAdvanced() }
            SettingsNavRow(
                icon = Icons.Default.RssFeed,
                title = "RSS feeds",
                subtitle = "Subscribe to feeds and download matching articles automatically"
            ) { onRss() }
            SettingsNavRow(
                icon = Icons.Default.Info,
                title = "About 1 download manager",
                subtitle = "Version, source code and updates"
            ) { onAbout() }
        }

        SettingsSection("App updates") {
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
            Text(
                "Share links from other apps or paste one into the add sheet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun destinationTitle(destination: AppDestination): String = when (destination) {
    AppDestination.DOWNLOADS -> APP_TITLE
    AppDestination.TORRENTS -> "Torrents"
    AppDestination.SEARCH -> "Search"
    AppDestination.YOUTUBE -> "YouTube"
    AppDestination.SETTINGS -> "Settings"
    AppDestination.DOWNLOAD_SETTINGS -> "Download settings"
    AppDestination.THEMES -> "Themes"
    AppDestination.QUEUES -> "Queues"
    AppDestination.ADVANCED -> "Advanced"
    AppDestination.RSS -> "RSS feeds"
    AppDestination.DISCOVER -> "Discover"
    AppDestination.BOOKS -> "Free books"
    AppDestination.MOVIES -> "Free movies"
    AppDestination.MUSIC -> "Free music"
    AppDestination.PLAYER -> "Player"
    AppDestination.TV -> "Free TV"
    AppDestination.ARCHIVE -> "Internet Archive"
    AppDestination.ABOUT -> "About us"
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

/** Unwraps the Activity behind a composable context, or null if there is none. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** How many are running, on the tab's icon, so it is visible from any screen. */
@Composable
private fun ActiveBadge(count: Int, icon: @Composable () -> Unit) {
    if (count <= 0) {
        icon()
        return
    }
    BadgedBox(badge = { Badge { Text(count.toString()) } }) { icon() }
}

/** What the Discover tab offers. */
private fun discoverTiles(): List<DiscoverTile> = listOf(
    DiscoverTile(
        "Free books",
        "Project Gutenberg, Standard Ebooks, Open Library, the Internet Archive and Wikisource",
        Icons.AutoMirrored.Filled.MenuBook,
        AppDestination.BOOKS
    ),
    DiscoverTile(
        "Free movies",
        "Public-domain films, silent films, cartoons and classic TV",
        Icons.Default.Movie,
        AppDestination.MOVIES
    ),
    DiscoverTile(
        "Free music",
        "Shareable live concerts, Creative Commons netlabels and LibriVox audiobooks",
        Icons.Default.MusicNote,
        AppDestination.MUSIC
    ),
    DiscoverTile(
        "Free TV",
        "Freely broadcast channels from around the world, by category or country",
        Icons.Default.LiveTv,
        AppDestination.TV
    ),
    DiscoverTile(
        "Internet Archive",
        "Browse all of archive.org - video, audio, books, software and apps - and download whole items in one tap",
        Icons.Default.CloudDownload,
        AppDestination.ARCHIVE
    ),
    DiscoverTile(
        "Player",
        "Your downloaded music and videos, with synced lyrics",
        Icons.Default.PlayCircle,
        AppDestination.PLAYER
    )
)
