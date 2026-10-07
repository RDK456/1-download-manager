package com.downloadhub.app.ui

import com.composables.icons.lucide.Play
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxHeight
import com.downloadhub.app.ui.theme.Dot
import com.composables.icons.lucide.List
import com.composables.icons.lucide.LayoutGrid
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.CloudDownload
import com.composables.icons.lucide.Compass
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Palette
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Rss
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Tv
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
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
import com.downloadhub.app.ui.theme.Mono
import com.downloadhub.app.ui.theme.inkPanel
import com.downloadhub.app.ui.theme.keycap
import com.downloadhub.app.ui.theme.screws
import com.downloadhub.app.ui.theme.Lcd
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
                    title = {
                        // Downloads wears the "1DM" plate in either layout, as the desktop does.
                        if (destination == AppDestination.DOWNLOADS) {
                            com.downloadhub.app.ui.theme.LcdPlate("1DM")
                        } else if (com.downloadhub.app.ui.theme.UiStyle.keys) {
                            com.downloadhub.app.ui.theme.LcdPlate(destinationTitle(destination).uppercase())
                        } else Text(
                            destinationTitle(destination).uppercase(),
                            fontFamily = Dot,
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        // Adding a download is the floating action button's job, so the
                        // root tabs show the app mark instead of a second add button.
                        when (destination) {
                            // Every bottom-bar tab is a root: no back arrow on any of them.
                            AppDestination.DOWNLOADS, AppDestination.TORRENTS, AppDestination.SEARCH, AppDestination.YOUTUBE, AppDestination.DISCOVER -> {
                                Image(
                                    painter = painterResource(R.drawable.ic_app_mark),
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
                                    Icon(Lucide.ArrowLeft, contentDescription = "Back")
                                }
                            }
                        }
                    },
                    actions = {
                        // The filter narrows the download list, so it is offered only where
                        // that list is; on Search and YouTube it did nothing visible.
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
                        if (destination != AppDestination.SETTINGS && destination != AppDestination.ABOUT) {
                            IconButton(onClick = { navigate(AppDestination.SETTINGS) }) {
                                Icon(Lucide.Settings, contentDescription = "Settings")
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
                    HardwareNavBar(
                        listOf(
                            NavTab("Downloads", Lucide.Download, destination == AppDestination.DOWNLOADS, mainSummary.active) { navigate(AppDestination.DOWNLOADS) },
                            NavTab("Torrents", Lucide.Folder, destination == AppDestination.TORRENTS, torrentSummary.active) { navigate(AppDestination.TORRENTS) },
                            NavTab("Search", Lucide.Search, destination == AppDestination.SEARCH) { navigate(AppDestination.SEARCH) },
                            NavTab("YouTube", Lucide.Film, destination == AppDestination.YOUTUBE) { navigate(AppDestination.YOUTUBE) },
                            NavTab("Discover", Lucide.Compass, destination.isDiscover) { navigate(AppDestination.DISCOVER) }
                        )
                    )
                    }
                }
            },
            floatingActionButton = {
                if (destination != AppDestination.SETTINGS && destination != AppDestination.ABOUT && destination != AppDestination.DOWNLOAD_SETTINGS && destination != AppDestination.THEMES && destination != AppDestination.QUEUES && destination != AppDestination.ADVANCED && destination != AppDestination.RSS && !destination.isDiscover) {
                    // The add button is the big key on the panel.
                    Box(
                        Modifier.keycap(onClick = { viewModel.openEditor() }).size(60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.Plus, contentDescription = "Add download", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
                    }
                }
            },            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            // Swipe sideways between the bottom-bar sections, in their bar order. Only on the
            // sections themselves: sub-pages keep back as the way out. Rows that scroll
            // sideways (the chips) take the drag first, so they still scroll.
            val tabs = listOf(AppDestination.DOWNLOADS, AppDestination.TORRENTS, AppDestination.SEARCH, AppDestination.YOUTUBE, AppDestination.DISCOVER)
            val tabIndex = tabs.indexOf(destination)
            val swipeTabs = if (tabIndex < 0) Modifier else Modifier.pointerInput(tabIndex) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onHorizontalDrag = { change, dx ->
                        travelled += dx
                        change.consume()
                    },
                    onDragEnd = {
                        val step = when {
                            travelled < -size.width / 4f -> 1
                            travelled > size.width / 4f -> -1
                            else -> 0
                        }
                        tabs.getOrNull(tabIndex + step)?.takeIf { step != 0 }?.let { navigate(it) }
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .then(swipeTabs)
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
                onAddBatch = viewModel::addBatch,
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
    // One line that scrolls, not a FlowRow: four chips wrapped one onto a second line by
    // itself, which read as a separate control.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryKind.entries.forEach { option ->
            val count = counts[option] ?: 0
            val selected = option == kind
            // Monochrome tabs: the chosen one is inverted ink, so the accent stays for the
            // in-progress / completed switch below rather than lighting two rows at once.
            val enabled = option == LibraryKind.ALL || count > 0 || selected
            val scheme = MaterialTheme.colorScheme
            val shape = MaterialTheme.shapes.small
            Row(
                Modifier
                    .height(40.dp)
                    .clip(shape)
                    .background(if (selected) scheme.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, scheme.onSurface.copy(alpha = if (selected) 1f else 0.3f), shape)
                    .clickable(enabled = enabled) { onKindChange(option) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ink = when {
                    selected -> scheme.surface
                    enabled -> scheme.onSurface
                    else -> scheme.onSurfaceVariant
                }
                Text(option.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(count.toString(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ink)
                }
            }
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
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryBand(summary, onPauseAll = onPauseAll, onResumeAll = onResumeAll)
        // List or grid, remembered, as a key beside the search box.
        val context = androidx.compose.ui.platform.LocalContext.current
        val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
        var showDone by rememberSaveable { mutableStateOf(false) }
        var grid by remember { mutableStateOf(prefs.getBoolean("downloads_grid", false)) }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.small,
            singleLine = true,
            label = {
                Text(if (showTorrentAction) "Search torrents" else "Search downloads")
            },
            leadingIcon = { Icon(Lucide.Search, contentDescription = null) },
            // The list filters as you type, so the keyboard's Search just puts it away.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() })
        )
        IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("downloads_grid", grid).apply() }, modifier = Modifier.size(52.dp)) {
            Icon(if (grid) Lucide.List else Lucide.LayoutGrid, contentDescription = if (grid) "Show as list" else "Show as grid")
        }
        }
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
        // Finished downloads get their own tab, so the main list is what is still moving
        // rather than a long tail of done things.
        val doneCount = items.count { it.status == DownloadStatus.COMPLETED }
        val shown = items.filter { (it.status == DownloadStatus.COMPLETED) == showDone }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentRow(
                options = listOf("IN PROGRESS ${items.size - doneCount}", "COMPLETED $doneCount"),
                selected = if (showDone) 1 else 0,
                onSelect = { showDone = it == 1 },
                modifier = Modifier.weight(1f)
            )
        }
        if (shown.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyDownloads(
                    modifier = Modifier.weight(1f),
                    title = when {
                        items.isEmpty() -> emptyTitle
                        showDone -> "Nothing finished yet"
                        else -> "Nothing in progress"
                    },
                    action = when {
                        items.isEmpty() -> emptyAction
                        showDone -> "Finished downloads land here."
                        else -> "Everything is done. Completed has the lot."
                    }
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
                        Icon(Lucide.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open a .torrent file")
                    }
                }
            }
        } else if (grid) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(156.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown.size, key = { shown[it].id }) { index ->
                    val item = shown[index]
                    Box(Modifier.animateItem()) {
                        DownloadTile(
                            item = item,
                            loader = loader,
                            onClick = { onSelect(item.id) },
                            onPause = { onPause(item.id) },
                            onResume = { onResume(item.id) },
                            onRetry = { onRetry(item.id) }
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { item ->
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
 * The numbers you open the list to find out, as four cards sharing the width: what is
 * running, how fast, what is waiting, what is done. They used to scroll sideways, which on
 * a phone cut the last card in half with nothing to say there was more.
 */
@Composable
private fun SummaryBand(summary: TabSummary, onPauseAll: () -> Unit = {}, onResumeAll: () -> Unit = {}) {
    val free = remember(summary.completed) {
        runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(-1L)
    }
    // The last 32 seconds of total speed, sampled once a second, for the trace.
    val speed by rememberUpdatedState(summary.speed)
    var history by remember { mutableStateOf(List(32) { 0L }) }
    LaunchedEffect(Unit) {
        while (true) {
            history = history.drop(1) + speed
            kotlinx.coroutines.delay(1000)
        }
    }
    // One instrument panel rather than four soft cards: the speed big, in the meter's
    // face, with its recent history as a row of LED columns, and the counts beside it
    // like the readouts on a piece of hardware.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .inkPanel()
            .screws()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // The status lights along the top, as on the desktop panel.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.downloadhub.app.ui.theme.StatusLed("RUN", summary.active > 0)
                com.downloadhub.app.ui.theme.StatusLed("QUE", summary.queued > 0)
                com.downloadhub.app.ui.theme.StatusLed("IDLE", summary.active == 0)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "↓ SPEED",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            // speed() is blank at zero; a meter always shows a number.
            Lcd(
                com.downloadhub.core.DisplayFormat.speed(summary.speed).ifEmpty { "0 B/s" },
                ghost = "888.8 MB/s",
                fontSize = 22.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            SpeedTrace(history, Modifier.fillMaxWidth().height(22.dp))
            // The panel's own keys: stop or start everything, where the speed is.
            if (summary.active > 0 || summary.paused > 0) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (summary.active > 0) PanelKey("PAUSE ALL", Lucide.Pause, accent = false, onClick = onPauseAll)
                    if (summary.paused > 0) PanelKey("RESUME ALL", Lucide.Play, accent = true, onClick = onResumeAll)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Readout("ACT", summary.active, MaterialTheme.colorScheme.primary)
            Readout("QUE", summary.queued, MaterialTheme.colorScheme.onSurface)
            Readout("DONE", summary.completed, MaterialTheme.colorScheme.onSurfaceVariant)
            // Free space where downloads land, as the desktop panel shows it.
            if (free >= 0) {
                Spacer(Modifier.height(6.dp))
                Text("FREE", fontFamily = Mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatBytes(free), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** One counter on the panel: a small caps tag and a zero-padded number, both in mono. */
@Composable
private fun Readout(tag: String, value: Int, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tag,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp)
        )
        Text(
            value.toString().padStart(2, '0'),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            color = tint
        )
    }
}

/**
 * Recent speed as LED columns, newest on the right, each scaled to the fastest second in
 * view. A column that is not lit still shows as a dim cell, so the meter has a shape even
 * when nothing is moving.
 */
@Composable
private fun SpeedTrace(samples: List<Long>, modifier: Modifier = Modifier) {
    val lit = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    androidx.compose.foundation.Canvas(modifier) {
        val peak = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val gap = 2.dp.toPx()
        val column = (size.width - gap * (samples.size - 1)) / samples.size
        val cells = 5
        val cellGap = 1.5.dp.toPx()
        val cell = (size.height - cellGap * (cells - 1)) / cells
        samples.forEachIndexed { i, sample ->
            val on = if (sample <= 0L) 0 else (sample.toFloat() / peak * cells).toInt().coerceIn(1, cells)
            for (c in 0 until cells) {
                val y = size.height - (c + 1) * cell - c * cellGap
                drawRect(
                    if (c < on) (if (com.downloadhub.app.ui.theme.UiEffects.enabled) lit.copy(alpha = 0.45f + 0.55f * (i + 1) / samples.size) else lit) else dim,
                    androidx.compose.ui.geometry.Offset(i * (column + gap), y),
                    androidx.compose.ui.geometry.Size(column, cell)
                )
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
        // A display with nothing tuned in: "NO SIGNAL" on the LCD, and a meter below it
        // sweeping like a tuner hunting for a station.
        val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "no-signal").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2400)),
            label = "sweep"
        )
        Lcd("NO SIGNAL", ghost = "888888888", fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        SignalBar(sweep, MaterialTheme.colorScheme.onSurfaceVariant, live = false, modifier = Modifier.width(200.dp).height(8.dp), segments = 20)
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
                icon = Lucide.Download,
                title = "Download settings",
                subtitle = "Folder, simultaneous downloads, speed limit, retries"
            ) { onDownloadSettings() }
            SettingsNavRow(
                icon = Lucide.Palette,
                title = "Themes",
                subtitle = "Two-tone colour scheme, light or dark"
            ) { onThemes() }
            SettingsNavRow(
                icon = Lucide.Clock,
                title = "Queues",
                subtitle = "Named queues that start and stop on a schedule"
            ) { onQueues() }
            SettingsNavRow(
                icon = Lucide.SlidersHorizontal,
                title = "Advanced",
                subtitle = "Categories, proxy, speed limits, BitTorrent, IP filter"
            ) { onAdvanced() }
            SettingsNavRow(
                icon = Lucide.Rss,
                title = "RSS feeds",
                subtitle = "Subscribe to feeds and download matching articles automatically"
            ) { onRss() }
            SettingsNavRow(
                icon = Lucide.Info,
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
        Lucide.BookOpen,
        AppDestination.BOOKS
    ),
    DiscoverTile(
        "Free movies",
        "Public-domain films, silent films, cartoons and classic TV",
        Lucide.Film,
        AppDestination.MOVIES
    ),
    DiscoverTile(
        "Free music",
        "Shareable live concerts, Creative Commons netlabels and LibriVox audiobooks",
        Lucide.Music,
        AppDestination.MUSIC
    ),
    DiscoverTile(
        "Free TV",
        "Freely broadcast channels from around the world, by category or country",
        Lucide.Tv,
        AppDestination.TV
    ),
    DiscoverTile(
        "Internet Archive",
        "Browse all of archive.org - video, audio, books, software and apps - and download whole items in one tap",
        Lucide.CloudDownload,
        AppDestination.ARCHIVE
    ),
    DiscoverTile(
        "Player",
        "Your downloaded music and videos, with synced lyrics",
        Lucide.CirclePlay,
        AppDestination.PLAYER
    )
)

/**
 * Two or three options as a row of keys that share one edge, like the mode buttons on a
 * piece of hardware: the chosen one is lit and pressed in, the others stand proud.
 */
@Composable
private fun SegmentRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), shape)
            .clip(shape)
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

/** One tab on the bottom rail. [active] is a count of running downloads, shown as a number. */
private class NavTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selected: Boolean,
    val active: Int = 0,
    val onClick: () -> Unit
)

/**
 * The bottom rail as the front of a device rather than a Material bar: a hairline across
 * the top, and over the chosen tab a lit LED where Material puts a pill. Labels are
 * printed in the mono face. A tab with running downloads shows the count beside its LED.
 */
@Composable
private fun HardwareNavBar(tabs: List<NavTab>) {
    val scheme = MaterialTheme.colorScheme
    if (com.downloadhub.app.ui.theme.UiStyle.keys) {
        KeyNavBar(tabs)
        return
    }
    Column(Modifier.fillMaxWidth().background(scheme.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.onSurface.copy(alpha = 0.18f)))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(66.dp)) {
            tabs.forEach { tab ->
                val tint = if (tab.selected) scheme.onSurface else scheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(onClickLabel = tab.label, onClick = tab.onClick)
                        .semantics { selected = tab.selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(10.dp)) {
                        Box(
                            Modifier
                                .size(width = 14.dp, height = 4.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                .background(if (tab.selected) scheme.primary else scheme.onSurface.copy(alpha = 0.12f))
                        )
                        if (tab.active > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text(tab.active.toString(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = scheme.primary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label.uppercase(),
                        fontFamily = Mono,
                        fontWeight = if (tab.selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.5.sp,
                        color = tint,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** A small labelled key on the instrument panel. */
@Composable
private fun PanelKey(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (accent) scheme.onPrimary else scheme.onSurface
    Row(
        Modifier
            .keycap(accent = accent, shape = MaterialTheme.shapes.small, depth = 3.dp, onClick = onClick)
            .height(40.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ink, maxLines = 1)
    }
}

/**
 * The KEYS style's bottom bar: every tab is its own key, raised off the chassis, with an
 * LED in its corner that lights in the accent on the chosen one. A tab with running
 * downloads shows the count next to its LED.
 */
@Composable
private fun KeyNavBar(tabs: List<NavTab>) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            Box(
                Modifier
                    .weight(1f)
                    .keycap(accent = false, shape = MaterialTheme.shapes.medium, depth = 3.dp, onClick = tab.onClick)
                    .background(if (tab.selected) scheme.surface else scheme.surfaceVariant)
                    .border(1.dp, scheme.onSurface.copy(alpha = 0.12f), MaterialTheme.shapes.medium)
                    .height(64.dp)
                    .semantics { selected = tab.selected }
            ) {
                Row(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tab.active > 0) {
                        Text(tab.active.toString(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = scheme.primary)
                        Spacer(Modifier.width(3.dp))
                    }
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (tab.selected) scheme.primary else scheme.onSurface.copy(alpha = 0.25f))
                    )
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(tab.icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label.uppercase(),
                        fontFamily = Mono,
                        fontWeight = if (tab.selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.sp,
                        color = scheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
