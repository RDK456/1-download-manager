package com.downloadhub.desktop

import com.downloadhub.core.ContentNode
import com.downloadhub.core.ContentRow
import com.downloadhub.core.contentTree
import com.downloadhub.core.visibleContentNodes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.downloadhub.core.DisplayFormat
import androidx.compose.foundation.border
import com.downloadhub.core.FilePriority
import java.util.Locale

/**
 * The torrent's file list, as a tree.
 *
 * The shape the reference clients use, and the only one that reads: folders that can be
 * opened, files indented inside them, and a size against every line. A release torrent
 * names its files identically and differs only at the end, so a flat list of those names
 * is thirteen rows of the same sentence - which is what this replaced.
 *
 * Columns are the ones asked for: name, total size, progress, download priority, and
 * what is still to come. Priority is a fixed "Normal" rather than an editable column,
 * because choosing a priority per file is not in this build and an editable column that
 * quietly does nothing is worse than showing the one thing that is true.
 */
@Composable
fun ContentTreeList(
    modifier: Modifier = Modifier,
    rows: List<ContentRow>,
    /**
     * Hoisted, not local. The dialog turns this into the request's file selection, so it
     * has to be the same value the boxes are drawing - a list that kept its own copy
     * would show ticks that the Add button then ignored.
     */
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    filter: String,
    onFilter: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    /** Used by the detail pane, which reports the selection rather than editing it. */
    readOnly: Boolean = false,
    /**
     * Bytes fetched per file, keyed by index.
     *
     * Read from the engine on every poll and held on the item, so the bar beside a file's
     * size is the same second as the speed in the row above rather than a second reading
     * that can disagree with it.
     */
    downloadedBytes: Map<Int, Long> = emptyMap(),
    /**
     * Sets one file's priority, or null where the list cannot change it.
     *
     * Null rather than a no-op callback, so read-only really is read-only: the control
     * disappears instead of accepting the click and doing nothing.
     */
    onFilePriority: ((Int, FilePriority) -> Unit)? = null,

    /** A file's current priority, keyed by index. Absent means Normal. */
    filePriorities: Map<Int, FilePriority> = emptyMap(),

    /**
     * Whether to draw the toolbar above the headings.
     *
     * False where the caller has put a filter on screen of its own. It was drawn either
     * way, so the detail pane ended up with two filter boxes and two counts: the one that
     * worked and the one that had nothing to filter.
     */
    chrome: Boolean = true
) {
    // Which folders are open. Nothing starts open, because a release with four hundred
    // files is not readable all at once - but the folders are one click away and each
    // says how much is in it.
    //
    // The top level starts open.
    //
    // Collapsed by default it showed a single row - the release folder - which is a list
    // you cannot choose anything from, which is the whole reason the list exists.
    var expanded by remember(rows) {
        mutableStateOf(rows.mapNotNull { it.path.substringBefore('/', "").ifEmpty { null } }.toSet())
    }

    // The filter is applied before the tree is built, so a filter matching one file
    // inside a folder still shows the folder - with only that file in it - rather than
    // hiding the file behind a folder the filter had no way to open.
    val nodes = remember(rows, filter) {
        val needle = filter.trim().lowercase(Locale.US)
        val kept = if (needle.isEmpty()) {
            rows
        } else {
            rows.filter { it.path.lowercase(Locale.US).contains(needle) }
        }
        contentTree(kept)
    }
    val shown = remember(nodes, expanded) { visibleContentNodes(nodes, expanded) }

    /**
     * Every file in the list, whether or not a row is showing.
     *
     * "Set priority" with nothing ticked means every file, and it has to mean every file
     * rather than every visible row - otherwise it sets twelve files when the filter has
     * narrowed the list to one and the other eleven keep downloading.
     */
    val allFileIndices = remember(rows) {
        visibleContentNodes(contentTree(rows), emptySet()).mapNotNull { node ->
            (node.first as? ContentNode.File)?.index
        }.toSet()
    }

    Column(modifier.fillMaxWidth()) {
        if (chrome) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!readOnly) {
                TreeButton("Select All", onSelectAll)
                TreeButton("Select None", onSelectNone)
            }
            // "Set priority", as qBittorrent has it: one control that applies to whatever
            // is ticked, or to every file when nothing is.
            if (onFilePriority != null) {
                var priorityMenu by remember { mutableStateOf(false) }
                Box {
                    TreeButton(
                        if (selected.isEmpty()) "Set priority" else "Set priority on ${selected.size}",
                        { priorityMenu = true }
                    )
                    if (priorityMenu) {
                        Box(Modifier.fillMaxSize().background(AppTheme.Palette.surface)) {
                            PriorityMenu(onPick = { chosen ->
                                priorityMenu = false
                                // The ticked files if there are any, and every file if
                                // none are - which is what the control says when nothing
                                // is ticked, so an empty selection cannot quietly become
                                // "set nothing".
                                val targets = if (selected.isEmpty()) allFileIndices else selected
                                for (index in targets) onFilePriority.invoke(index, chosen)
                            })
                        }
                    }
                }
            }
            // Weighted with a cap rather than a fixed width. It was 170 dp unconditionally,
            // so in a narrow window the three things on this line asked for more than the
            // pane had and the filter box - the widest of them - was pushed off the right
            // edge. It now takes what is going and gives it back as the window grows.
            OutlinedTextField(
                value = filter,
                onValueChange = onFilter,
                label = { Text("Filter files", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 90.dp, max = 170.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        }
        // Measured, so the headings can be fitted to the same widths the rows use. A header
        // that is a fixed set of widths and rows that are not is a header over the wrong
        // columns.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = TreeLayout.columnsFor(maxWidth.value)
            Row(
                Modifier
                    .fillMaxWidth()
                    // The main window's own header colour, not the theme's surfaceVariant.
                    // That is a lighter grey than the surface it sits on, and against this
                    // window's near-black it read as a lit panel rather than a heading -
                    // the brightest thing in the dialog, which is the opposite of what a
                    // heading should be.
                    .background(TREE_BAND, RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                TreeHeader("Name", Modifier.weight(1f))
                TreeHeader("Size", Modifier.width(cols.size.dp).padding(end = 5.dp))
                if (cols.showsProgress) TreeHeader("Prog", Modifier.width(cols.progress.dp).padding(end = 5.dp))
                if (cols.showsPriority) TreeHeader("Pri", Modifier.width(cols.priority.dp).padding(end = 5.dp))
                if (cols.showsRemaining) TreeHeader("Remain", Modifier.width(cols.remaining.dp).padding(end = 5.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                // Weight rather than a fixed height: the dialog is resizable now, and a
                // fixed 200 dp list left a taller dialog half empty.
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (shown.isEmpty()) {
                Text(
                    if (rows.isEmpty()) "This torrent lists no files." else "No files match that filter.",
                    fontSize = 11.sp,
                    color = TREE_SECONDARY,
                    modifier = Modifier.padding(10.dp)
                )
            } else {
                LazyColumn {
                    // Keyed on the full path. In a tree that is unique even when two files
                    // share a name, which is the whole reason the flat list's key - the
                    // file index - no longer works here: a folder has no index at all.
                    items(shown, key = { (node, _) ->
                        // A file is identified by its index and a folder by its path,
                        // and the two cannot collide. The path alone is not a key: a
                        // torrent may list the same path twice, the tree keeps both, and
                        // a duplicated key throws while the list is being laid out.
                        when (node) {
                            is ContentNode.File -> "f" + node.index
                            is ContentNode.Folder -> "d" + node.fullPath
                        }
                    }) { (node, depth) ->
                        // Measured per row rather than once per list, so a row laid out
                        // during a drag is already at the width the row after it will be.
                        // Measuring once and caching it is what produced the state where
                        // the heading had four columns and the rows had two.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val cols = TreeLayout.columnsFor(maxWidth.value)
                        when (node) {
                            is ContentNode.Folder -> TreeFolderRow(
                                node = node,
                                depth = depth,
                                cols = cols,
                                selected = selected,
                                open = node.fullPath in expanded,
                                readOnly = readOnly,
                                onOpen = {
                                    expanded = if (node.fullPath in expanded) {
                                        expanded - node.fullPath
                                    } else {
                                        expanded + node.fullPath
                                    }
                                },
                                onToggle = {
                                    // A folder's tick is all-or-nothing across everything
                                    // beneath it, because half a folder is not a thing
                                    // anyone means to ask for.
                                    val allIn = node.fileIndices.all { it in selected }
                                    onSelectionChange(
                                        if (allIn) {
                                            selected - node.fileIndices.toSet()
                                        } else {
                                            selected + node.fileIndices
                                        }
                                    )
                                }
                            )

                            is ContentNode.File -> TreeFileRow(
                                node = node,
                                depth = depth,
                                cols = cols,
                                selected = node.index in selected,
                                downloadedBytes = downloadedBytes,
                                priority = filePriorities[node.index] ?: FilePriority.NORMAL,
                                onPriority = onFilePriority?.let { change ->
                                    { chosen -> change(node.index, chosen) }
                                },
                                readOnly = readOnly,
                                onToggle = {
                                    onSelectionChange(
                                        if (node.index in selected) {
                                            selected - node.index
                                        } else {
                                            selected + node.index
                                        }
                                    )
                                }
                            )
                        }
                        }
                    }
                }
            }
        }
    }
}

/** One folder, with its own tick, its own size, and its own open/closed triangle. */
@Composable
private fun TreeFolderRow(
    node: ContentNode.Folder,
    depth: Int,
    cols: TreeColumns,
    selected: Set<Int>,
    open: Boolean,
    readOnly: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (readOnly) Modifier else Modifier.clickable { onToggle() })
            // 14 dp a level, so a file three folders down is visibly inside them rather
            // than merely listed after them.
            .padding(start = (4 + depth * 14).dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (readOnly) {
            Spacer(Modifier.width(13.dp))
        } else {
            TickBox(checked = node.isFullySelected(selected), onChange = { onToggle() }, size = 12.dp)
        }
        // Its own small target, and it does not toggle the tick: opening a folder and
        // downloading it are different questions.
        Box(
            Modifier
                .width(15.dp)
                .clickable { onOpen() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (open) "v" else ">",
                fontSize = 9.sp,
                color = TREE_SECONDARY
            )
        }
        // The count rides with the name. It was in the Size column, and the total in
        // Remaining, so a folder read "12  3.68 GB" against a file's "3.68 GB  3.68 GB
        // 100%" - a row whose numbers meant something different from every other row's.
        Row(
            Modifier.weight(1f).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                node.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                "  ${node.fileIndices.size} items",
                fontSize = 10.sp,
                color = TREE_SECONDARY,
                maxLines = 1
            )
        }
        // A folder's size is its own, so it belongs in Total Size like a file's.
        Text(
            DisplayFormat.bytes(node.totalSize),
            fontSize = 11.sp,
            color = TREE_SECONDARY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(cols.size.dp).padding(end = 5.dp)
        )
        // Nothing has been fetched, and a folder has no priority of its own: a dash
        // rather than a number that would be a lie about a folder of twelve files. A
        // column that is not drawn at all draws nothing, rather than a dash in a gap.
        if (cols.showsProgress) {
            Text("-", fontSize = 11.sp, color = TREE_SECONDARY, modifier = Modifier.width(cols.progress.dp).padding(end = 5.dp))
        }
        if (cols.showsPriority) {
            Text("-", fontSize = 11.sp, color = TREE_SECONDARY, modifier = Modifier.width(cols.priority.dp).padding(end = 5.dp))
        }
        if (cols.showsRemaining) {
            Text("-", fontSize = 11.sp, color = TREE_SECONDARY, modifier = Modifier.width(cols.remaining.dp).padding(end = 5.dp))
        }
    }
}

/** One file, indented inside whatever folders contain it. */
@Composable
private fun TreeFileRow(
    node: ContentNode.File,
    depth: Int,
    cols: TreeColumns,
    selected: Boolean,
    readOnly: Boolean,
    /** Bytes fetched for this file, keyed by index, or empty when nothing is known yet. */
    downloadedBytes: Map<Int, Long>,
    priority: FilePriority,
    onToggle: () -> Unit,
    onPriority: ((FilePriority) -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (readOnly) Modifier else Modifier.clickable { onToggle() })
            .padding(start = (19 + depth * 14).dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (readOnly) {
            Spacer(Modifier.width(13.dp))
        } else {
            TickBox(checked = selected, onChange = { onToggle() }, size = 12.dp)
        }
        Spacer(Modifier.width(5.dp))
        Text(
            node.label,
            fontSize = 11.sp,
            color = TREE_PRIMARY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 4.dp)
        )
        Text(
            if (node.size > 0) DisplayFormat.bytes(node.size) else "-",
            fontSize = 11.sp,
            color = TREE_SECONDARY,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(cols.size.dp).padding(end = 5.dp)
        )
        if (cols.showsProgress) {
            // A bar and a number, because a percentage with no bar does not say whether
            // it is nearly done or barely started - both read as "12%".
            val done = downloadedBytes[node.index] ?: 0L
            val fraction = if (node.size > 0) {
                (done.toDouble() / node.size).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            Row(
                Modifier.width(cols.progress.dp).padding(end = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(TREE_BAND, RoundedCornerShape(3.dp))
                ) {
                    if (fraction > 0.0) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.toFloat())
                                .fillMaxHeight()
                                .background(AppTheme.Palette.accent, RoundedCornerShape(3.dp))
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "${(fraction * 100).toInt()}%",
                    fontSize = 9.sp,
                    color = TREE_SECONDARY,
                    maxLines = 1,
                    modifier = Modifier.width(30.dp)
                )
            }
        }
        if (cols.showsPriority) {
            PriorityCell(
                priority = priority,
                width = cols.priority.dp,
                enabled = !readOnly && onPriority != null,
                onChange = onPriority
            )
        }
        if (cols.showsRemaining) {
            Text(
                if (node.size > 0) DisplayFormat.bytes(node.size) else "-",
                fontSize = 11.sp,
                color = TREE_SECONDARY,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(cols.remaining.dp).padding(end = 5.dp)
            )
        }
    }
}

/**
 * One file's priority, as a control rather than a word.
 *
 * qBittorrent's own five, in its own order, and it opens on click rather than needing a
 * long press or a right-click - because a list of twelve files is exactly the case where
 * right-clicking each in turn to find one setting is the tedious path.
 *
 * Read-only it is plain text. A control that looks live and does nothing is worse than a
 * word that says what it is.
 */
@Composable
private fun PriorityCell(
    priority: FilePriority,
    width: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onChange: ((FilePriority) -> Unit)?
) {
    var open by remember { mutableStateOf(false) }
    val tint = when (priority) {
        FilePriority.SKIP -> AppTheme.Palette.faint
        FilePriority.HIGH, FilePriority.MAXIMUM -> AppTheme.Palette.accent
        else -> TREE_SECONDARY
    }
    Box(Modifier.width(width).padding(end = 5.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (enabled) {
                        Modifier
                            .clickable { open = true }
                            .background(AppTheme.Palette.raised, RoundedCornerShape(3.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                priority.label,
                fontSize = 10.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (enabled) Text("v", fontSize = 8.sp, color = TREE_SECONDARY)
        }
        if (open) {
            // Anchored inside the list's own box, like the context menu: it cannot land
            // outside the window, and it needs nothing from the window manager to know
            // where to be.
            Box(Modifier.fillMaxSize().background(AppTheme.Palette.surface)) {
                Box(Modifier.padding(2.dp)) {
                    Column(
                        Modifier
                            .width(width + 30.dp)
                            .background(AppTheme.Palette.menuPanel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppTheme.Palette.menuEdge, RoundedCornerShape(4.dp))
                            .padding(vertical = 2.dp)
                    ) {
                        FilePriority.entries.forEach { option ->
                            Text(
                                option.label,
                                fontSize = 10.sp,
                                color = if (option == priority) AppTheme.Palette.accent else TREE_PRIMARY,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        open = false
                                        onChange?.invoke(option)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


/** The five priorities, as a list. The one control the toolbar opens. */
@Composable
private fun PriorityMenu(onPick: (FilePriority) -> Unit) {
    Column(
        Modifier
            .width(112.dp)
            .background(AppTheme.Palette.menuPanel, RoundedCornerShape(4.dp))
            .border(1.dp, AppTheme.Palette.menuEdge, RoundedCornerShape(4.dp))
            .padding(vertical = 3.dp)
    ) {
        FilePriority.entries.forEach { option ->
            Text(
                option.label,
                fontSize = 11.sp,
                color = TREE_PRIMARY,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
@Composable
private fun TreeHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = TREE_HEADER,
        modifier = modifier
    )
}

@Composable
private fun TreeButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = TREE_PRIMARY,
        modifier = Modifier
            .clickable(onClick = onClick)
            // A hair above the surface, not the theme's surfaceVariant. On this dark
            // scheme surfaceVariant is lighter than the pane behind it, so two small
            // buttons sat on a lit slab - which read as "these are the important
            // controls", when they are the ones you need least.
            .background(TREE_BAND, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * The file list, for a caller that only reports the selection.
 *
 * A thin wrapper over [ContentTreeList] so the detail pane's Content tab and the
 * pre-download dialog show the same tree and the one that only reports it cannot be
 * mistaken for the one that edits it.
 */
@Composable
fun ContentFileList(
    rows: List<ContentRow>,
    filter: String,
    onFilter: (String) -> Unit,
    selected: Set<Int>,
    onSelectionChange: (Set<Int>) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    readOnly: Boolean = false,
    chrome: Boolean = true,
    downloadedBytes: Map<Int, Long> = emptyMap(),
    onFilePriority: ((Int, FilePriority) -> Unit)? = null,
    filePriorities: Map<Int, FilePriority> = emptyMap()
) {
    ContentTreeList(
        rows = rows,
        selected = selected,
        onSelectionChange = onSelectionChange,
        filter = filter,
        onFilter = onFilter,
        onSelectAll = onSelectAll,
        onSelectNone = onSelectNone,
        readOnly = readOnly,
        chrome = chrome,
        downloadedBytes = downloadedBytes,
        onFilePriority = onFilePriority,
        filePriorities = filePriorities
    )
}

/**
 * The file list's own two greys.
 *
 * Taken from the theme's `onSurfaceVariant`, which on this dark scheme is too dim to read
 * a filename against - the list looked disabled. These are the same values the main
 * window's rows use, so a file list and a download list are legibly the same surface.
 */
private val TREE_PRIMARY: Color get() = AppTheme.Palette.onSurface
private val TREE_SECONDARY: Color get() = AppTheme.Palette.muted
private val TREE_HEADER: Color get() = AppTheme.Palette.muted

/**
 * The band behind the headings and the two small buttons.
 *
 * The main window's own header colour, and slightly under its surface, so a header is the
 * quietest thing on the pane rather than the brightest.
 */
private val TREE_BAND: Color get() = AppTheme.Palette.band
