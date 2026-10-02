package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A torrent found by searching arrived with a file list drawn in the pre-download
 * dialog and nothing at all afterwards.
 *
 * Every search result is a magnet, and a magnet has no .torrent file. libtorrent4j
 * exposes no way to write one back out from the metadata it fetches, so the list
 * was used to draw the dialog and then dropped — and the Content tab, which reads
 * a .torrent from disk, could never show a file list for any of them.
 */
class TorrentMetainfoStoreTest {

    private fun tempRoot(): File =
        File.createTempFile("dlm-metainfo", "").let {
            it.delete()
            File(it, "home").apply { mkdirs() }
        }

    private fun metainfo(vararg files: Pair<String, Long>) = TorrentMetainfo(
        name = "A Release",
        files = files.mapIndexed { position, (path, size) ->
            TorrentFile(position, path, size, 262144L, 40)
        },
        comment = "",
        createdAtEpochMillis = 0L,
        createdBy = "",
        infoHashV1 = "abc",
        infoHashV2 = "",
        isSingleFile = files.size <= 1
    )

    @Test
    fun aFileListSurvivesBeingWrittenAndReadBack() {
        val root = tempRoot()
        try {
            TorrentMetainfoStore.write(
                root, "item-1",
                metainfo("Season 1/ep1.mkv" to 700_000_000L, "Season 1/ep2.mkv" to 800_000_000L)
            )
            val back = TorrentMetainfoStore.read(root, "item-1")
            assertEquals("A Release", back?.name)
            assertEquals(listOf(0, 1), back?.files?.map { it.index })
            assertEquals(
                listOf("Season 1/ep1.mkv", "Season 1/ep2.mkv"),
                back?.files?.map { it.path }
            )
            assertEquals(700_000_000L, back?.files?.first()?.size)
        } finally {
            root.parentFile.deleteRecursively()
        }
    }

    /** Paths carry spaces and punctuation in practice, and quotes are legal too. */
    @Test
    fun awkwardNamesAreEscapedRatherThanBroken() {
        val root = tempRoot()
        try {
            val awkward = metainfo(
                "A \"quoted\" name [1080p].mkv" to 12L,
                "Baldur's Gate 3 - 汉化.mkv" to 34L
            )
            TorrentMetainfoStore.write(root, "item-2", awkward)
            val back = TorrentMetainfoStore.read(root, "item-2")
            assertEquals(awkward.files.map { it.path }, back?.files?.map { it.path })
            assertEquals(12L, back?.files?.first()?.size)
            assertEquals(34L, back?.files?.last()?.size)
        } finally {
            root.parentFile.deleteRecursively()
        }
    }

    /**
     * A queue id is a UUID and would be a legal file name, but nothing guarantees
     * that forever - and an id that could escape the folder would write anywhere.
     */
    @Test
    fun anAwkwardIdCannotEscapeItsFolder() {
        val root = tempRoot()
        try {
            TorrentMetainfoStore.write(root, "../../escape", metainfo("a.mkv" to 1L))
            val written = TorrentMetainfoStore.directory(root)
                .listFiles()
                ?.filter { it.name.endsWith(".files.json") }
                .orEmpty()
            assertEquals(
                "the file list must stay inside its own folder",
                1,
                written.size
            )
            assertTrue(
                "nothing may be written above the folder",
                !File(root.parentFile, "escape.files.json").exists()
            )
        } finally {
            root.parentFile.deleteRecursively()
        }
    }

    /** "Not known yet" and "known to have no files" must not be the same thing. */
    @Test
    fun anEmptyListIsStoredAsNothing() {
        val root = tempRoot()
        try {
            TorrentMetainfoStore.write(root, "item-3", metainfo("a.mkv" to 1L))
            assertTrue("a list should have been written", TorrentMetainfoStore.read(root, "item-3") != null)
            TorrentMetainfoStore.write(root, "item-3", null)
            assertNull(
                "clearing must remove the file rather than leave an empty one, so " +
                    "'not known' and 'no files' stay different",
                TorrentMetainfoStore.read(root, "item-3")
            )
        } finally {
            root.parentFile.deleteRecursively()
        }
    }

    @Test
    fun anUnknownItemIsNothingRatherThanAFailure() {
        val root = tempRoot()
        try {
            assertNull(TorrentMetainfoStore.read(root, "never-written"))
            TorrentMetainfoStore.write(root, "truncated", metainfo("a.mkv" to 1L))
            val target = File(
                TorrentMetainfoStore.directory(root),
                TorrentMetainfoStore.read(root, "truncated")?.let { "truncated.files.json" } ?: ""
            )
            target.writeText("{ not json")
            assertNull(
                "a damaged file must read as no list, not crash the Content tab",
                TorrentMetainfoStore.read(root, "truncated")
            )
        } finally {
            root.parentFile.deleteRecursively()
        }
    }
}
