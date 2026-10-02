package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The download list appeared, went away, and came back only on a restart.
 *
 * Two faults, one on each platform, and neither of them a drawing problem.
 */
class ListFlickerTest {

    /**
     * The store's read of the queue was the one accessor that did not take the lock.
     *
     * It is the only way the screen reads the queue, so it runs on the UI thread while
     * the torrent poll calls update() several times a second from its own thread. A
     * LinkedHashMap being walked by one thread and written by another throws, and the
     * throw came out of refresh() - before the new state could be assigned - so the
     * list kept whatever the last build that survived had in it.
     */
    @Test
    fun everyReadOfTheQueueTakesTheLock() {
        val reads = listOf("snapshot", "get", "add", "update", "remove", "clearFinished", "delete")
        reads.forEach { name ->
            val method = DesktopStore::class.java.declaredMethods.first { it.name == name }
            assertTrue(
                "$name must be @Synchronized: it is called from the UI thread while " +
                    "the torrent poll is writing",
                Modifier.isSynchronized(method.modifiers)
            )
        }
    }

    /**
     * A reading is a consistent snapshot rather than a walk that can be interrupted.
     *
     * A list of the rows is taken and the caller works from that, so a download that
     * changes while the screen is drawing it cannot make the drawing fail.
     */
    @Test
    fun aReadingIsACopyAndNotALiveView() {
        val store = DesktopStore(emptyList())
        val before = store.snapshot()
        store.add(
            QueuedDownload(
                id = "later",
                url = "https://example.invalid/a.zip",
                fileName = "a.zip",
                source = com.downloadhub.core.DownloadSource.HTTP
            )
        )
        assertEquals("a reading must not see rows added after it was taken", 0, before.size)
        assertEquals(1, store.snapshot().size)
    }

    /**
     * Every list the Android screen draws was emptied by navigating away.
     *
     * SharingStarted.WhileSubscribed(5_000) stops collecting when the last collector
     * goes and restarts it later holding the initial value it was declared with. For a
     * list that initial value is empty, so coming back to the tab after five seconds
     * put an empty queue on screen along with every list derived from it.
     */
    @Test
    fun noAndroidListIsEmptyJustBecauseNobodyWasLooking() {
        val source = File("../app/src/main/java/com/downloadhub/app/ui/DownloadViewModel.kt")
        assertTrue("the view model should be where it was", source.isFile)
        val text = source.readText()
        assertFalse(
            "WhileSubscribed restarts a list holding its initial value, which for " +
                "every list here is empty - that is the flicker:\n" +
                text.lines().filter { "WhileSubscribed" in it }.joinToString("\n"),
            text.contains("SharingStarted.WhileSubscribed")
        )
        assertTrue(
            "the lists must be held for the life of the screen instead",
            text.contains("SharingStarted.Lazily")
        )
    }

    /**
     * The kinds are a third axis, not a fourth state and not a fifth category.
     *
     * If they had been folded into the states then choosing a kind would have changed
     * what "Completed" meant, and choosing Completed would have quietly narrowed the
     * list to one kind as well. Both are askable together and neither touches the
     * other.
     */
    @Test
    fun aKindAndAStateCanBeAskedForTogether() {
        val items = listOf(
            com.downloadhub.core.DownloadItem(
                id = "yt-done",
                url = "https://youtu.be/a",
                fileName = "done.mp4",
                source = com.downloadhub.core.DownloadSource.YOUTUBE,
                status = com.downloadhub.core.DownloadStatus.COMPLETED
            ),
            com.downloadhub.core.DownloadItem(
                id = "yt-going",
                url = "https://youtu.be/b",
                fileName = "going.mp4",
                source = com.downloadhub.core.DownloadSource.YOUTUBE,
                status = com.downloadhub.core.DownloadStatus.RUNNING
            ),
            com.downloadhub.core.DownloadItem(
                id = "web-done",
                url = "https://example.invalid/c.zip",
                fileName = "c.zip",
                source = com.downloadhub.core.DownloadSource.HTTP,
                status = com.downloadhub.core.DownloadStatus.COMPLETED
            )
        )
        val completedYouTube = com.downloadhub.core.DownloadLibrary.visible(
            items,
            com.downloadhub.core.LibraryQuery(
                group = com.downloadhub.core.LibraryGroup.COMPLETED,
                kind = com.downloadhub.core.LibraryKind.YOUTUBE
            )
        )
        assertEquals(
            "one item is both completed and a YouTube download",
            listOf("yt-done"),
            completedYouTube.map { it.id }
        )
        // The kind alone.
        assertEquals(
            2,
            com.downloadhub.core.DownloadLibrary.visible(
                items,
                com.downloadhub.core.LibraryQuery(kind = com.downloadhub.core.LibraryKind.YOUTUBE)
            ).size
        )
        // The state alone still reaches every kind, which it would not if the kind had
        // been folded into the state: Completed means completed, whatever it is.
        assertEquals(
            listOf("web-done", "yt-done"),
            com.downloadhub.core.DownloadLibrary.visible(
                items,
                com.downloadhub.core.LibraryQuery(
                    group = com.downloadhub.core.LibraryGroup.COMPLETED
                )
            ).map { it.id }
        )
    }
}
