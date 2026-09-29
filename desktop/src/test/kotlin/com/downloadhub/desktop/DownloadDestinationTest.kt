package com.downloadhub.desktop

import com.downloadhub.core.DownloadCategory
import com.downloadhub.core.LibraryCategory
import com.downloadhub.core.destinationFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the three behaviours asked for together: open the download folder, file
 * finished downloads into category subfolders, and choose what "delete" means.
 *
 * They are grouped because they are one change from the user's point of view - what
 * happens to a file after it arrives, and what happens when it is removed.
 */
class DownloadDestinationTest {

    // --- category subfolders -------------------------------------------------

    @Test
    fun everyCategoryHasItsOwnFolder() {
        val folders = DownloadCategory.entries.map { it.destinationFolder() }
        // PROGRAM/FILE/ARCHIVE and OTHER share a bucket, but nothing should be silently
        // dropped into nothing.
        assertTrue("some category has no folder", folders.none { it.isBlank() })
    }

    /**
     * The folder name has to be the word already on screen. If the sidebar says
     * "Videos" and the folder says "Video", people end up with two arrangements to
     * remember.
     */
    @Test
    fun folderNamesMatchTheSidebarLabels() {
        assertEquals(LibraryCategory.COMPRESSED.label, DownloadCategory.COMPRESSED.destinationFolder())
        assertEquals(LibraryCategory.PROGRAMS.label, DownloadCategory.PROGRAM.destinationFolder())
        assertEquals(LibraryCategory.VIDEOS.label, DownloadCategory.VIDEO.destinationFolder())
        assertEquals(LibraryCategory.MUSIC.label, DownloadCategory.AUDIO.destinationFolder())
        assertEquals(LibraryCategory.PICTURES.label, DownloadCategory.IMAGE.destinationFolder())
        assertEquals(LibraryCategory.DOCUMENTS.label, DownloadCategory.DOCUMENT.destinationFolder())
    }

    @Test
    fun archiveJoinsCompressedAndStraysGoToOther() {
        assertEquals(
            "an archive and a compressed file are the same thing to a person",
            DownloadCategory.COMPRESSED.destinationFolder(),
            DownloadCategory.ARCHIVE.destinationFolder()
        )
        assertEquals("Other", DownloadCategory.OTHER.destinationFolder())
        assertEquals("Other", DownloadCategory.FILE.destinationFolder())
    }

    /**
     * The engine classifies from the response, so the category must reach the place
     * that does the filing rather than being re-guessed from the file name.
     */
    /**
     * Exercises the real thing rather than reading the source: a staged file is
     * published for each category and has to come out in that category's folder.
     */
    @Test
    fun publishingFilesThemIntoTheRightFolder() {
        val root = File(System.getProperty("java.io.tmpdir"), "dlm-dest-${System.nanoTime()}")
        val staging = File(root, "staging").apply { mkdirs() }
        try {
            val area = DesktopWorkArea { DesktopSettings(downloadDir = File(root, "out").absolutePath) }
            val expected = mapOf(
                DownloadCategory.VIDEO to "Videos",
                DownloadCategory.AUDIO to "Music",
                DownloadCategory.IMAGE to "Pictures",
                DownloadCategory.DOCUMENT to "Documents",
                DownloadCategory.COMPRESSED to "Compressed",
                DownloadCategory.PROGRAM to "Programs",
                DownloadCategory.OTHER to "Other"
            )
            expected.forEach { (category, folder) ->
                val staged = File(staging, "file-${category.name}.bin")
                staged.writeText("payload for $category")
                val published = area.publishFile(staged, staged.name, null, category)
                val landed = File(published.location)
                assertTrue("$category landed at ${landed.absolutePath}, not under $folder", landed.isFile)
                assertEquals(folder, landed.parentFile?.name)
                assertEquals("the payload must survive the move", "payload for $category", landed.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Two downloads of the same name in one category must not overwrite each other.
     */
    @Test
    fun publishingTwiceDoesNotOverwrite() {
        val root = File(System.getProperty("java.io.tmpdir"), "dlm-collide-${System.nanoTime()}")
        val staging = File(root, "staging").apply { mkdirs() }
        try {
            val area = DesktopWorkArea { DesktopSettings(downloadDir = File(root, "out").absolutePath) }
            // Distinct staging paths, same published name: that is the real situation,
            // two separate downloads that both came back called clip.mp4.
            val first = File(staging, "a.bin").apply { writeText("first") }
            val second = File(staging, "b.bin").apply { writeText("second") }
            val a = File(area.publishFile(first, "clip.mp4", null, DownloadCategory.VIDEO).location)
            val b = File(area.publishFile(second, "clip.mp4", null, DownloadCategory.VIDEO).location)
            assertTrue("the second file overwrote the first", a.exists() && b.exists())
            assertEquals("first", a.readText())
            assertEquals("second", b.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theCategoryIsHandedToTheWorkArea() {
        val core = File("../core/src/main/kotlin/com/downloadhub/core/HttpDownloader.kt").readText()
        assertTrue(
            "HttpDownloader must pass item.category to publishFile, or the folder is " +
                "guessed from the extension again",
            Regex("publishFile\\([\\s\\S]{0,400}?category = item\\.category").containsMatchIn(core)
        )
        val area = File("src/main/kotlin/com/downloadhub/desktop/DesktopWorkArea.kt").readText()
        assertTrue(
            "DesktopWorkArea must file into the category folder",
            area.contains("category.destinationFolder()")
        )
    }

    @Test
    fun everyPublishCallSitePassesTheCategory() {
        listOf(
            File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt"),
            File("src/main/kotlin/com/downloadhub/desktop/DesktopTorrentEngine.kt"),
            File("../app/src/main/java/com/downloadhub/app/download/HttpDownloader.kt"),
            File("../app/src/main/java/com/downloadhub/app/download/YoutubeDownloader.kt")
        ).filter { it.isFile }.forEach { file ->
            val text = file.readText()
            var index = 0
            while (true) {
                val at = text.indexOf("publishFile(", index)
                if (at < 0) break
                index = at + 1
                // Walk to the matching close rather than guessing a length: the calls
                // carry explanatory comments of different sizes, and a fixed window
                // passed on one and failed on another.
                val open = text.indexOf('(', at)
                var depth = 0
                var end = open
                while (end < text.length) {
                    when (text[end]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    end++
                }
                val call = text.substring(at, (end + 1).coerceAtMost(text.length))
                assertTrue(
                    "${file.name} calls publishFile without a category, so the file " +
                        "lands in the wrong folder:\n$call",
                    call.contains("category")
                )
            }
        }
    }

    // --- the folder button ---------------------------------------------------

    @Test
    fun thereIsAButtonThatOpensTheDownloadFolder() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the toolbar has no button for the download folder",
            // The glyph is checked properly in UpdateAndRevealTest, which also pins
            // down that it differs from the row's; here only the button matters.
            // Trailing arguments are allowed because the button takes a compact flag.
            Regex("ToolbarButton\\(\"Downloads\", DlmIcons\\.\\w+, onClick = onOpenFolder[^)]*\\)")
                .containsMatchIn(screen)
        )
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        assertTrue(
            "the action must be wired up, not just declared",
            controller.contains("openDownloadFolder = ::openDownloadFolder")
        )
        assertTrue(
            "the action must actually open the folder",
            controller.contains("fun openDownloadFolder()")
        )
    }

    /**
     * A folder button that does nothing until the first download is finished reads as
     * broken, so the folder is created on demand.
     */
    @Test
    fun openingTheFolderCreatesItIfItIsMissing() {
        val controller = File("src/main/kotlin/com/downloadhub/desktop/DesktopController.kt").readText()
        val body = controller.substringAfter("fun openDownloadFolder()").substringBefore("\n    }")
        assertTrue(
            "the folder must be created on demand:\n$body",
            body.contains("mkdirs()")
        )
    }

    // --- what delete means ---------------------------------------------------

    /**
     * The point of the change. Deleting a downloaded file is not undoable, and
     * "remove from the list" is what people often mean, so the app must ask.
     */
    @Test
    fun deletingOffersBothChoices() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "there is no delete-choice dialog",
            screen.contains("DeleteChoiceDialog")
        )
        // One button and a tick box, as qBittorrent does it. Two buttons put a
        // destructive choice next to a safe one and made the safe one the odd shape; the
        // tick box says what else will happen without putting two verdicts side by side.
        assertTrue(
            "the dialog must offer keeping the file, which is the unticked box",
            screen.contains("Also remove the content files")
        )
        assertTrue(
            "the dialog's single button is Remove, and it must not be a delete verb - " +
                "the tick box is what decides",
            screen.contains("Text(\"Remove\")")
        )
        assertTrue(
            "the tick box must start on the user's own default, or the setting is not " +
                "reaching the dialog",
            screen.contains("deleteFilesDefault = state.settings.deleteCacheWhenRemoved")
        )
    }

    @Test
    fun bothChoicesReachTheStore() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the tick box's answer is not wired through to the removal",
            screen.contains("actions.removeSelectingFiles(it, deleteFiles)")
        )
    }

    @Test
    fun theStoreHonoursTheChoice() {
        val store = File("src/main/kotlin/com/downloadhub/desktop/DesktopStore.kt").readText()
        val body = store
            .substringAfter("fun remove(id: String, deleteFiles: Boolean")
            .substringBefore("\n    }")
        assertTrue(
            "the store must take the file with it only when asked:\n$body",
            body.contains("if (deleteFiles)")
        )
        assertTrue(
            "the scratch copy must go when asked, or a partial download nobody wanted " +
                "is stranded in the cache:\n$body",
            body.contains("if (deleteCache)")
        )
        assertTrue(
            "and it must be looked up under the item's own cache key, not its id - a " +
                "torrent's bytes are a folder, not a `part-<id>` file",
            body.contains("cacheKey")
        )
    }

    /**
     * A running download has no file yet, so offering to delete one is a question
     * about nothing.
     */
    @Test
    fun theFileOptionIsHiddenWhenThereIsNoFile() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the dialog should only offer file deletion for a finished download",
            screen.contains("canDeleteFiles")
        )
        assertTrue(
            "it must be derived from whether anything is actually on disk",
            screen.contains("DownloadStatus.COMPLETED")
        )
    }

    // --- category icons ------------------------------------------------------

    @Test
    fun everySidebarCategoryHasAnIconExceptAll() {
        LibraryCategory.entries.forEach { category ->
            val icon = LibraryCategoryIcons.of(category)
            if (category == LibraryCategory.ALL) {
                assertTrue("All should have no icon; it is the heading, not a kind", icon == null)
            } else {
                assertTrue("$category has no icon", icon != null)
            }
        }
    }

    @Test
    fun theRailPassesTheIconThrough() {
        val screen = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt").readText()
        assertTrue(
            "the rail is not asking for the category icon",
            screen.contains("icon = LibraryCategoryIcons.of(entry)")
        )
    }

    @Test
    fun theIconsAreDrawnHereRatherThanPulledFromTheLargeJar() {
        val build = File("build.gradle.kts").readText()
        assertFalse(
            "material-icons-extended is a 36 MB jar for a handful of glyphs",
            build.contains("materialIconsExtended")
        )
        val icons = File("src/main/kotlin/com/downloadhub/desktop/DlmIcons.kt").readText()
        listOf("Compressed", "Programs", "Videos", "Music", "Pictures", "Documents")
            .forEach { name ->
                assertTrue("the $name icon is missing", icons.contains("val $name"))
            }
    }
}
