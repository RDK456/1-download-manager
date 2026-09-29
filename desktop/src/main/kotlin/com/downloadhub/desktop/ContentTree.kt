package com.downloadhub.desktop

import java.util.Locale

/**
 * One line of a torrent's file list: a folder, or a file.
 *
 * A torrent is a tree, and showing it as a flat list of full paths is what made the list
 * unreadable. A thirteen-file release rendered as thirteen rows all beginning
 * "[Judas] Chainsaw Man (Season 1) [1080p][HEVC x265 10bit][Multi-Subs]", differing only
 * in the last few characters, with nothing to say they were ever one folder of episodes.
 *
 * Every torrent client and every file explorer shows the tree instead, and so does this.
 */
sealed interface ContentNode {
    val label: String

    /** The path from the torrent's root, used for matching a filter against. */
    val fullPath: String

    /**
     * A folder.
     *
     * Carries every file index beneath it, not just its own, so ticking a folder ticks
     * what is inside it and a collapsed folder can still say how big it is.
     */
    data class Folder(
        override val label: String,
        override val fullPath: String,
        val children: List<ContentNode>,
        val fileIndices: List<Int>,
        val totalSize: Long,
        /** The indices of the folders directly inside this one, for the tree's own tests. */
        val folderPaths: List<String>
    ) : ContentNode {
        /** True when everything beneath it is ticked, which is what its tick box shows. */
        fun isFullySelected(selected: Set<Int>): Boolean =
            fileIndices.isNotEmpty() && fileIndices.all { it in selected }
    }

    /** A file. */
    data class File(
        override val label: String,
        override val fullPath: String,
        val index: Int,
        val size: Long
    ) : ContentNode
}

/**
 * Folds a torrent's file list into a tree.
 *
 * The insertion walks each path segment by segment into a mutable tree, then freezes it.
 * A torrent lists its files in whatever order the creator's client chose, which is not
 * sorted, and the tree has to be for the list to be readable - folders first, then files,
 * each alphabetically - so the order they arrive in cannot be relied on.
 *
 * A file sitting directly at the torrent's root has a one-segment path and becomes a
 * top-level node. There is no synthetic folder wrapped around everything: a single-file
 * torrent is one row, and a multi-file one that lists its files at the root is a list, not
 * a folder with one thing in it.
 */
/**
 * Walks a frozen list of nodes, gathering the file indices and sizes beneath it.
 *
 * Top level rather than nested, because a local function has to be declared before the
 * point it is used and the node class below uses it from inside itself.
 */
private fun collectContent(
    nodes: List<ContentNode>,
    indices: MutableList<Int>,
    folders: MutableList<String>,
    accumulator: (Long) -> Unit
) {
    nodes.forEach { node ->
        when (node) {
            is ContentNode.Folder -> {
                folders += node.fullPath
                collectContent(node.children, indices, folders, accumulator)
            }
            is ContentNode.File -> {
                indices += node.index
                accumulator(node.size)
            }
        }
    }
}

fun contentTree(rows: List<ContentRow>): List<ContentNode> {
    // A working node, so inserting a file does not rebuild everything above it.
    class Node(val label: String, val fullPath: String) {
        val folders = LinkedHashMap<String, Node>()
        val files = mutableListOf<ContentRow>()

        fun add(row: ContentRow) {
            val segments = row.path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return
            var node = this
            segments.dropLast(1).forEach { segment ->
                val path = if (node.fullPath.isEmpty()) segment else "${node.fullPath}/$segment"
                node = node.folders.getOrPut(segment) { Node(segment, path) }
            }
            node.files += row
        }

        fun freeze(): List<ContentNode> {
            val folderNodes = folders.values
                .sortedBy { it.label.lowercase(Locale.US) }
                .map { folder -> folder.toNode() }
            val fileNodes = files
                .sortedBy { it.name.lowercase(Locale.US) }
                .map { ContentNode.File(it.name, it.path, it.index, it.size) }
            // Folders before files, which is how a file explorer lists a folder and how
            // the two can be told apart at a glance.
            return folderNodes + fileNodes
        }

        private fun toNode(): ContentNode.Folder {
            val children = freeze()
            val indices = mutableListOf<Int>()
            val folders = mutableListOf<String>()
            var size = 0L
            collectContent(children, indices, folders) { size += it }
            return ContentNode.Folder(
                label = label,
                fullPath = fullPath,
                children = children,
                fileIndices = indices,
                totalSize = size,
                folderPaths = folders
            )
        }
    }


    val root = Node("", "")
    rows.forEach { root.add(it) }
    return root.freeze()
}

/**
 * The lines to draw, given the tree and which folders are open.
 *
 * Folders that are not open contribute themselves and nothing below them. Filtering is
 * applied before the tree is built rather than after, so a filter that matches one file
 * inside a folder shows the folder - with only that file in it - instead of hiding the
 * file behind a collapsed folder the filter had no way to open.
 */
fun visibleContentNodes(
    nodes: List<ContentNode>,
    expanded: Set<String>,
    depth: Int = 0
): List<Pair<ContentNode, Int>> = buildList {
    nodes.forEach { node ->
        add(node to depth)
        if (node is ContentNode.Folder && node.fullPath in expanded) {
            addAll(visibleContentNodes(node.children, expanded, depth + 1))
        }
    }
}
