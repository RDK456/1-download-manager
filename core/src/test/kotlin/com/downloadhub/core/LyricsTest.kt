package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test
    fun `timed lines are read, sorted, and several stamps on one line all count`() {
        val lines = LyricsSource.parseLrc("[00:12.30]Second\n[ar:Queen]\n[00:01.5]First\n[01:00.00][01:30.000]Chorus")
        assertEquals(listOf(1_500L, 12_300L, 60_000L, 90_000L), lines.map { it.atMillis })
        assertEquals(listOf("First", "Second", "Chorus", "Chorus"), lines.map { it.text })
    }

    @Test
    fun `the current line is the last one that has started`() {
        val lines = LyricsSource.parseLrc("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(-1, LyricsSource.lineAt(lines, 500))
        assertEquals(0, LyricsSource.lineAt(lines, 1_000))
        assertEquals(1, LyricsSource.lineAt(lines, 8_999))
        assertEquals(2, LyricsSource.lineAt(lines, 60_000))
    }

    @Test
    fun `synced lyrics win over plain ones`() {
        val synced = LyricsSource.parseSearch("""[{"plainLyrics":"x\ny"},{"syncedLyrics":"[00:02.00]hello","plainLyrics":"hello"}]""")!!
        assertTrue(synced.synced)
        assertEquals("hello", synced.lines.single().text)
        val plain = LyricsSource.parseSearch("""[{"plainLyrics":"one\ntwo","syncedLyrics":null}]""")!!
        assertFalse(plain.synced)
        assertEquals(2, plain.lines.size)
        assertEquals(null, LyricsSource.parseSearch("[]"))
    }

    @Test
    fun `title and artist come from a typical file name`() {
        assertEquals("Bohemian Rhapsody" to "Queen", LyricsSource.guessTitleArtist("Queen - Bohemian Rhapsody (Official Video) [1080p].mp3"))
        assertEquals("Song" to "", LyricsSource.guessTitleArtist("C:\\Music\\Song.flac"))
    }
}
