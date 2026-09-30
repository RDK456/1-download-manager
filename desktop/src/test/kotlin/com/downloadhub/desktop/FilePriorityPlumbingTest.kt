package com.downloadhub.desktop

import com.downloadhub.core.FilePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-file priority's path from a click to libtorrent.
 *
 * Every step of this chain is a conversion between two models, and every conversion is a
 * place the setting can be quietly dropped: the click arrives as an ordinal, it lives on
 * the store item, the store item becomes the core item, and the core item becomes a
 * `prioritize_files` call. A missing hop does not throw - it drops the user's choice, and
 * the only symptom is that every file stays Normal and nothing says why.
 */
class FilePriorityPlumbingTest {

    @Test
    fun `a priority set on the store item reaches the core item`() {
        val queued = QueuedDownload(
            id = "t1",
            url = "magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd",
            fileName = "Example",
            source = com.downloadhub.core.DownloadSource.TORRENT
        ).copy(
            torrentFilePriorities = mapOf(0 to FilePriority.SKIP.ordinal, 3 to FilePriority.MAXIMUM.ordinal)
        )

        val core = queued.toCoreItem()
        assertEquals(
            "the priority was dropped on the way to the engine",
            FilePriority.SKIP.ordinal,
            core.torrentFilePriorities[0]
        )
        assertEquals(FilePriority.MAXIMUM.ordinal, core.torrentFilePriorities[3])
    }

    /**
     * A torrent with nothing set sends an empty map, which the planner reads as "every file
     * is Normal" - not as "every file is skipped".
     *
     * The two are one character apart in the saved data and opposite in meaning, so the
     * distinction is worth a test of its own.
     */
    @Test
    fun `an empty map means every file is normal, not every file skipped`() {
        val core = QueuedDownload(
            id = "t1",
            url = "magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd",
            fileName = "Example",
            source = com.downloadhub.core.DownloadSource.TORRENT
        ).toCoreItem()

        assertTrue("nothing should be set by default", core.torrentFilePriorities.isEmpty())
        val priorities = (0 until 5).associateWith { index ->
            com.downloadhub.core.effectiveFilePriority(emptySet(), emptyMap(), index)
        }
        assertTrue(priorities.values.all { it == FilePriority.NORMAL })
    }

    /**
     * And the pre-download selection still wins over a per-file choice.
     *
     * The Content tab can now set Skip on a file the user unticked at the start. That must
     * not make the file wanted: a file nobody chose is still not wanted, and the tab's
     * ticks are a toolbar's selection rather than a re-decision.
     */
    @Test
    fun `a file outside the pre-download selection stays skipped`() {
        val chosen = mapOf(0 to FilePriority.NORMAL, 5 to FilePriority.MAXIMUM)
        assertEquals(
            FilePriority.SKIP,
            com.downloadhub.core.effectiveFilePriority(setOf(0, 1), chosen, 5)
        )
        assertEquals(
            FilePriority.MAXIMUM,
            com.downloadhub.core.effectiveFilePriority(setOf(0, 1, 5), chosen, 5)
        )
    }
}
