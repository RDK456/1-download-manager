package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadSource
import com.downloadhub.core.DownloadStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The right-click menu and the cache rules.
 *
 * Each of these decides whether something destructive happens or whether a partial file
 * survives, so they are written down rather than left to reading the composable.
 */
class ContextMenuAndCacheTest {

    private fun item(
        status: DownloadStatus = DownloadStatus.RUNNING,
        source: DownloadSource = DownloadSource.TORRENT,
        id: String = "d1",
        downloaded: Long = 0L,
        location: String? = null
    ) = DownloadItem(
        id = id,
        url = if (source == DownloadSource.TORRENT) {
            "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3"
        } else {
            "https://example.com/file.zip"
        },
        fileName = "ubuntu.iso",
        source = source,
        status = status,
        bytesDownloaded = downloaded,
        location = location
    )

    @Test
    fun aRunningDownloadOffersPauseAndNotResume() {
        val actions = contextActions(item(status = DownloadStatus.RUNNING), hasContentFiles = false)

        assertTrue("a running download must be pausable", ContextAction.Pause in actions)
        assertFalse(
            "resuming something already running is a second name for pause",
            ContextAction.Resume in actions
        )
    }

    @Test
    fun aPausedDownloadOffersResumeAndNotPause() {
        val actions = contextActions(item(status = DownloadStatus.PAUSED), hasContentFiles = false)

        assertTrue(ContextAction.Resume in actions)
        assertFalse(ContextAction.Pause in actions)
        assertFalse(
            "force start means the same as resume for something merely paused",
            ContextAction.ForceStart in actions
        )
    }

    @Test
    fun aFailedDownloadOffersForceStartAsWellAsResume() {
        val actions = contextActions(item(status = DownloadStatus.FAILED), hasContentFiles = false)

        assertTrue(
            "a failure is the one case where forcing past it differs from resuming",
            ContextAction.ForceStart in actions
        )
        assertTrue(ContextAction.Resume in actions)
    }

    @Test
    fun anHttpDownloadIsOfferedNeitherMagnetNorExport() {
        val actions = contextActions(
            item(source = DownloadSource.HTTP, status = DownloadStatus.RUNNING),
            hasContentFiles = false
        )

        assertFalse(
            "copying a magnet for something that is not a torrent would copy a link to nothing",
            ContextAction.CopyMagnet in actions
        )
        assertFalse(
            "there is no .torrent file to export for an ordinary download",
            ContextAction.ExportTorrent in actions
        )
    }

    @Test
    fun aTorrentIsOfferedMagnetAndExportButNotSetLocation() {
        val actions = contextActions(item(), hasContentFiles = true)

        assertTrue(ContextAction.CopyMagnet in actions)
        assertTrue(ContextAction.ExportTorrent in actions)
        assertFalse(
            "a torrent's files are already spread across a folder, so moving the " +
                "destination folder would not move anything",
            ContextAction.SetLocation in actions
        )
    }

    @Test
    fun renameIsOnlyOfferedWhenThereIsAFileToRename() {
        assertTrue(
            ContextAction.Rename in
                contextActions(item(status = DownloadStatus.COMPLETED), hasContentFiles = true)
        )
        assertFalse(
            "renaming a download with nothing on disk can only change the label, which is " +
                "not what Rename says it does",
            ContextAction.Rename in
                contextActions(item(status = DownloadStatus.COMPLETED), hasContentFiles = false)
        )
    }

    @Test
    fun removeIsAlwaysLast() {
        // Last because it is the only destructive item, and a menu lists what is safe
        // before what is not.
        val actions = contextActions(item(status = DownloadStatus.RUNNING), hasContentFiles = true)
        assertEquals(ContextAction.Remove, actions.last())
    }

    @Test
    fun automaticManagementIsOfferedButNeverEnabled() {
        assertTrue(
            "it is in qBittorrent's menu and users look for it, so leaving it out reads " +
                "as an oversight rather than as 'not supported'",
            ContextAction.AutomaticManagement in
                contextActions(item(), hasContentFiles = false)
        )
    }

    @Test
    fun everyActionHasALabel() {
        // A menu entry with no text is a blank row that still takes clicks.
        listOf(
            ContextAction.Pause, ContextAction.Resume, ContextAction.ForceStart,
            ContextAction.Remove, ContextAction.Options, ContextAction.SetLocation,
            ContextAction.Rename, ContextAction.OpenFolder, ContextAction.CopyMagnet,
            ContextAction.ExportTorrent, ContextAction.AutomaticManagement
        ).forEach { action ->
            assertTrue("action $action has no label", contextActionLabel(action).isNotBlank())
        }
    }

    @Test
    fun copyingGivesTheMagnetItselfWhenThereIsOne() {
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=ubuntu"
        assertEquals(magnet, magnetLinkFor(item().copy(url = magnet)))
    }

    @Test
    fun copyingGivesTheFilePathForATorrentAddedAsAFile() {
        // A magnet cannot be rebuilt from a .torrent: the tracker list lives in the file.
        val path = "C:/downloads/ubuntu.torrent"
        assertEquals(path, magnetLinkFor(item().copy(url = path, torrentFilePath = path)))
    }

    @Test
    fun anUnfinishedDownloadsBytesLiveInTheCacheAndAreDeletedWithIt() {
        val store = DesktopStore(emptyList())
        val cached = AppPaths.workDir.resolve("d1")
        cached.parentFile?.mkdirs()
        cached.writeText("half a file")
        store.add(
            QueuedDownload(
                id = "d1",
                url = "https://example.com/big.zip",
                fileName = "big.zip",
                source = DownloadSource.HTTP,
                status = DownloadStatus.RUNNING,
                bytesDownloaded = 5L
            )
        )

        store.remove("d1", deleteFiles = false, deleteCache = true)

        assertFalse(
            "a partial download nobody asked for must not be left behind, which is how a " +
                "cache quietly fills up",
            cached.exists()
        )
    }

    @Test
    fun theCacheSurvivesWhenTheUserAsksToKeepIt() {
        val store = DesktopStore(emptyList())
        val cached = AppPaths.workDir.resolve("d2")
        cached.parentFile?.mkdirs()
        cached.writeText("half a file")
        store.add(
            QueuedDownload(
                id = "d2",
                url = "https://example.com/big.zip",
                fileName = "big.zip",
                source = DownloadSource.HTTP,
                status = DownloadStatus.PAUSED,
                bytesDownloaded = 5L
            )
        )

        // Unticking the box in the remove dialog.
        store.remove("d2", deleteFiles = false, deleteCache = false)

        assertTrue("the partial was asked to be kept", cached.exists())
        assertEquals("half a file", cached.readText())
        assertEquals(null, store.get("d2"))
        cached.delete()
    }

    @Test
    fun aTorrentsCacheFolderIsNotTheHttpFileName() {
        val http = QueuedDownload(id = "x", url = "https://e/a.zip", fileName = "a")
        val torrent = QueuedDownload(
            id = "x",
            url = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3",
            fileName = "pack",
            source = DownloadSource.TORRENT
        )

        // One name for both meant removing an item deleted a folder that was never there
        // while leaving the real one behind.
        assertFalse(http.cacheKey == torrent.cacheKey)
        assertEquals("x", http.cacheKey)
        assertEquals("torrent-x", torrent.cacheKey)
    }

    @Test
    fun aBlankCacheFolderMeansTheAppsOwnFolder() {
        assertEquals(
            AppPaths.cacheDirectory.absolutePath,
            DesktopSettings(cacheDir = "").cacheDirFile().absolutePath
        )
        assertEquals(
            File("D:/scratch").absolutePath,
            DesktopSettings(cacheDir = "D:/scratch").cacheDirFile().absolutePath
        )
    }

    @Test
    fun theRemoveTickBoxStartsOnTheUsersOwnDefault() {
        assertTrue(
            "a partial download nobody wanted is the usual reason for removing one",
            DesktopSettings().deleteCacheWhenRemoved
        )
        assertFalse(
            "and it is a setting, because abandoning a download to free a slot and " +
                "resuming it later is a real thing people do",
            DesktopSettings(deleteCacheWhenRemoved = false).deleteCacheWhenRemoved
        )
    }

    @Test
    fun theStatusStripCountsTorrentsOnly() {
        // Counting HTTP downloads in a torrent client's "seeds" line is what makes such a
        // number mean nothing.
        val items = listOf(
            item(id = "t1", downloaded = 100L, status = DownloadStatus.RUNNING).copy(
                seeds = 4, peerCount = 9, uploadRate = 2048L
            ),
            item(id = "t2", status = DownloadStatus.PAUSED).copy(seeds = 1, uploadRate = 100L),
            DownloadItem(
                id = "h1",
                url = "https://example.com/a.zip",
                fileName = "a.zip",
                source = DownloadSource.HTTP,
                status = DownloadStatus.RUNNING,
                speedBytesPerSecond = 999_999L,
                seeds = 100,
                peerCount = 100,
                uploadRate = 100L
            )
        )

        val strip = TorrentStatusStrip.from(items)
        assertEquals(2, strip.torrentCount)
        assertEquals(5, strip.seeds)
        assertEquals(9, strip.peers)
        assertEquals(
            "an HTTP download's speed must not appear as a torrent's",
            0L,
            strip.downloadRate
        )
    }

    /**
     * The pane offers only what there is something behind.
     *
     * It used to have five tabs, three of which existed to answer "this app does not
     * collect that yet" - which is a fact about the app, not about the download, and is
     * the same answer whatever is selected. They were drawn and greyed out rather than
     * hidden so the pane's shape would not change with the selection, and the cost of
     * that was a strip where four buttons in five could only ever decline.
     *
     * Now there are two, and which of them is offered depends on the download. A torrent
     * has a file list; an ordinary link and a YouTube video do not.
     */
    @Test
    fun thePaneOffersOnlyWhatThereIsSomethingBehind() {
        assertEquals(
            listOf("Details", "Content"),
            TorrentTab.values().map { it.label }
        )
        assertEquals(
            listOf(TorrentTab.GENERAL),
            TorrentTab.forDownload(isTorrent = false)
        )
        assertEquals(
            listOf(TorrentTab.GENERAL, TorrentTab.CONTENT),
            TorrentTab.forDownload(isTorrent = true)
        )
        // A saved name from an older build still resolves to something real.
        assertEquals(TorrentTab.GENERAL, TorrentTab.fromName(null))
        assertEquals(TorrentTab.CONTENT, TorrentTab.fromName("CONTENT"))
        assertEquals(TorrentTab.GENERAL, TorrentTab.fromName("NONSENSE"))
        // And a name for a tab that no longer exists falls back rather than throwing.
        assertEquals(TorrentTab.GENERAL, TorrentTab.fromName("PEERS"))
    }

    /**
     * The row's own buttons must actually do something.
     *
     * `IconButton` accepted an `onClick` and never used it, so every pause, retry and gear
     * button on every row was a picture of a button. Read out of the source because that
     * is the only place the mistake is visible.
     */
    @Test
    fun theRowIconButtonsAreActuallyClickable() {
        val source = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt")
            .readText()
        val body = source.substringAfter("private fun IconButton(")
            .substringBefore("\n}")

        assertTrue(
            "IconButton must use its onClick, or the row's buttons are pictures:\n$body",
            body.contains("clickable")
        )
        assertTrue(
            "and it needs a target bigger than the icon, or it cannot be hit",
            body.contains("size(24.dp)")
        )
    }

    /**
     * The row must not use `combinedClickable`.
     *
     * It is the reason the context menu never opened *and* the reason the row's own
     * buttons stopped responding - one cause, two reports.
     */
    @Test
    fun theRowDoesNotUseCombinedClickable() {
        val source = File("src/main/kotlin/com/downloadhub/desktop/LibraryScreen.kt")
            .readText()
        val row = source.substringAfter("private fun DownloadRow(")
            .substringBefore("private fun Cell(")
            // Comments are stripped first: the row's own doc comment explains why
            // combinedClickable is not used, and matching the word there would fail the
            // very test that documents the fix.
            .lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

        assertFalse(
            "combinedClickable makes Compose Desktop hold a secondary press as a long " +
                "press, which stopped both the context menu and the row's own buttons",
            row.contains("combinedClickable")
        )
        assertTrue(
            "and the secondary button must be read off the raw event instead",
            row.contains("BUTTON3")
        )
    }
}
