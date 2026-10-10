package com.downloadhub.desktop

import androidx.compose.runtime.rememberUpdatedState
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LayoutGrid
import com.composables.icons.lucide.Library
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.CirclePlay
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Rss
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Magnet
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.CirclePause
import com.composables.icons.lucide.CircleDashed
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Trash2
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import com.downloadhub.core.DownloadColumn
import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadLibrary
import java.awt.event.MouseEvent
import com.downloadhub.core.DownloadPriority
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import com.downloadhub.core.LibraryCategory
import com.downloadhub.core.FreeCatalog
import com.downloadhub.core.RailEntry
import com.downloadhub.core.railCount
import com.downloadhub.core.sidebarEntries
import com.downloadhub.core.LibraryGroup
import com.downloadhub.core.LibraryKind
import com.downloadhub.core.LibraryQuery
import com.downloadhub.core.LibrarySort
import com.downloadhub.core.SortDirection
import java.io.File
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs

/**
 * The Windows library window.
 *
 * Laid out the way a desktop download manager should be: a fixed category rail on
 * the left, a toolbar of icon-and-label actions, a sortable column table in the
 * middle and a live status bar along the bottom. All of the filtering, sorting and
 * counting comes from :core, so the phone and the desktop cannot disagree.
 *
 * The layout reads the window size so it can give things up in a defined order as the
 * window narrows, rather than clipping the right-hand end off.
 */
@Composable
fun LibraryScreen(
    state: DesktopUiState,
    actions: DesktopActions,
    onOpenAdd: () -> Unit,
    /**
     * Open the pre-download window on a link the search found.
     *
     * Its own callback rather than going through [onOpenAdd] because the search already
     * has a magnet and the add dialog starts by asking for one. Handing it over directly
     * is what makes a search result get the same file list, folder picker and stop
     * condition as a magnet pasted by hand.
     */
    onOpenAddForLink: (String) -> Unit,
    /**
     * A YouTube link the pre-download window handed over, loaded once by the
     * YouTube section and then forgotten via [onYouTubePrefillConsumed].
     */
    youTubePrefill: String? = null,
    onYouTubePrefillConsumed: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onQuit: () -> Unit
) {
    var category by remember { mutableStateOf(LibraryCategory.ALL) }
    var group by remember { mutableStateOf(LibraryGroup.ALL) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.RECENT) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // Set when Delete is pressed, so the dialog can ask what should happen to the
    // files rather than the app assuming.
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    /** Which download has its options dialog open, if any. */
    var optionsFor by remember { mutableStateOf<String?>(null) }
    /** Which download has its right-click menu open, if any. */
    var contextFor by remember { mutableStateOf<String?>(null) }
    /**
     * Where the pointer was when the menu was asked for.
     *
     * Read off the raw mouse event, which reports in the coordinates of the component it
     * was delivered to - so these are already inside the list's own box and the menu can
     * simply be offset by them. Screen coordinates would have to be converted back through
     * the window and clipped to it, and every one of those steps is a way to open a menu
     * somewhere the user cannot click.
     */
    var contextAt by remember { mutableStateOf(IntOffset.Zero) }
    /** Which download is being renamed. */
    var renaming by remember { mutableStateOf<String?>(null) }
    /** Which download is being given a different folder. */
    var relocating by remember { mutableStateOf<String?>(null) }
    /** Which bottom pane is open on the torrents tab. */
    var detailTab by remember { mutableStateOf(TorrentTab.GENERAL) }
    /**
     * Whether the search section is open.
     *
     * Its own flag rather than a rail entry in the query, because search is not a filter:
     * every other entry narrows what is already downloaded, and this replaces the list
     * with a box to type in.
     */
    var searchOpen by remember { mutableStateOf(false) }
    /**
     * The current search, held here rather than inside the search panel.
     *
     * Queueing a download from a result used to close the panel, and closing the panel
     * threw the query and every result away with it - so a person comparing releases
     * had to search again to see the list they had just been reading. Holding the
     * search beside the flag that shows it means the panel can be closed and reopened
     * without costing anything, and the [searchOpen] flag goes back to meaning only
     * whether it is on screen.
     */
    val searchState = remember { SearchPanelState() }
    /**
     * Whether the YouTube section is open.
     *
     * A second flag beside [searchOpen] rather than one shared "panel" state,
     * because the two panels hold unrelated work - a search and a pasted link -
     * and switching between them should not wipe either. Only one shows at a
     * time, and every rail row that is neither closes both.
     */
    var youTubeOpen by remember { mutableStateOf(false) }
    /** Whether the RSS panel is open; one of the three panels, like Search and YouTube. */
    var rssOpen by remember { mutableStateOf(false) }
    // Free books, free TV, the player: sections that replace the list, one at a time.
    var extraPanel by remember { mutableStateOf<RailEntry?>(null) }
    /**
     * Which kind of download is being looked at, apart from the Torrents tab.
     *
     * Its own flag rather than three more booleans, because the three kinds are one
     * choice and a choice with three parts made in one at a time is a way to end up
     * scoped to torrents and YouTube at once, which is not a list anyone asked for.
     */
    var kindFilter by remember { mutableStateOf(LibraryKind.ALL) }
    /** The queue the list is narrowed to, if a queue row was picked in the rail. */
    var queueFilter by remember { mutableStateOf<String?>(null) }
    /** The queue being created or edited, and whether it is new. */
    var editingQueue by remember { mutableStateOf<Pair<QueueConfig, Boolean>?>(null) }
    /** Downloads waiting for the "Move to queue" picker. */
    var moving by remember { mutableStateOf(emptySet<String>()) }
    var creatingTorrent by remember { mutableStateOf(false) }
    val queues = state.settings.queuesOrDefault

    // A handed-over link opens the section with the link already loading, then
    // is forgotten: later recompositions must not re-fetch it over what was
    // typed there since.
    LaunchedEffect(youTubePrefill) {
        if (!youTubePrefill.isNullOrBlank()) {
            searchOpen = false
            youTubeOpen = true
        }
    }
    /**
     * How tall the detail pane is, and who changed it.
     *
     * Saved rather than merely remembered, so a file list made tall enough to read is
     * still tall enough next time. A pane that resets to its default on every launch is a
     * pane that has to be resized on every launch, which is the same as not being
     * resizable.
     */
    var detailPaneHeight by rememberSaveable { mutableStateOf(PANE_DEFAULT_DP) }
    // Shut until a tab is clicked: selecting a row shows the tab strip and nothing more,
    // so the list keeps the window.
    var detailOpen by rememberSaveable { mutableStateOf(false) }
    /**
     * The column widths, kept for as long as the window is open.
     *
     * Deliberately not in settings. Widths are a property of how this particular window is
     * being used on this particular screen; persisting them means a resized column on a
     * large monitor silently narrows the same table on a laptop, and there is then no way
     * back to the default without editing a file.
     */
    var columnWidths by remember { mutableStateOf(ColumnWidths.DEFAULT) }
    /**
     * The width a column had when its handle was grabbed, and which column that was.
     *
     * Held so the drag can be a movement rather than a position. The width is read once,
     * at the press, and every move after that is added to it - which is why where the
     * pointer is in the window no longer has to be worked out at all.
     */
    var dragColumn by remember { mutableStateOf<com.downloadhub.core.DownloadColumn?>(null) }
    var dragStartWidth by remember { mutableFloatStateOf(0f) }

    // Remembered against the snapshot, so a recomposition for anything else - a hover, a
    // dialog - does not re-map and re-sort the whole queue.
    val all = remember(state.items) { state.items.map { it.toCoreItem() } }
    /**
     * Which kind of download the list is showing.
     *
     * All, torrents, YouTube, or ordinary downloads. The Torrents tab at the bottom
     * is the older way of asking the same question, so it is folded in here rather
     * than kept as a second flag: two flags meaning "torrents only" is how the list
     * ends up scoped to torrents while the rail says something else.
     */
    val kind = if (state.torrentsTab) LibraryKind.TORRENT else kindFilter
    val query = LibraryQuery(
        category = category,
        group = group,
        search = search,
        kind = kind,
        sort = sort,
        queueId = queueFilter
    )
    val visible = remember(all, query) { DownloadLibrary.visible(all, query) }

    // A message answers one action, so it goes away: after a few seconds, or as soon as
    // the selection it was about changes. It used to stay in the status bar for good,
    // reading as if it described whatever was selected next.
    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(MESSAGE_SHOWN_MILLIS)
            actions.consumeMessage()
        }
    }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { selected }
            .drop(1)
            .collect { actions.consumeMessage() }
    }

    // Keys bubble here from whatever has focus; a text field consumes its own Delete and
    // Ctrl+A first, so typing in the search box never removes a download.
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    event.isCtrlPressed && event.key == Key.N -> { onOpenAdd(); true }
                    event.isCtrlPressed && event.key == Key.A -> { selected = visible.map { it.id }.toSet(); true }
                    event.key == Key.Delete && selected.isNotEmpty() -> { deleting = selected; selected = emptySet(); true }
                    event.key == Key.Escape && selected.isNotEmpty() -> { selected = emptySet(); true }
                    event.key == Key.Enter && selected.size == 1 -> {
                        all.firstOrNull { it.id in selected && it.status == DownloadStatus.COMPLETED }
                            ?.let { openWithSystem(it.location) }
                        true
                    }
                    else -> false
                }
            },
        color = state.palette.background
    ) {
        Column(Modifier.fillMaxSize()) {
            MenuBar(
                version = state.appVersion,
                onCheckUpdates = actions.checkForUpdates,
                onOpenAdd = onOpenAdd,
                onOpenSettings = onOpenSettings,
                onPauseAll = actions.pauseAll,
                onResumeAll = actions.resumeAll,
                onQuit = onQuit,
                onCreateTorrent = { creatingTorrent = true },
                extensionRoot = java.io.File(state.extensionPath),
                pairingToken = state.settings.captureToken
            )
            // Sized from the window rather than from fixed widths. The table used to
            // need 1071 dp before it stopped fitting - 230 of sidebar plus 841 of
            // columns - and the toolbar 982, so a window narrower than the one the app
            // opens at lost its right-hand columns and its last three buttons.
            //
            // The sidebar and the table and the toolbar each decide separately, because
            // they give things up in different orders: the sidebar narrows first, the
            // table drops columns from the right, and the toolbar drops its search box
            // before its captions - and never the buttons themselves.
            BoxWithConstraints(Modifier.weight(1f)) {
                val sidebar = sidebarWidthFor(maxWidth.value)
                // What is left for everything beside the sidebar and its rule.
                val contentDp = maxWidth.value - sidebar.value - 1f
                // The window's own height, kept for the pane to be capped against.
                val windowHeight = maxHeight.value
                val table = tableLayoutFor(contentDp)
                val toolbar = toolbarLayoutFor(contentDp)
                Row(Modifier.fillMaxSize()) {
                    CategoryRail(
                        items = all,
                        category = category,
                        group = group,
                        kind = kind,
                        width = sidebar,
                        // A rail this narrow shows icons only, each named by a tooltip.
                        compact = sidebar < 100.dp,
                        // Every row that is neither panel closes both. They did not, so the
                        // rail looked broken: you could go from Downloads to Search and
                        // then not back, because clicking All Downloads set the group
                        // and the category - both of which the panels do not read -
                        // and left the panel on screen.
                        onCategory = { selected = emptySet(); category = it; group = LibraryGroup.ALL; kindFilter = LibraryKind.ALL; queueFilter = null; searchOpen = false; youTubeOpen = false; rssOpen = false; extraPanel = null },
                        onGroup = { selected = emptySet(); group = it; category = LibraryCategory.ALL; kindFilter = LibraryKind.ALL; queueFilter = null; searchOpen = false; youTubeOpen = false; rssOpen = false; extraPanel = null },
                        queues = queues,
                        queueFilter = queueFilter,
                        onQueue = { id ->
                            selected = emptySet()
                            category = LibraryCategory.ALL
                            group = LibraryGroup.ALL
                            kindFilter = LibraryKind.ALL
                            queueFilter = if (queueFilter == id) null else id
                            searchOpen = false
                            youTubeOpen = false
                            rssOpen = false
                            extraPanel = null
                        },
                        onQueueStarted = actions.setQueueStarted,
                        onEditQueue = { editingQueue = it to false },
                        onNewQueue = {
                            editingQueue = QueueConfig(id = "q" + System.currentTimeMillis().toString(36), name = "") to true
                        },
                        // Picking a kind drops the category and the status as well, so the
                        // list is exactly the kind asked for and not the kind intersected
                        // with whatever was selected before. Choosing a second kind is the
                        // way to change your mind about the first.
                        onKind = {
                            selected = emptySet()
                            category = LibraryCategory.ALL
                            group = LibraryGroup.ALL
                            queueFilter = null
                            kindFilter = if (kind == it) LibraryKind.ALL else it
                            searchOpen = false
                            youTubeOpen = false
                            rssOpen = false
                            extraPanel = null
                        },
                        searchOpen = searchOpen,
                        onToggleSearch = { searchOpen = !searchOpen; youTubeOpen = false; rssOpen = false; extraPanel = null },
                        youTubeOpen = youTubeOpen,
                        onToggleYouTube = { youTubeOpen = !youTubeOpen; searchOpen = false; rssOpen = false; extraPanel = null },
                        rssOpen = rssOpen,
                        onToggleRss = { rssOpen = !rssOpen; searchOpen = false; youTubeOpen = false; extraPanel = null },
                        extraPanel = extraPanel,
                        onExtra = { entry ->
                            extraPanel = if (extraPanel == entry) null else entry
                            searchOpen = false
                            youTubeOpen = false
                            rssOpen = false
                        }
                    )
                    // No rule: the content is a card of its own, as in AB Download Manager.
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 6.dp, end = 8.dp, bottom = 8.dp)
                            // A hard, unblurred shadow offset down and right, as if the panel were a
                            // plate fixed onto the chassis - not a soft elevation shadow.
                            .drawBehind {
                                drawRoundRect(
                                    AppTheme.Palette.outline,
                                    topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()),
                                    size = size,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
                                )
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppTheme.Palette.surface)
                            .border(1.dp, AppTheme.Palette.outline, RoundedCornerShape(12.dp))
                    ) {
                     if (extraPanel == RailEntry.Tv) {
                        TvPanel(modifier = Modifier.fillMaxSize())
                        return@Column
                     }
                     if (extraPanel == RailEntry.Archive) {
                        ArchivePanel(
                            onDownload = { files ->
                                files.zip(com.downloadhub.core.ArchiveOrg.saveNames(files)).forEach { (file, name) ->
                                    actions.addPrepared(linkRequest(name, file.url, state.settings))
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        return@Column
                     }
                     if (extraPanel == RailEntry.Player) {
                        PlayerPanel(items = all, modifier = Modifier.fillMaxSize())
                        return@Column
                     }
                     val catalog = when (extraPanel) {
                        RailEntry.Books -> FreeCatalog.BOOKS
                        RailEntry.Movies -> FreeCatalog.MOVIES
                        RailEntry.Music -> FreeCatalog.MUSIC
                        else -> null
                     }
                     if (catalog != null) {
                        BooksPanel(
                            onDownload = { book, file -> actions.addPrepared(bookRequest(book, file, state.settings)) },
                            modifier = Modifier.fillMaxSize(),
                            catalog = catalog
                        )
                        return@Column
                     }
                     if (rssOpen) {
                        RssPanel(
                            settings = state.settings,
                            itemsByFeed = state.rssItems,
                            refreshing = state.rssRefreshing,
                            actions = actions,
                            modifier = Modifier.fillMaxSize()
                        )
                        return@Column
                     }
                     if (searchOpen) {
                        SearchPanel(
                            state = searchState,
                            // Straight into the same pre-download window every other magnet
                            // goes through, so a search result gets the file list, the
                            // folder and the stop condition without any of it being written
                            // twice.
                            //
                            // The panel stays open. It used to close, which threw the
                            // query and every result away - and the results are the
                            // point, since the pre-download window is for choosing files
                            // out of one torrent and comparing it against the others is
                            // how that choice gets made. The dialog is on top either way.
                            onPick = { magnet ->
                                onOpenAddForLink(magnet)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        return@Column
                     }
                     if (youTubeOpen) {
                        YouTubePanel(
                            fetch = actions.fetchYouTubeListing,
                            prefill = youTubePrefill,
                            onPrefillConsumed = onYouTubePrefillConsumed,
                            knownIds = all
                                .filter { it.source == com.downloadhub.core.DownloadSource.YOUTUBE }
                                .mapNotNull { com.downloadhub.core.youTubeIdFromUrl(it.url) }
                                .toSet(),
                            onQueue = { entries, audioOnly, ceiling, format ->
                                // Back to the Downloads list: the rows appearing
                                // there are the confirmation.
                                val queued = actions.queueYouTubeEntries(entries, audioOnly, ceiling, format)
                                searchOpen = false
                                youTubeOpen = false
                                queued
                            },
                            fetchFormats = actions.listVideoFormats,
                            // One video at its exact streams, then back to the
                            // Downloads list the same way.
                            onPickExact = { url, choice, audioOnly, title ->
                                actions.addChosenVideo(url, choice, audioOnly, title)
                                searchOpen = false
                                youTubeOpen = false
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        return@Column
                     }
                        // What each button would act on, from the ticked rows. A button that
                        // would do nothing to any of them is disabled rather than pressed.
                        val ticked = all.filter { it.id in selected }
                        val resumable = ticked.filter { it.status == DownloadStatus.PAUSED }
                        val pausable = ticked.filter { it.isActive }
                        val retryable = ticked.filter {
                            it.status == DownloadStatus.FAILED || it.status == DownloadStatus.COMPLETED
                        }
                        LibraryToolbar(
                            hasSelection = selected.isNotEmpty(),
                            selectedCount = selected.size,
                            activeCount = DownloadLibrary.activeCount(all),
                            onNew = onOpenAdd,
                            resumeCount = resumable.size,
                            pauseCount = pausable.size,
                            retryCount = retryable.size,
                            onResume = { resumable.forEach { actions.resume(it.id) } },
                            onPause = { pausable.forEach { actions.pause(it.id) } },
                            onRetry = { retryable.forEach { actions.retry(it.id) } },
                            onStop = { selected.forEach { actions.stop(it) } },
                            hasResumable = all.any {
                                it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED
                            },
                            onStartQueue = actions.resumeAll,
                            onStopAll = actions.pauseAll,
                            onDelete = { deleting = selected; selected = emptySet() },
                            onOpenFolder = actions.openDownloadFolder,
                            onSettings = onOpenSettings,
                            layout = toolbar,
                            search = search,
                            onSearch = { search = it; selected = emptySet() },
                            cardView = state.settings.libraryCards,
                            onCardView = { actions.updateSettings(state.settings.copy(libraryCards = it)) }
                        )
                        InstrumentPanel(all, state.settings.downloadDir)
                        val cardView = state.settings.libraryCards
                        if (!cardView) ColumnHeader(
                            sort = sort,
                            onSort = { sort = it },
                            layout = table,
                            widths = columnWidths,
                            tableDp = contentDp,
                            visible = visible,
                            selected = selected,
                            onSelectedChange = { selected = it },
                            onDragStart = { column ->
                                dragColumn = column
                                dragStartWidth = ColumnDividers.resolvedWidthOf(
                                    table, columnWidths, contentDp, column
                                )
                            },
                            onDrag = { column, deltaDp ->
                                // By how far the pointer has travelled, not by where it
                                // is. A delta from the press point is the only form that
                                // cannot be thrown off by the header's own padding, the
                                // checkbox column, or the display's pixel-to-dp scale -
                                // and it cancels out landing anywhere inside the 14 dp
                                // handle, so a column no longer shrinks under the cursor
                                // before you have moved at all.
                                columnWidths = ColumnDividers.draggedBy(
                                    widths = columnWidths,
                                    column = column,
                                    proposedWidth = dragStartWidth + deltaDp,
                                    tableDp = contentDp,
                                    layout = table
                                )
                            }
                        )
                        HorizontalDivider(color = state.palette.outline.copy(alpha = 0.5f))
                        if (visible.isEmpty()) {
                            EmptyList(
                                filtered = all.isNotEmpty(),
                                onNew = onOpenAdd,
                                onFind = { searchOpen = true; youTubeOpen = false },
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        } else {
                          val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                          Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                items(visible, key = { it.id }) { item ->
                                  // Slides into place on add, remove and re-sort instead of jumping.
                                  Column(Modifier.animateItem()) {
                                   if (cardView) {
                                    DownloadCardRow(
                                        item = item,
                                        checked = item.id in selected,
                                        onToggle = {
                                            selected = if (item.id in selected) selected - item.id else selected + item.id
                                        },
                                        onPause = { actions.pause(item.id) },
                                        onResume = { actions.resume(item.id) },
                                        onRetry = { actions.retry(item.id) },
                                        onOpen = { actions.revealDownload(item.location) },
                                        onOptions = { optionsFor = item.id },
                                        onRemove = { deleting = setOf(item.id) }
                                    )
                                   } else {
                                    DownloadRow(
                                        item = item,
                                        palette = state.palette,
                                        layout = table,
                                        widths = columnWidths,
                                        tableDp = contentDp,
                                        checked = item.id in selected,
                                        onToggle = {
                                            // Selecting a row is what the bottom pane
                                            // follows, so it shows the download that was
                                            // last touched rather than an arbitrary one.
                                            selected = if (item.id in selected) {
                                                selected - item.id
                                            } else {
                                                selected + item.id
                                            }
                                        },
                                        onPause = { actions.pause(item.id) },
                                        onResume = { actions.resume(item.id) },
                                        onRetry = { actions.retry(item.id) },
                                        onOpen = { actions.revealDownload(item.location) },
                                        onOpenFile = { openWithSystem(item.location) },
                                        onOptions = { optionsFor = item.id },
                                        onContext = { x, y ->
                                            contextAt = IntOffset(x, y)
                                            contextFor = item.id
                                            selected = setOf(item.id)
                                        }
                                    )
                                    HorizontalDivider(
                                        color = state.palette.outline.copy(alpha = 0.25f),
                                        thickness = 1.dp
                                    )
                                   }
                                  }
                                }
                            }
                            // After the list, so it sits on top and can be dragged.
                            androidx.compose.foundation.VerticalScrollbar(
                                adapter = androidx.compose.foundation.rememberScrollbarAdapter(listState),
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                            )
                          }
                        }

                    // The bottom pane, but only when a download is actually selected.
                    //
                    // It was drawn for whatever the list happened to contain when nothing
                    // was ticked, which meant the pane was usually describing a download
                    // nobody had chosen - and on a queue of one it was a permanent
                    // reminder of a pane with nothing to say. It belongs to the
                    // selection: tick a row, get the details for that row; untick, get
                    // the room back.
                    //
                    // Falling back to "the first torrent", as it once did, was worse
                    // still: on the main list it described a download that might not be
                    // on screen at all.
                    val detailItem = all.firstOrNull { it.id in selected }
                    if (detailItem != null) {
                        // The store item rather than the core model, because the file
                        // list's per-file progress and the id both actions need are on
                        // this one.
                        val detailRow = state.items.firstOrNull { it.id == detailItem.id }
                        TorrentDetailPanel(
                            item = detailItem,
                            tab = TorrentTab.forDownload(detailItem.isTorrent, detailTab),
                            onTab = { detailTab = it },
                            expanded = detailOpen,
                            onExpandedChange = { detailOpen = it },
                            // Never the list's room. The pane draws at a fixed height and
                            // the list takes what is left, so a short window - or a pane
                            // the user had once made taller - left the list with nothing
                            // at all. The download list then disappeared entirely, which
                            // is the opposite of what a detail pane is for.
                            //
                            // Capped against the window rather than against the chosen
                            // height, so the height is remembered and comes back on a
                            // taller window, but cannot starve the list on a short one.
                            paneHeight = detailPaneHeight.coerceAtMost(
                                (windowHeight - LIST_MIN_DP).coerceAtLeast(PANE_MIN_DP)
                            ),
                            onPaneHeightChange = { detailPaneHeight = it },
                            // A reading, not a setting: it changes every second and is
                            // worth nothing after a restart, so it is held on the store
                            // item rather than persisted in the queue file.
                            fileProgress = detailRow?.torrentFileProgress ?: emptyMap(),
                            trackersOf = actions.torrentTrackers,
                            peersOf = actions.torrentPeers,
                            onAddTrackers = { urls -> actions.addTrackers(detailItem.id, urls) },
                            onFilePriority = { index, priority ->
                                detailRow?.let { row ->
                                    actions.setFilePriority(row.id, index, priority)
                                }
                            }
                        )
                    }

                    StatusBar(state, all, onToggleAltSpeed = {
                        actions.updateSettings(state.settings.copy(altSpeedEnabled = !state.settings.altSpeedEnabled))
                    })
                }
                }
            }
            // Under everything, on every screen, while something is playing.
            MiniPlayerBar(onOpenPlayer = {
                extraPanel = RailEntry.Player
                searchOpen = false
                youTubeOpen = false
                rssOpen = false
            })
        }

        // Asking is the point. "Remove from the list" and "delete the file" are both
        // things people mean, and the second is not undoable, so the app does not
        // choose on the user's behalf.
        // One download's own settings. Kept inside the window like every other dialog:
        // a dialog composed outside it is what produced "Failed to launch JVM" on the
        // first button press.
        val optionsItem = optionsFor?.let { id -> all.firstOrNull { it.id == id } }
        if (optionsItem != null) {
            DownloadOptionsDialog(
                item = optionsItem,
                globalSpeedLimitBytesPerSecond = state.settings.speedLimitBytesPerSecond,
                onSave = { rank, speed, startAfter, ratio, minutes ->
                    actions.setItemOptions(optionsItem.id, rank, speed, startAfter, ratio, minutes)
                    optionsFor = null
                },
                onDismiss = { optionsFor = null },
                onSaveRequest = { request -> actions.setItemRequest(optionsItem.id, request) }
            )
        }

        if (deleting.isNotEmpty()) {
            // Anything with bytes on disk - a finished file or a half-fetched partial - has
            // something the tick box can be about. Only a download that has not written
            // anything yet does not.
            val onDisk = state.items.filter {
                it.id in deleting &&
                    (it.status == DownloadStatus.COMPLETED || it.bytesDownloaded > 0L)
            }
            DeleteChoiceDialog(
                count = deleting.size,
                canDeleteFiles = onDisk.isNotEmpty(),
                // One name when there is a single item: "Remove 'ubuntu.iso'?" says far
                // more than "Remove this download?", and this is the one dialog where
                // naming it prevents removing the wrong row.
                subject = if (deleting.size == 1) {
                    "'" + (onDisk.firstOrNull()?.fileName
                        ?: state.items.firstOrNull { it.id in deleting }?.fileName
                        ?: "it") + "'"
                } else {
                    "these ${deleting.size} downloads"
                },
                // Starts on whatever the user chose in settings, so the common case needs
                // no thought and the unusual one is one tick away.
                deleteFilesDefault = state.settings.deleteCacheWhenRemoved,
                onConfirm = { deleteFiles ->
                    deleting.forEach { actions.removeSelectingFiles(it, deleteFiles) }
                    deleting = emptySet()
                },
                onDismiss = { deleting = emptySet() }
            )
        }

        // The right-click menu, the rename box and the relocation box. All three look
        // their item up in `all` rather than holding a copy, so a row that has since been
        // removed closes its dialog instead of acting on a stale snapshot of itself.
        all.firstOrNull { it.id == contextFor }?.let { item ->
            DownloadContextMenu(
                item = item,
                hasContentFiles = item.location != null || item.bytesDownloaded > 0L,
                atX = contextAt.x,
                atY = contextAt.y,
                onAction = { action ->
                    contextFor = null
                    when (action) {
                        ContextAction.Pause -> actions.pause(item.id)
                        ContextAction.Resume -> actions.resume(item.id)
                        ContextAction.ForceStart -> actions.retry(item.id)
                        ContextAction.Options -> optionsFor = item.id
                        ContextAction.SetLocation -> relocating = item.id
                        ContextAction.Rename -> renaming = item.id
                        ContextAction.OpenFolder -> actions.revealDownload(item.location)
                        // The clipboard is the one part of this that can fail silently,
                        // and a menu item that does nothing is worse than a message.
                        ContextAction.CopyMagnet -> actions.copyToClipboard(magnetLinkFor(item))
                        ContextAction.ExportTorrent -> actions.exportTorrent(item.id)
                        // Always offered, never enabled: see ContextAction.
                        ContextAction.AutomaticManagement -> Unit
                        ContextAction.MoveToQueue -> moving = setOf(item.id)
                        ContextAction.ForceRecheck -> actions.forceRecheck(item.id)
                        ContextAction.ForceReannounce -> actions.forceReannounce(item.id)
                        ContextAction.Remove -> {
                            deleting = setOf(item.id)
                            contextFor = null
                        }
                    }
                },
                onDismiss = { contextFor = null }
            )
        }

        editingQueue?.let { (queue, isNew) ->
            QueueEditorDialog(
                queue = queue,
                isNew = isNew,
                onSave = { actions.saveQueue(it); editingQueue = null },
                onDelete = if (isNew || queue.id == com.downloadhub.core.QueueRules.MAIN) null else {
                    {
                        actions.deleteQueue(queue.id)
                        if (queueFilter == queue.id) queueFilter = null
                        editingQueue = null
                    }
                },
                onDismiss = { editingQueue = null }
            )
        }

        if (creatingTorrent) {
            CreateTorrentDialog(
                onSeed = actions.addPrepared,
                onDismiss = { creatingTorrent = false }
            )
        }

        if (moving.isNotEmpty()) {
            QueuePickerDialog(
                queues = queues,
                current = all.firstOrNull { it.id in moving }?.queueId,
                count = moving.size,
                onPick = { id -> actions.moveToQueue(moving, id); moving = emptySet() },
                onDismiss = { moving = emptySet() }
            )
        }

        all.firstOrNull { it.id == renaming }?.let { item ->
            RenameDownloadDialog(
                currentName = item.fileName,
                onConfirm = { name ->
                    actions.renameDownload(item.id, name)
                    renaming = null
                },
                onDismiss = { renaming = null }
            )
        }

        all.firstOrNull { it.id == relocating }?.let { item ->
            SetLocationDialog(
                currentDirectory = item.outputPath ?: state.settings.downloadDir,
                onConfirm = { path ->
                    actions.setDownloadLocation(item.id, path)
                    relocating = null
                },
                onDismiss = { relocating = null }
            )
        }
    }
}

/**
 * Asks what "delete" should mean.
 *
 * Two answers people actually want - tidy the list but keep the file, or take the file
 * too - plus the implicit third, cancelling, which leaving the dialog open provides.
 */
/**
 * The remove confirmation.
 *
 * One button, not two. The earlier version asked "remove it, or remove it and delete the
 * files?" and answered with two buttons, which puts a destructive choice beside a safe one
 * and makes the safe one the unusual shape. Modelled on qBittorrent's instead: one
 * "Remove" button, and a tick box that says what else will happen.
 *
 * The tick box is the part that matters. Removing a download that never finished has two
 * reasonable outcomes - the half-fetched file is wanted again, or it is not - and guessing
 * wrong either strands a partial file nobody can account for, or throws away work the
 * user came back for. So it is asked, and it starts on the user's own default.
 */
@Composable
private fun DeleteChoiceDialog(
    count: Int,
    canDeleteFiles: Boolean,
    subject: String = if (count == 1) "this download" else "these $count downloads",
    deleteFilesDefault: Boolean = false,
    onConfirm: (deleteFiles: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var deleteFiles by remember(count, canDeleteFiles) { mutableStateOf(deleteFilesDefault) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = APP_DIALOG_PROPERTIES,
        title = { Text("Remove $subject?") },
        text = {
            Column {
                if (canDeleteFiles) {
                    // A folder, a file and a half-finished one are all different things to
                    // delete, so the label says which rather than saying "content files"
                    // whatever that happens to be.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TickBox(checked = deleteFiles, onChange = { deleteFiles = it })
                        Text(
                            "Also remove the content files",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (deleteFiles) {
                            "This deletes what is on disk. It cannot be undone."
                        } else {
                            "The download is removed from the list and what it has " +
                                "fetched so far is kept in the cache."
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Remove $subject from the list? Nothing has been written to disk yet.",
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (canDeleteFiles) deleteFiles else false) }) {
                Text("Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
@Composable
private fun VerticalRule() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(AppTheme.Palette.outlineVariant)
    )
}

@Composable
private fun CategoryRail(
    items: List<DownloadItem>,
    category: LibraryCategory,
    group: LibraryGroup,
    kind: LibraryKind,
    /**
     * Sized from the window by the caller. A fixed 230 dp is a third of a 700 dp
     * window, which leaves nothing for the thing the window is for, so it narrows
     * before the table gives up any columns.
     */
    width: androidx.compose.ui.unit.Dp,
    /** Narrow: the labels no longer sit comfortably beside the counts. */
    compact: Boolean,
    onCategory: (LibraryCategory) -> Unit,
    onGroup: (LibraryGroup) -> Unit,
    onKind: (LibraryKind) -> Unit,
    /** Search is a section rather than a filter, so it is its own boolean. */
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    /** YouTube is a section beside it, for the same reason. */
    youTubeOpen: Boolean,
    onToggleYouTube: () -> Unit,
    rssOpen: Boolean = false,
    onToggleRss: () -> Unit = {},
    extraPanel: RailEntry? = null,
    onExtra: (RailEntry) -> Unit = {},
    queues: List<QueueConfig> = emptyList(),
    queueFilter: String? = null,
    onQueue: (String) -> Unit = {},
    onQueueStarted: (String, Boolean) -> Unit = { _, _ -> },
    onEditQueue: (QueueConfig) -> Unit = {},
    onNewQueue: () -> Unit = {}
) {
    // What the table is actually showing, so every number beside it is reachable.
    //
    // The kind counts deliberately do not use this. They are counted over the whole
    // queue, because a count of zero beside Torrents when the list is already scoped to
    // YouTube says "there are none" when it means "not this list".
    val scoped = DownloadLibrary.scopedFor(items, kind)
        // Which rail sections are folded away.
        //
        // A set rather than a flag per section, so adding a section to the rail does not
        // also mean adding a variable to remember whether it is open. Everything starts
        // open: a section nobody has touched should show its rows, and folding one is a
        // deliberate act that lasts for the session.
        var collapsedSections by remember { mutableStateOf(emptySet<String>()) }
        val entries = sidebarEntries()
        val railScroll = rememberScrollState()
        Box(Modifier.width(width).fillMaxHeight()) {
        androidx.compose.foundation.VerticalScrollbar(
            adapter = androidx.compose.foundation.rememberScrollbarAdapter(railScroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(railScroll)
                .padding(vertical = 6.dp)
        ) {
            // Built from the shared list rather than assembled here. The rail used to print
            // "Finished" as a heading and again as a row underneath it, and "Unfinished" the
            // same way, because the two halves were written separately. One list cannot
            // produce a heading and a row with the same word, because a heading is a
            // RailEntry.Heading and a row is a RailEntry.Status, and the list has each
            // exactly once.
            //
            // A heading owns every row up to the next one, and folding it folds them with
            // it - so the set of rows belonging to a section is worked out by walking the
            // list, not by each entry knowing what follows it.
            val ownerOf = remember(entries) {
                val owners = mutableMapOf<RailEntry, String>()
                var owner: String? = null
                entries.forEach { entry ->
                    if (entry is RailEntry.Heading) owner = entry.label
                    owner?.let { owners[entry] = it }
                }
                owners
            }
            entries.forEach { entry ->
                // A row in a folded section is not drawn at all, rather than drawn and
                // greyed: the point of folding is that it takes less room.
                if (ownerOf[entry]?.let { it in collapsedSections } == true &&
                    entry !is RailEntry.Heading
                ) {
                    return@forEach
                }
                when (entry) {
                    is RailEntry.Heading -> GroupHeader(
                        label = entry.label,
                        compact = compact,
                        expanded = entry.label !in collapsedSections,
                        onToggle = {
                            collapsedSections = if (entry.label in collapsedSections) {
                                collapsedSections - entry.label
                            } else {
                                collapsedSections + entry.label
                            }
                        }
                    )
                is RailEntry.Status -> RailRow(
                    label = entry.group.label,
                    count = railCount(entry, scoped),
                    // A status row is only selected when nothing else is narrowing: the
                    // category, the group and the kind are all part of the same query,
                    // and two highlighted rows read as two choices when there is one.
                    selected = group == entry.group && category == LibraryCategory.ALL &&
                        kind == LibraryKind.ALL && queueFilter == null && !searchOpen && !youTubeOpen && !rssOpen && extraPanel == null,
                    icon = StatusIcons.of(entry.group),
                    compact = compact
                ) { onGroup(entry.group) }

                is RailEntry.Category -> RailRow(
                    label = entry.category.label,
                    count = railCount(entry, scoped),
                    selected = category == entry.category && group == LibraryGroup.ALL &&
                        kind == LibraryKind.ALL && queueFilter == null && !searchOpen && !youTubeOpen && !rssOpen && extraPanel == null,
                    icon = LibraryCategoryIcons.of(entry.category),
                    compact = compact
                ) { onCategory(entry.category) }

                // The three kinds. Selecting one clears the category and the status, so
                // the query says one thing rather than three that happen to agree.
                is RailEntry.Kind -> RailRow(
                    label = entry.kind.label,
                    count = railCount(entry, items),
                    selected = kind == entry.kind && !searchOpen && !youTubeOpen && !rssOpen && extraPanel == null,
                    icon = LibraryKindIcons.of(entry.kind),
                    compact = compact
                ) { onKind(entry.kind) }

                RailEntry.Search -> RailRow(
                    label = "Find torrents",
                    count = 0,
                    selected = searchOpen,
                    icon = Lucide.Search,
                    compact = compact
                ) { onToggleSearch() }

                RailEntry.YouTube -> RailRow(
                    // Not "YouTube": the Kinds heading has a YouTube row that filters the
                    // list, and two rows with one name did two different things.
                    label = "Get from YouTube",
                    count = 0,
                    selected = youTubeOpen,
                    icon = DlmIcons.YouTube,
                    compact = compact
                ) { onToggleYouTube() }

                RailEntry.Rss -> RailRow(
                    label = "RSS feeds",
                    count = 0,
                    selected = rssOpen,
                    icon = Lucide.Rss,
                    compact = compact
                ) { onToggleRss() }

                RailEntry.Books -> RailRow(
                    label = "Free books",
                    count = 0,
                    selected = extraPanel == RailEntry.Books,
                    icon = Lucide.BookOpen,
                    compact = compact
                ) { onExtra(RailEntry.Books) }

                RailEntry.Movies -> RailRow(
                    label = "Free movies",
                    count = 0,
                    selected = extraPanel == RailEntry.Movies,
                    icon = Lucide.Film,
                    compact = compact
                ) { onExtra(RailEntry.Movies) }

                RailEntry.Music -> RailRow(
                    label = "Free music",
                    count = 0,
                    selected = extraPanel == RailEntry.Music,
                    icon = DlmIcons.Music,
                    compact = compact
                ) { onExtra(RailEntry.Music) }

                RailEntry.Player -> RailRow(
                    label = "Player",
                    count = 0,
                    selected = extraPanel == RailEntry.Player,
                    icon = Lucide.CirclePlay,
                    compact = compact
                ) { onExtra(RailEntry.Player) }

                RailEntry.Tv -> RailRow(
                    label = "Free TV",
                    count = 0,
                    selected = extraPanel == RailEntry.Tv,
                    icon = Lucide.Tv,
                    compact = compact
                ) { onExtra(RailEntry.Tv) }

                RailEntry.Archive -> RailRow(
                    label = "Internet Archive",
                    count = 0,
                    selected = extraPanel == RailEntry.Archive,
                    icon = Lucide.Library,
                    compact = compact
                ) { onExtra(RailEntry.Archive) }
            }
        }
            // Queues, after the fixed sections: they are the user's own, and there can be
            // any number of them.
            GroupHeader(
                label = "Queues",
                compact = compact,
                expanded = "Queues" !in collapsedSections,
                onToggle = {
                    collapsedSections = if ("Queues" in collapsedSections) collapsedSections - "Queues" else collapsedSections + "Queues"
                }
            )
            if ("Queues" !in collapsedSections) {
                queues.forEach { queue ->
                    QueueRailRow(
                        queue = queue,
                        count = items.count { it.queueId == queue.id && it.status != DownloadStatus.COMPLETED },
                        selected = queueFilter == queue.id && !searchOpen && !youTubeOpen && !rssOpen && extraPanel == null,
                        compact = compact,
                        onClick = { onQueue(queue.id) },
                        onToggle = { onQueueStarted(queue.id, !queue.started) },
                        onEdit = { onEditQueue(queue) }
                    )
                }
                RailRow(
                    label = "New queue",
                    count = -1,
                    selected = false,
                    icon = Lucide.Plus,
                    compact = compact,
                    onClick = onNewQueue
                )
            }
        }
        }
}

/**
 * One queue in the rail: its name, what it still has to do, a start/stop button and a
 * gear. The row itself narrows the list to the queue, as every other rail row does.
 */
@Composable
private fun QueueRailRow(
    queue: QueueConfig,
    count: Int,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .hoverFill(selected = selected, shape = RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = if (compact) 7.dp else 14.dp, end = if (compact) 4.dp else 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Lucide.List,
            null,
            Modifier.size(15.dp),
            tint = if (selected) AppTheme.Palette.accent else AppTheme.Palette.faint
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                queue.name,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) AppTheme.Palette.accent else AppTheme.Palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                queueSummary(queue) + if (count > 0) " · $count waiting" else "",
                fontSize = 10.sp,
                color = if (queue.started) AppTheme.success else AppTheme.Palette.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(
            16.dp,
            if (queue.started) DlmIcons.Stop else Lucide.Play,
            if (queue.started) "Stop queue" else "Start queue",
            onToggle
        )
        IconButton(16.dp, Lucide.Settings, "Edit queue", onEdit)
    }
}

/** An icon for each kind, so the three read as three rather than as one list twice. */
private object LibraryKindIcons {
    fun of(kind: LibraryKind): androidx.compose.ui.graphics.vector.ImageVector? = when (kind) {
        // The whole queue gets no icon, like All Downloads: there is nothing in it that
        // is not in one of the other three.
        LibraryKind.ALL -> null
        LibraryKind.TORRENT -> Lucide.Magnet
        LibraryKind.YOUTUBE -> DlmIcons.YouTube
        LibraryKind.NORMAL -> Lucide.Globe
    }
}

/** An icon for each status filter, so the rail can be read rather than deciphered. */
private object StatusIcons {
    fun of(group: LibraryGroup): androidx.compose.ui.graphics.vector.ImageVector? = when (group) {
        LibraryGroup.ALL -> Lucide.Layers
        LibraryGroup.DOWNLOADING -> Lucide.Download
        LibraryGroup.FINISHED -> Lucide.CircleCheck
        LibraryGroup.UNFINISHED -> Lucide.CircleDashed
        LibraryGroup.PAUSED -> Lucide.CirclePause
        LibraryGroup.FAILED -> Lucide.CircleAlert
    }
}

    @Composable
    private fun GroupHeader(
        label: String,
        expanded: Boolean = true,
        onToggle: (() -> Unit)? = null,
        /** Icon-only rail: a section is a thin rule, not a word. */
        compact: Boolean = false
    ) {
        if (compact) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .height(1.dp)
                    .background(AppTheme.Palette.outline.copy(alpha = 0.3f))
            )
            return
        }
        // A chevron, and the whole header is the target.
        //
        // Collapsible because the rail has outgrown the window it was arranged for: with
        // the states, the categories and the kinds all listed, Finished and Unfinished
        // are four rows nobody reads every time, and the section headings are what let
        // them be folded away without losing the rows that matter.
        //
        // The count travels with the heading, so a folded section still says how much is
        // in it - a collapsed section that says nothing is one you cannot tell apart from
        // an empty one.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onToggle != null) {
                        Modifier.clickable(onClick = onToggle)
                    } else {
                        Modifier
                    }
                )
                // Lined up with the row labels' left edge, with air above: a section label,
                // as a modern sidebar writes it, and the fold chevron at the far end.
                .padding(start = 22.dp, end = 18.dp, top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.Palette.faint,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (onToggle != null) {
                Icon(
                    imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                    contentDescription = if (expanded) "Collapse $label" else "Expand $label",
                    modifier = Modifier.size(14.dp),
                    tint = AppTheme.Palette.faint
                )
            }
        }
    }
@Composable
private fun RailRow(
    label: String,
    count: Int,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /** Narrow rail: tighten the indent so the label and count still both fit. */
    compact: Boolean = false,
    onClick: () -> Unit
) {
    // The chosen row is a raised panel with a lit LED bar on its edge, as on the Android
    // rail: the accent marks it without flooding the sidebar. Counts show only when there
    // is something to count, so the rail is not a column of zeros.
    val shape = RoundedCornerShape(6.dp)
    val fill = if (selected) {
        Modifier
            .background(AppTheme.Palette.raised, shape)
            .border(1.dp, AppTheme.Palette.outline, shape)
            .drawBehind {
                drawRoundRect(
                    AppTheme.Palette.accent,
                    topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), size.height * 0.25f),
                    size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height * 0.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx())
                )
            }
    } else Modifier.hoverFill(shape = shape)
    val iconTint = if (selected) AppTheme.Palette.onSurface else AppTheme.Palette.muted
    if (compact) {
        // Icons only: the rail is too narrow for words, so the tooltip names the row.
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.foundation.TooltipArea(
            tooltip = {
                Text(
                    if (count > 0) "$label ($count)" else label,
                    fontSize = 12.sp,
                    color = AppTheme.Palette.onSurface,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(6.dp))
                        .background(AppTheme.Palette.raised, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            },
            delayMillis = 300
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 2.dp)
                    .height(40.dp)
                    .clip(shape)
                    .then(fill)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(icon ?: Lucide.Layers, label, Modifier.size(20.dp), tint = iconTint)
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .height(36.dp)
            .clip(shape)
            .then(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(18.dp), tint = iconTint)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = AppTheme.Palette.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Text(
                "$count",
                fontSize = 11.sp,
                fontFamily = Mono,
                color = if (selected) AppTheme.Palette.accent else AppTheme.Palette.faint
            )
        }
    }
}

@Composable
private fun LibraryToolbar(
    hasSelection: Boolean,
    activeCount: Int,
    /**
     * How many rows are ticked. Shown on the buttons that act on the
     * selection, because "which ones did I mean" is a question the buttons
     * should answer without a glance back down the list.
     */
    selectedCount: Int,
    onNew: () -> Unit,
    /** How many ticked rows each button would act on; zero disables it. */
    resumeCount: Int,
    pauseCount: Int,
    retryCount: Int,
    onResume: () -> Unit,
    onPause: () -> Unit,
    /** Starts failed downloads again; on a finished one it says where the file is. */
    onRetry: () -> Unit,
    /**
     * Stops what is ticked. Not Stop All, which stops everything.
     */
    onStop: () -> Unit,
    /** Anything paused or failed that Resume All would restart. */
    hasResumable: Boolean,
    onStartQueue: () -> Unit,
    onStopAll: () -> Unit,
    onDelete: () -> Unit,
    onOpenFolder: () -> Unit,
    onSettings: () -> Unit,
    /**
     * How much of the toolbar fits, and how wide each button ended up. The captions go
     * before the search box, because the buttons are what the toolbar is for; the search
     * box then takes whatever is left rather than a fixed width, so it is never what
     * overflows.
     */
    layout: ToolbarLayout,
    search: String,
    onSearch: (String) -> Unit,
    cardView: Boolean,
    onCardView: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.Palette.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val compact = layout.style == ToolbarStyle.COMPACT
        ToolbarButton("New Download", Lucide.Plus, highlighted = true, onClick = onNew, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarDivider()
        ToolbarButton("Resume", Lucide.Play, enabled = resumeCount > 0, badge = resumeCount, onClick = onResume, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Pause", DlmIcons.Pause, enabled = pauseCount > 0, badge = pauseCount, onClick = onPause, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Retry", Lucide.RotateCw, enabled = retryCount > 0, badge = retryCount, onClick = onRetry, compact = compact, buttonWidth = layout.buttonDp)
    // Stop, for the selection only. Beside Pause because the two are the pair everybody
    // reaches for, and beside Stop All because that is the one it mirrors: Stop All stops
    // everything, this stops what is ticked. It needed its own button because pausing a
    // broken download keeps it in the session, still holding its slot, still holding the
    // swarm open - which is not what someone means when they press stop.
    ToolbarButton("Stop", DlmIcons.Stop, enabled = hasSelection, badge = selectedCount, onClick = onStop, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarDivider()
        // Two queue buttons, not three: Stop Queue and Stop All both paused everything.
        ToolbarButton("Resume All", Lucide.Play, enabled = hasResumable, onClick = onStartQueue, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarButton("Pause All", DlmIcons.Pause, enabled = activeCount > 0, onClick = onStopAll, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarDivider()
        ToolbarButton("Delete", Lucide.Trash2, enabled = hasSelection, onClick = onDelete, compact = compact, buttonWidth = layout.buttonDp)
        ToolbarDivider()
        // Next to the search box rather than with the transfer actions: it is about the
        // destination, not about the queue.
        ToolbarButton("Downloads", DlmIcons.Folder, onClick = onOpenFolder, compact = compact, buttonWidth = layout.buttonDp)
      if (layout.showsSearch) {
        // A weight with a ceiling, not a fixed width. The fixed 260 dp was the one
        // thing in this row that could not give way, so it was what pushed Settings
        // off the end of a window at the size the app opens at.
        //
        // The floor is the important half, and the field is given one that Compose will
        // not go below: `layout.showsSearch` is meant to guarantee this box has room,
        // and when that guarantee was wrong - the button count was stale, so the row was
        // measured as though a tenth button did not exist - the box was handed the
        // leftover, which was nothing. It collapsed to a couple of dozen pixels and its
        // placeholder wrapped one letter per line down the whole window, which dragged
        // the toolbar to nearly half the height of the screen and left the download list
        // with no room at all.
        //
        // So the placeholder cannot wrap and cannot make the field taller than one line,
        // and the field is given a height it will keep whatever the width does.
        OutlinedTextField(
          value = search,
          onValueChange = onSearch,
          singleLine = true,
          placeholder = {
            Text(
              "Search in the list",
              fontSize = 12.sp,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis
            )
          },
          leadingIcon = { Icon(Lucide.Search, null, Modifier.size(15.dp)) },
          textStyle = MaterialTheme.typography.bodySmall,
          modifier = Modifier
            .weight(1f)
            .widthIn(min = SEARCH_MIN_DP.dp, max = SEARCH_MAX_DP.dp)
            // The ceiling the field must not grow past, so that a narrow window costs
            // the box its width and never the toolbar its height.
            .heightIn(max = SEARCH_MAX_HEIGHT_DP.dp)
        )
      } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(6.dp))
        ViewToggle(cardView, onCardView)
        ToolbarButton("Settings", Lucide.Settings, onClick = onSettings, compact = compact, buttonWidth = layout.buttonDp)
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    /**
     * Icon-only. Nine captioned buttons are 666 dp before the search box, which is why
     * the right-hand end of the toolbar used to be cut off rather than wrapped - at the
     * app's own opening width, not only on a deliberately small one. An icon-only
     * button only needs room for a 30 dp target; the tooltip still names it.
     */
    compact: Boolean = false,
    /** The captioned width the caller worked out for this window. */
        buttonWidth: Float = CAPTION_BUTTON_DP,
    /**
     * A number in the corner of the icon, for a button that acts on a selection.
     *
     * The count is on the button rather than in the caption, because the caption is
     * already the width the window worked out and a number added to it changes that. It
     * answers "which rows did I mean" without anybody having to look back down at the
     * list to count the ticks.
     */
    badge: Int = 0,
) {
    val tint = when {
        // Muted, not faint: the key itself is already dimmed when disabled, and dimming
        // both left the caption unreadable.
        !enabled -> AppTheme.Palette.muted
        highlighted -> AppTheme.Palette.accent
        else -> AppTheme.Palette.muted
    }
    // The icon of the highlighted button sits on the accent fill; the caption does not.
    // Sharing one tint made the caption the container colour on a surface, which is
    // near-white on white.
    val iconTint = if (highlighted && enabled) AppTheme.Palette.onAccent else tint
    /**
     * No height on the button, and none on the caption.
     *
     * The caption was clipped along its bottom edge on every button at once, all by the
     * same few pixels. Giving the caption a fixed 15 dp box made it *worse*, not better,
     * which is the clue: the text was not short of room in the layout, it was short of
     * room in its own line box. Compose sizes a line from the font's metrics, and at
     * 10 sp with the default font the descenders and the leading sit below where it
     * measured to - so the glyphs were drawn past the bottom of the line and sliced.
     *
     * An explicit `lineHeight` makes the box tall enough for the glyphs, which is the
     * only thing that was actually wrong. Constraining the box instead - by height, on
     * either the text or the button - just moves the clip.
     */
    // The main action is a labelled pill while there is room; every other action is an
    // icon with a tooltip. Nine captioned buttons were the most crowded thing on screen.
    if (highlighted && !compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(end = 6.dp)
                .keycap(enabled = enabled, accent = true, onClick = onClick)
                .height(34.dp)
                .padding(horizontal = 14.dp)
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = AppTheme.Palette.onAccent)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onAccent, maxLines = 1, softWrap = false)
        }
        return
    }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Text(
                label,
                fontSize = 12.sp,
                color = AppTheme.Palette.onSurface,
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(6.dp))
                    .background(AppTheme.Palette.raised, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        },
        delayMillis = 400
    ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(if (compact) buttonWidth.dp else TOOLBAR_CAPTIONED_DP.dp)
            .padding(vertical = 2.dp)
    ) {
                // Each action is a key on the panel, with its name printed underneath.
                Box(
                    modifier = Modifier
                        .keycap(enabled = enabled, accent = highlighted, onClick = onClick)
                        .size(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, label, Modifier.size(19.dp), tint = iconTint)
                    // The count, in the icon's top-right corner and drawn over it.
                    //
                    // Over the icon rather than beside the caption because the caption
                    // already has a width worked out from the window, and putting a number
                    // in it would change that arithmetic for every other button.
                    if (badge > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(13.dp)
                                .background(AppTheme.Palette.accent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (badge > 99) "99+" else badge.toString(),
                                fontSize = 8.sp,
                                lineHeight = 9.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
        // A small caption under the icon, as AB Download Manager labels its toolbar; a
        // narrow window drops it and the tooltip names the button instead.
        if (!compact) {
            Text(
                text = label.uppercase(),
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = Mono,
                    fontSize = 10.sp,
                    lineHeight = TOOLBAR_CAPTION_LINE_HEIGHT_SP.sp
                ),
                color = tint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
    }
}

/** Width of a captioned toolbar button: room for "RESUME ALL" in the mono face at 10 sp (6 dp a letter), no more. */
private const val TOOLBAR_CAPTIONED_DP = 66f

/** A thin rule between groups of toolbar actions. */
@Composable
private fun ToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(28.dp)
            .background(AppTheme.Palette.outline.copy(alpha = 0.35f))
    )
}

/** Table or Cards, as two icon toggles in the toolbar instead of a row of its own. */
@Composable
private fun ViewToggle(cardView: Boolean, onCardView: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.clip(shape).border(1.dp, AppTheme.Palette.outline.copy(alpha = 0.45f), shape)) {
        listOf(false to Lucide.List, true to Lucide.LayoutGrid).forEach { (cards, icon) ->
            val selected = cards == cardView
            Box(
                Modifier
                    .size(32.dp)
                    .background(if (selected) AppTheme.Palette.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onCardView(cards) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = if (cards) "Cards" else "Table",
                    tint = if (selected) AppTheme.Palette.accent else AppTheme.Palette.muted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
    private fun ColumnHeader(
        sort: LibrarySort,
        onSort: (LibrarySort) -> Unit,
        layout: TableLayout,
        widths: ColumnWidths,
        tableDp: Float,
        /** The rows the list is showing, which is what Select all covers. */
        visible: List<DownloadItem>,
        /** The ticked row ids. */
        selected: Set<String>,
        onSelectedChange: (Set<String>) -> Unit,
        /** Called when a handle is grabbed: notes the width to measure the drag from. */
        onDragStart: (DownloadColumn) -> Unit,
        onDrag: (DownloadColumn, Float) -> Unit
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A fixed height, and the reason is in LibraryLayout: the resize handles
            // inside this row fill its height, and a row with no height constraint hands
            // them the incoming maximum - the whole table. The header then swallowed the
            // list and every row and the status bar went off the bottom of the window.
            .height(HEADER_HEIGHT_DP.dp)
            .background(AppTheme.Palette.band)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Select all, over every row the list is actually showing.
        //
        // The rows can be picked one at a time from a tick box in the first column, and
        // there was no way to pick all of them - so acting on a whole queue meant
        // clicking every row, or Shift-clicking, which the row's own click handler knows
        // nothing about. Acting on a selection is the point of the tick boxes, and the
        // button that starts one is the one that was missing.
        //
        // It covers what is on screen rather than the whole queue, and says so when the
        // two differ: a button labelled "select all" that quietly selected a filtered
        // subset is worse than none, because the count in the badge afterwards does not
        // add up to the queue.
        SelectionBox(
            state = when {
                visible.isEmpty() -> SelectionBoxState.OFF
                // Every row on screen is ticked. Compared against the rows shown rather
                // than the selection, because a selection can hold ids from a filter that
                // has since changed, and those must not make the box look full.
                visible.all { it.id in selected } -> SelectionBoxState.ON
                selected.none { id -> visible.any { it.id == id } } -> SelectionBoxState.OFF
                else -> SelectionBoxState.MIXED
            },
            enabled = visible.isNotEmpty(),
            onClick = {
                // Already everything on screen means the next press is the one that
                // clears, which is the same two-state behaviour as a tick box.
                val allTicked = visible.isNotEmpty() && visible.all { it.id in selected }
                onSelectedChange(if (allTicked) emptySet() else visible.map { it.id }.toSet())
            }
        )
        // Every visible column, in order, at the width the user has set. The header and
        // the rows read the same numbers out of [ColumnWidths], which is the only way they
        // can stay lined up: a version that laid the two out independently had them
        // disagreeing by a few pixels, so the headers sat over the wrong columns.
        DownloadColumn.entries.forEach { column ->
            if (!ColumnDividers.isShown(column, layout)) return@forEach
            val width = ColumnDividers.resolvedWidthOf(layout, widths, tableDp, column)
            Box(Modifier.width(width.dp)) {
                ColumnHeaderCell(
                    label = column.label,
                    modifier = Modifier.fillMaxWidth(),
                    sort = sort,
                    column = column,
                    onSort = onSort
                )
                // The handle sits on the column's right edge, in the padding, so it does
                // not eat any of the caption's own width.
                ResizeHandle(
                  onPress = { onDragStart(column) },
                  onDelta = { delta -> onDrag(column, delta) },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/**
 * The grab area on a column's right edge.
 *
 * Its hit area is wider than the line it draws - 14 dp against 1 dp - because a one-pixel
 * drag target is a drag target nobody finds. A raw pointer handler rather than
 * `draggable`, because a drag is not a drag-and-drop here and `draggable` would fight the
 * header's own click-to-sort.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
    private fun ResizeHandle(
        /** Called once, when the button goes down: the width to measure the drag from. */
        onPress: () -> Unit,
        /** How far the pointer has travelled since that press, in dp. */
        onDelta: (Float) -> Unit,
        modifier: Modifier = Modifier
    ) {
        // AWT reports pointer positions in pixels; the columns are in dp. Reading one and
        // setting a column by the other is how a drag ends up overshooting by the
        // display's scale factor on anything not sitting at 100%.
        val density = LocalDensity.current.density
        Box(
            modifier
                .width(14.dp)
                // The full height of the header, so the target does not depend on where in
                // the 1 dp line the pointer happens to be.
                .fillMaxHeight()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val press = awaitPointerEvent()
                            if (!press.changes.any { it.changedToDown() }) continue
                            val mouse = press.nativeEvent as? java.awt.event.MouseEvent
                                ?: continue
                            if (mouse.button != java.awt.event.MouseEvent.BUTTON1) continue
                            val startX = mouse.x / density
                            // The press must not also reach the header cell, or every
                            // attempt to resize sorts the table instead.
                            press.changes.forEach { it.consume() }
                            onPress()

                            // Then track until the button comes up, wherever the pointer
                            // has gone.
                            //
                            // This is the whole fix, and it is why a plain
                            // onPointerEvent(Move) never felt right: that only fires while
                            // the pointer is inside these 14 dp. A drag is faster than its
                            // own handle, so the cursor left the target within a few
                            // milliseconds and the resize stopped dead part-way across -
                            // which is what "the drag is not proper" looks like. Having
                            // captured the press, this loop keeps receiving moves for as
                            // long as the button is down, inside the handle or not.
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.changedToUp() }) {
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                val moved = event.nativeEvent as? java.awt.event.MouseEvent
                                if (moved != null) {
                                    onDelta(moved.x / density - startX)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            // A grip, so the handle is something you can see rather than a line you have
            // to guess at. Three ticks and a rule, inside the target's own 14 dp - the
            // target keeps its full width either way, so this costs nothing to grab.
            Row(
                modifier = Modifier.width(10.dp).fillMaxHeight(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    repeat(3) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(1.dp)
                                .background(AppTheme.Palette.outlineVariant)
                        )
                    }
                }
                Spacer(Modifier.width(2.dp))
                Box(
                    Modifier
                        .width(1.dp)
                        .height(HEADER_HEIGHT_DP.dp)
                        .background(AppTheme.Palette.outlineVariant)
                )
            }
        }
    }
    /**
     * Whether a tick box is on, off, or neither - which is a third state and not a
     * decoration.
     *
     * A header over a partly-selected list needs to say so. Showing it empty says
     * "none of these are selected" while a third of them are, and showing it full says
     * the opposite. The mark in the middle is the only honest answer, and it is also the
     * one that tells you pressing it will select the rest rather than clear the lot.
     */
    private enum class SelectionBoxState { OFF, ON, MIXED }

    /**
     * A tick box in the header, over every row the list is showing.
     *
     * The same 26 by 16 box the rows draw, so the two line up and the header reads as the
     * column it heads rather than as a control sitting next to one.
     */
    @Composable
    private fun SelectionBox(
        state: SelectionBoxState,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        // Clickable across the full 26 dp column; the box drawn in it is square, as in the rows.
        Box(
            Modifier
                .width(26.dp)
                .height(16.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
        Box(
            Modifier
                .size(16.dp)
                .background(
                    when {
                        !enabled -> AppTheme.Palette.surface
                        state == SelectionBoxState.OFF -> AppTheme.Palette.surface
                        // A partly-selected box is drawn filled but without the tick,
                        // so it cannot be read as "all of them".
                        else -> AppTheme.Palette.accent
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                )
                .border(
                    1.dp,
                    if (state == SelectionBoxState.OFF || !enabled) AppTheme.Palette.outline else AppTheme.Palette.accent,
                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                SelectionBoxState.ON ->
                    Icon(
                        Lucide.Check, null, Modifier.size(11.dp),
                        tint = AppTheme.Palette.onAccent
                    )

                SelectionBoxState.MIXED -> Box(
                    Modifier
                        .width(7.dp)
                        .height(2.dp)
                        .background(
                            AppTheme.Palette.onAccent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                        )
                )

                SelectionBoxState.OFF -> Unit
            }
        }
        }
    }

    @Composable
private fun ColumnHeaderCell(
    label: String,
    modifier: Modifier = Modifier,
    sort: LibrarySort,
    column: DownloadColumn,
    onSort: (LibrarySort) -> Unit
) {
    val active = sort.column == column
    Row(
        modifier = modifier
            .clickable {
                onSort(
                    if (active) sort.toggled() else LibrarySort(column, SortDirection.ASCENDING)
                )
            }
            // Room on the right for the handle that overlaps this cell's edge.
            .padding(start = 4.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active) AppTheme.Palette.accent else AppTheme.Palette.muted
        )
        if (active) {
            Icon(
                if (sort.direction == SortDirection.ASCENDING) DlmIcons.ArrowUpward
                else DlmIcons.ArrowDownward,
                null,
                Modifier.size(11.dp),
                tint = AppTheme.Palette.accent
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    palette: androidx.compose.material3.ColorScheme,
    layout: TableLayout,
    /**
     * The widths the user has dragged the columns to, and the table's width to work them
     * out against. Read from the same [ColumnWidths] the header uses, which is the only
     * reason a caption stays over its own column after a drag.
     */
    widths: ColumnWidths,
    tableDp: Float,
    checked: Boolean,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    /** Opens the finished file itself, rather than its folder. */
    onOpenFile: () -> Unit,
    /** Opens this download's own settings. */
    onOptions: () -> Unit,
    /** Right-click: the menu every other torrent client opens. */
    onContext: (Int, Int) -> Unit = { _, _ -> }
) {
    val running = item.status == DownloadStatus.RUNNING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverFill(selected = checked)
            .clickable(onClick = onToggle)
            // The secondary button, read off the raw event rather than taken through
            // `combinedClickable`'s long-press.
            //
            // Compose Desktop treats a secondary press as the start of a long press and
            // holds it. That produced two failures from one cause: the context menu never
            // opened, and - because the same handler sat in the row's own chain - the pause
            // and gear buttons inside the row stopped responding too. `combinedClickable`
            // is not used anywhere in this row.
            .onPointerEvent(PointerEventType.Press) { event ->
                val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                // The button number, not `isRightButtonDown`: the flag is also set on the
                // release, and a context menu that opens again on mouse-up is worse than
                // one that opens once.
                if (mouse != null && mouse.button == java.awt.event.MouseEvent.BUTTON3) {
                    onContext(mouse.x, mouse.y)
                    event.changes.forEach { it.consume() }
                }
                // Double-click opens a finished file, as every file manager does.
                if (mouse != null && mouse.button == java.awt.event.MouseEvent.BUTTON1 &&
                    mouse.clickCount == 2 && item.status == DownloadStatus.COMPLETED
                ) {
                    onOpenFile()
                }
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The column stays 26 wide so it lines up with the header; the box drawn in it is
        // square, because a 26 by 16 box read as an empty pill rather than a tick box.
        Box(Modifier.width(26.dp).height(16.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(16.dp)
                    .background(
                        if (checked) AppTheme.Palette.accent else AppTheme.Palette.surface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    // An unticked box is surface on surface; the edge is the only thing that
                    // shows it is there.
                    .border(
                        1.dp,
                        if (checked) AppTheme.Palette.accent else AppTheme.Palette.outline,
                        androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) Icon(Lucide.Check, null, Modifier.size(12.dp), tint = AppTheme.Palette.onAccent)
            }
        }

        // The dragged width, not a weight. A weight cannot be dragged - the user has no
        // way to say "I want the name to be a third of this" and get it - and a weighted
        // name also cannot be lined up with a header cell that *is* a fixed width.
        Column(
            Modifier
                // Capped, so the row's own buttons always have room. Dragged to nine
                // tenths and then narrowed, they used to be pushed off the
                // right-hand end of the window with no way to reach them.
                .width(ColumnDividers.resolvedWidthOf(layout, widths, tableDp, DownloadColumn.NAME).dp)
                .padding(end = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A shape per kind of file, as in AB Download Manager's list.
                val kindIcon = LibraryCategory.entries
                    .firstOrNull { it != LibraryCategory.ALL && it.matches(item) }
                    ?.let(LibraryCategoryIcons::of)
                    ?: if (item.isTorrent) DlmIcons.Folder else DlmIcons.ArrowDownward
                Icon(
                    kindIcon,
                    null,
                    Modifier.size(16.dp),
                    tint = if (running) AppTheme.Palette.accent else AppTheme.Palette.muted
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        item.fileName,
                        fontSize = 12.sp,
                        color = palette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (item.totalBytes > 0 && item.status != DownloadStatus.COMPLETED) {
                            DisplayFormat.bytes(item.bytesDownloaded) + " of " + DisplayFormat.bytes(item.totalBytes)
                        } else {
                            item.category.name.lowercase().replaceFirstChar { it.uppercase() }
                        },
                        fontSize = 10.sp,
                        color = palette.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        // The same numbers the header used, from the same object. That is what keeps a
        // column's caption over its own contents after it has been dragged.
        if (layout.size > 0.dp) {
            Cell(
                DisplayFormat.bytes(item.totalBytes),
                ColumnDividers.resolvedWidthOf(layout, widths, tableDp, DownloadColumn.SIZE).dp,
                palette
            )
        }
        if (layout.showStatus) {
            StatusCell(
                item,
                ColumnDividers.resolvedWidthOf(layout, widths, tableDp, DownloadColumn.STATUS).dp,
                statusColour(item.status, palette)
            )
        }
        if (layout.showSpeed) {
            Cell(
                DisplayFormat.speed(item.speedBytesPerSecond),
                ColumnDividers.resolvedWidthOf(layout, widths, tableDp, DownloadColumn.SPEED).dp,
                palette
            )
        }
        if (layout.showTimeLeft) {
            Cell(
                DisplayFormat.timeLeft(DownloadLibrary.estimateSecondsLeft(item)),
                ColumnDividers.resolvedWidthOf(layout, widths, tableDp, DownloadColumn.TIME_LEFT).dp,
                palette
            )
        }
        if (layout.showDateAdded) {
            Cell(
                DisplayFormat.timeAgo(item.createdAt),
                ColumnDividers.resolvedWidthOf(layout, widths, tableDp, DownloadColumn.DATE_ADDED).dp,
                palette
            )
        }

        if (layout.showRowActions) {
            Row(
                Modifier.width(ROW_ACTION_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.RESOLVING ->
                        IconButton(16.dp, DlmIcons.Pause, "Pause", onPause)

                    DownloadStatus.PAUSED -> IconButton(16.dp, Lucide.Play, "Resume", onResume)
                    DownloadStatus.FAILED -> IconButton(16.dp, Lucide.RotateCw, "Retry", onRetry)
                    DownloadStatus.COMPLETED -> IconButton(16.dp, DlmIcons.FolderOpen, "Show in folder", onOpen)
                }
                // Always present, unlike the status button above, because these settings
                // are wanted on a finished download (to set a share limit) as much as on
                // a running one - and on a paused one more than anything else.
                IconButton(16.dp, Lucide.Settings, "Download options", onOptions)
            }
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    palette: androidx.compose.material3.ColorScheme,
    colour: Color? = null
) {
    // Every cell here is a number - size, speed, time left, age - set in the mono face so
    // a column of them lines up digit for digit and a ticking speed holds still.
    Text(
        text,
        fontSize = 11.sp,
        fontFamily = Mono,
        color = colour ?: palette.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 4.dp)
    )
}

/**
 * A small icon button on a row.
 *
 * The `onClick` used to be accepted and then ignored - the composable drew the icon and
 * nothing else, which is why the pause, retry and gear buttons on every row did nothing
 * when pressed. The icon is drawn at [size] and the whole button is a 24 dp target, so it
 * can actually be hit with a mouse.
 */
@Composable
private fun IconButton(
    size: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, Modifier.size(size), tint = AppTheme.Palette.muted)
    }
}

private fun statusColour(status: DownloadStatus, palette: androidx.compose.material3.ColorScheme): Color =
    when (status) {
        // Neutral, as on Android: a finished download is the normal state, and only what
        // is moving gets the accent. Failed is the one red.
        DownloadStatus.COMPLETED -> palette.onSurfaceVariant
        DownloadStatus.RUNNING -> AppTheme.Palette.accent
        DownloadStatus.FAILED -> palette.error
        else -> palette.onSurfaceVariant
    }

/**
 * What an empty list says. A blank queue gets the two ways in; a filter that matches
 * nothing says so, because "nothing here" on a full queue reads as lost downloads.
 */
@Composable
private fun EmptyList(filtered: Boolean, onNew: () -> Unit, onFind: () -> Unit, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // A meter with nothing on it, sweeping like a tuner looking for a station.
        val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "no-signal").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2400)),
            label = "sweep"
        )
        Text(
            if (filtered) "NO MATCH" else "NO SIGNAL",
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            letterSpacing = 4.sp,
            color = AppTheme.Palette.onSurface
        )
        Spacer(Modifier.height(10.dp))
        SignalBar(sweep, AppTheme.Palette.faint, live = false, modifier = Modifier.width(220.dp).height(8.dp), segments = 20)
        Spacer(Modifier.height(14.dp))
        Text(
            if (filtered) "Nothing matches this filter" else "Nothing downloading yet",
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.Palette.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (filtered) "Pick All Downloads in the sidebar to see everything."
            else "Paste a link or magnet, open a .torrent, or search for one. Ctrl+N works too.",
            fontSize = 12.sp,
            color = AppTheme.Palette.muted
        )
        if (!filtered) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("New download", fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onAccent, modifier = Modifier.keycap(accent = true, onClick = onNew).padding(horizontal = 18.dp, vertical = 9.dp))
                Text("Find torrents", fontWeight = FontWeight.SemiBold, color = AppTheme.Palette.onSurface, modifier = Modifier.keycap(onClick = onFind).padding(horizontal = 18.dp, vertical = 9.dp))
            }
        }
    }
}

/** Hands a finished file to whatever Windows opens it with. Off the UI thread: the shell can stall. */
internal fun openWithSystem(path: String?) {
    val file = path?.let { java.io.File(it) }?.takeIf { it.exists() } ?: return
    Thread { runCatching { java.awt.Desktop.getDesktop().open(file) } }.start()
}


/**
 * One strip along the bottom: the transfer readout on the left, the last message on the
 * right. There used to be two stacked strips that each said half of this.
 */
@Composable
private fun StatusBar(state: DesktopUiState, all: List<DownloadItem>, onToggleAltSpeed: () -> Unit = {}) {
    Surface(
        color = AppTheme.Palette.band,
        // A hairline above, so the strip reads as the card's footer rather than its last row.
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.Palette.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The readout keeps its natural width and the message takes what is left. The
            // message had no weight, so a long one squeezed the readout until every word
            // wrapped one letter per line.
            TorrentStatusBar(all)
            Spacer(Modifier.width(16.dp))
            Text(
                state.message.orEmpty(),
                fontSize = 11.sp,
                color = AppTheme.Palette.accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            // qBittorrent's turtle: alternative speed limits, on and off with one click.
            // Lit when it applies, whether switched on by hand or by its schedule.
            val altOn = state.settings.altSpeedActive()
            Text(
                if (altOn) {
                    "Alt speed ↓" + DisplayFormat.bytes(state.settings.altDownloadLimitBytesPerSecond) + "/s"
                } else {
                    "Alt speed off"
                },
                fontSize = 11.sp,
                color = if (altOn) AppTheme.Palette.onAccent else AppTheme.Palette.muted,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (altOn) AppTheme.Palette.accent else Color.Transparent)
                    .border(1.dp, if (altOn) AppTheme.Palette.accent else AppTheme.Palette.outlineVariant, RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleAltSpeed)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${DownloadLibrary.activeCount(all)} active",
                fontSize = 11.sp,
                color = AppTheme.Palette.muted,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

internal fun QueuedDownload.toCoreItem() = DownloadItem(
    id = id,
    url = url,
    fileName = fileName,
    source = source,
    status = status,
    category = category,
    bytesDownloaded = bytesDownloaded,
    totalBytes = totalBytes,
    speedBytesPerSecond = speedBytesPerSecond,
    errorMessage = errorMessage,
    location = location,
    quality = quality,
    audioFormat = audioFormat,
    playlist = playlist,
    createdAt = createdAt,
    torrentFilePath = torrentFilePath,
    torrentInfoHash = torrentInfoHash,
    outputPath = outputPath,
    priority = DownloadPriority.fromRank(priorityRank),
    speedLimitBytesPerSecond = speedLimitBytesPerSecond,
    startAfterEpochMillis = startAfterEpochMillis,
    shareRatioLimit = shareRatioLimit,
    seedTimeLimitMinutes = seedTimeLimitMinutes,
    seedingSinceEpochMillis = seedingSinceEpochMillis,
    seedingStoppedAtEpochMillis = seedingStoppedAtEpochMillis,
    torrentSelectedFiles = torrentSelectedFiles,
    torrentFilePriorities = torrentFilePriorities,
    torrentSequential = torrentSequential,
    torrentFirstLastPiecesFirst = torrentFirstLastPiecesFirst,
    torrentContentFolder = torrentContentFolder,
    uploadRate = uploadRate,
    seeds = seeds,
    peerCount = peerCount,
    uploadedBytes = uploadedBytes,
    completedAt = completedAt,
    queueId = queueId,
    request = com.downloadhub.core.HttpRequestOptions(requestHeaders, cookies, username, password)
)

/** How long a status message stays before clearing itself. */
private const val MESSAGE_SHOWN_MILLIS = 6_000L

/**
 * The instrument panel above the list, as on the Android app: status LEDs, the total speed
 * on an LCD with its last half-minute as LED columns, the counts as readouts, and the free
 * space where downloads land.
 */
@Composable
private fun InstrumentPanel(all: List<DownloadItem>, downloadDir: String) {
    val speed = all.filter { it.status == DownloadStatus.RUNNING }.sumOf { it.speedBytesPerSecond }
    val active = all.count { it.isActive }
    val queued = all.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.PAUSED }
    val done = all.count { it.status == DownloadStatus.COMPLETED }
    val rate by rememberUpdatedState(speed)
    var history by remember { mutableStateOf(List(40) { 0L }) }
    LaunchedEffect(Unit) {
        while (true) {
            history = history.drop(1) + rate
            kotlinx.coroutines.delay(1000)
        }
    }
    var free by remember(downloadDir) { mutableStateOf(-1L) }
    LaunchedEffect(downloadDir, done) {
        free = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.io.File(downloadDir).usableSpace }.getOrDefault(-1L)
        }
    }
    val p = AppTheme.Palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(p.band)
            .border(1.dp, p.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusLed("RUN", active > 0)
            StatusLed("QUEUE", queued > 0)
            StatusLed("IDLE", active == 0)
        }
        Column {
            Text("↓ SPEED", fontFamily = Mono, fontSize = 10.sp, color = p.muted)
            Spacer(Modifier.height(3.dp))
            Lcd(DisplayFormat.speed(speed).ifEmpty { "0 B/s" }, ghost = "8888.8 MB/s", fontSize = 18.sp)
        }
        SpeedTrace(history, Modifier.weight(1f).height(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Readout("ACT", active, p.accent)
            Readout("QUE", queued, p.onSurface)
            Readout("DONE", done, p.muted)
        }
        if (free >= 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text("FREE", fontFamily = Mono, fontSize = 10.sp, color = p.muted)
                Text(DisplayFormat.bytes(free), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = p.onSurface)
            }
        }
    }
}

/** One counter: a small mono tag and a zero-padded number. */
@Composable
private fun Readout(tag: String, value: Int, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tag, fontFamily = Mono, fontSize = 10.sp, color = AppTheme.Palette.muted, modifier = Modifier.width(34.dp))
        Text(value.toString().padStart(2, '0'), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint)
    }
}
