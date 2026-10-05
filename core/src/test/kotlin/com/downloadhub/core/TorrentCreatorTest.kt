package com.downloadhub.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentCreatorTest {
    @Test
    fun blankLinesSeparateTrackerTiers() {
        val tiers = TorrentCreator.trackerTiers("udp://a/announce\nudp://b/announce\n\n\nhttp://c/announce\n")
        assertEquals(listOf("udp://a/announce" to 0, "udp://b/announce" to 0, "http://c/announce" to 1), tiers)
    }

    @Test
    fun aFolderBecomesATorrentThatParsesBack() {
        val root = Files.createTempDirectory("creator").toFile()
        try {
            val folder = java.io.File(root, "My Pack").apply { mkdirs() }
            java.io.File(folder, "a.bin").writeBytes(ByteArray(300_000) { it.toByte() })
            java.io.File(folder, "b.txt").writeText("hello")
            val bytes = TorrentCreator.create(
                TorrentCreator.Request(folder, trackersText = "udp://tracker.example:1337/announce", comment = "made in a test")
            )
            val saved = java.io.File(root, "pack.torrent").apply { writeBytes(bytes) }
            val meta = TorrentParser.parse(saved)
            assertEquals("My Pack", meta.name)
            assertEquals(2, meta.files.size)
            assertEquals(300_005L, meta.totalSize)
            assertTrue("the tracker is in the file", String(bytes, Charsets.ISO_8859_1).contains("udp://tracker.example:1337/announce"))
        } finally {
            root.deleteRecursively()
        }
    }
}
