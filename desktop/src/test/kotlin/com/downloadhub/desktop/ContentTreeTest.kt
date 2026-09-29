package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-download dialog's file list, as a tree.
 *
 * Found by opening the dialog on a real thirteen-file release: it rendered as thirteen
 * flat rows all beginning "[Judas] Chainsaw Man (Season 1) [1080p][HEVC x265 10bit]",
 * differing only in the last few characters, with nothing to indicate they were one
 * folder of episodes.
 */
class ContentTreeTest {

    private fun row(path: String, index: Int, size: Long = 100L) = ContentRow(path, index, size)

    private fun rows(vararg paths: String): List<ContentRow> =
        paths.mapIndexed { position, path -> row(path, position, 100L * (position + 1)) }

    @Test
    fun aFlatTorrentIsAListAndNotAFolderWithOneThingInIt() {
        val tree = contentTree(rows("readme.txt", "setup.exe"))
        assertEquals(2, tree.size)
        assertTrue(
            "there is no folder to wrap them in: ${tree.map { it.label }}",
            tree.all { it is ContentNode.File }
        )
    }

    @Test
    fun aFolderBecomesAFolder() {
        val tree = contentTree(rows("docs/readme.txt", "docs/manual.pdf"))
        assertEquals(1, tree.size)
        val folder = tree.single() as ContentNode.Folder
        assertEquals("docs", folder.label)
        assertEquals(2, folder.fileIndices.size)
        assertEquals(2, folder.children.size)
    }

    @Test
    fun nestedFoldersNest() {
        val tree = contentTree(rows("a/b/c/deep.txt", "a/b/shallow.txt", "a/top.txt"))
        val a = tree.single() as ContentNode.Folder
        val b = a.children.first { it.label == "b" } as ContentNode.Folder
        val c = b.children.first { it.label == "c" } as ContentNode.Folder

        assertEquals("a", a.label)
        assertEquals("b", b.label)
        assertEquals("c", c.label)
        assertEquals("deep.txt", c.children.single().label)
        // Every file beneath, not just the folder's own - a folder's tick has to reach all
        // of them, and its size has to be the whole of it.
        assertEquals(3, a.fileIndices.size)
        assertEquals(2, b.fileIndices.size)
        assertEquals(1, c.fileIndices.size)
    }

    @Test
    fun aFoldersSizeIsTheWholeOfIt() {
        val tree = contentTree(rows("docs/a.txt", "docs/b.txt", "other.txt"))
        val docs = tree.first { it.label == "docs" } as ContentNode.Folder
        // 100 + 200 for the two files inside it, and not the 300 of the one outside.
        assertEquals(300L, docs.totalSize)
    }

    @Test
    fun foldersComeBeforeFilesAndBothAreSorted() {
        val tree = contentTree(rows("zebra.txt", "alpha.txt", "zfolder/f.txt", "afolder/g.txt"))
        assertEquals(
            // Folders first, alphabetically, then files, alphabetically.
            listOf("afolder", "zfolder", "alpha.txt", "zebra.txt"),
            tree.map { it.label }
        )
    }

    @Test
    fun theOrderTheTorrentListsItsFilesInDoesNotMatter() {
        // A torrent's order is its creator client's choice and is not sorted. The tree has
        // to be, or the same torrent lists differently depending on who made it.
        val forwards = contentTree(rows("b/2.txt", "b/1.txt", "a.txt"))
        val backwards = contentTree(rows("a.txt", "b/1.txt", "b/2.txt"))
        assertEquals(
            forwards.map { it.fullPath },
            backwards.map { it.fullPath }
        )
    }

    @Test
    fun aFolderIsTickedOnlyWhenEverythingInItIs() {
        val tree = contentTree(rows("docs/a.txt", "docs/b.txt"))
        val folder = tree.single() as ContentNode.Folder

        assertTrue("nothing ticked", !folder.isFullySelected(emptySet()))
        assertTrue("one of two", !folder.isFullySelected(setOf(0)))
        assertTrue("both", folder.isFullySelected(setOf(0, 1)))
    }

    @Test
    fun aFolderWithNothingInItIsNeverFullyTicked() {
        // An empty selection is not the same as an empty folder, and showing a folder as
        // ticked because there was nothing to tick would be a lie.
        val folder = ContentNode.Folder("empty", "empty", emptyList(), emptyList(), 0L, emptyList())
        assertFalse(folder.isFullySelected(emptySet()))
    }

    @Test
    fun twoFilesWithTheSameNameInDifferentFoldersAreBothKept() {
        // The shape that crashed the flat list: a duplicated key throws while the list is
        // being laid out. As a tree, the paths differ and there is no collision.
        val tree = contentTree(rows("s1/episode.mkv", "s2/episode.mkv"))
        assertEquals(2, tree.size)
        val paths = tree.map { it.fullPath }
        assertEquals("s1", paths[0])
        assertEquals("s2", paths[1])
        assertEquals("both names are the same, so only the folder tells them apart", 2, paths.toSet().size)
    }

    @Test
    fun theTreeIsClosedByDefaultAndOpensOneFolderAtATime() {
        val tree = contentTree(rows("docs/a.txt", "bin/b.txt"))
        val closed = visibleContentNodes(tree, expanded = emptySet())
        assertEquals(
            "with nothing open, only the two folders show",
            listOf("bin", "docs"),
            closed.map { it.first.label }
        )

        val opened = visibleContentNodes(tree, expanded = setOf("docs"))
        assertEquals(
            // Folders are sorted, so "bin" comes first however the files arrived.
            listOf("bin", "docs", "a.txt"),
            opened.map { it.first.label }
        )
        // And the depth, which is what indents the file inside its folder.
        assertEquals(0, opened[0].second)
        assertEquals(0, opened[1].second)
        assertEquals(1, opened[2].second)
    }

    @Test
    fun openingAFolderDoesNotOpenTheOneBesideIt() {
        val tree = contentTree(rows("docs/a.txt", "bin/b.txt"))
        val opened = visibleContentNodes(tree, expanded = setOf("bin"))
        assertEquals(listOf("bin", "b.txt", "docs"), opened.map { it.first.label })
    }

    @Test
    fun theFilterKeepsTheFolderThatHoldsAMatchingFile() {
        // Applied before the tree is built, so a filter that matches one file inside a
        // folder shows the folder rather than hiding the file behind a folder the filter
        // has no way to open.
        val all = rows("docs/notes.txt", "bin/tool.exe", "readme.md")
        val needle = "notes"
        val kept = all.filter { it.path.contains(needle) }
        val tree = contentTree(kept)
        assertEquals(1, tree.size)
        assertEquals("docs", tree.single().label)
        assertEquals("notes.txt", tree.single().let { (it as ContentNode.Folder).children.single().label })
    }

    @Test
    fun aFilterMatchingNothingGivesAnEmptyTreeRatherThanEverything() {
        val kept = rows("docs/notes.txt", "bin/tool.exe").filter { it.path.contains("zzz") }
        assertTrue(contentTree(kept).isEmpty())
    }

    @Test
    fun aFullPathWithNoFoldersIsHandled() {
        val tree = contentTree(rows("single-file.iso"))
        assertEquals(1, tree.size)
        assertEquals("single-file.iso", tree.single().label)
        assertEquals(0, tree.single().let { (it as ContentNode.File).index })
    }

    @Test
    fun aDeepPathDoesNotProduceEmptySegments() {
        // Leading and doubled slashes appear in the wild. An empty segment would become a
        // folder with a blank name, which renders as an empty row.
        val tree = contentTree(rows("/docs//notes.txt"))
        val docs = tree.single() as ContentNode.Folder
        assertEquals("docs", docs.label)
        assertTrue(
            "no blank names anywhere: ${docs.children.map { it.label }}",
            docs.children.none { it.label.isBlank() }
        )
    }

    @Test
    fun aReleaseWithManyFilesAcrossManyFoldersStaysNavigable() {
        val paths = (1..12).map { episode ->
            "Example Release 2.1 [1080p]/Season 01/Episode $episode.mkv"
        }
        val tree = contentTree(rows(*paths.toTypedArray()))

        val release = tree.single() as ContentNode.Folder
        val season = release.children.single() as ContentNode.Folder
        assertEquals("Example Release 2.1 [1080p]", release.label)
        assertEquals("Season 01", season.label)
        assertEquals(12, season.children.size)
        // The three levels are what make it readable; as a flat list it was twelve rows
        // of the same sentence.
        assertEquals(
            listOf("Episode 1.mkv", "Episode 10.mkv", "Episode 11.mkv", "Episode 12.mkv",
                "Episode 2.mkv", "Episode 3.mkv"),
            season.children.take(6).map { it.label }
        )
    }
}
