package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchAndPlaylistTest {
    @Test
    fun `a pasted list of links is every link, once, and nothing that is not a web link`() {
        val text = "https://a.com/1.zip\nhttps://a.com/2.zip  magnet:?xt=urn:btih:abc\nnot a link\nhttps://a.com/1.zip"
        assertEquals(listOf("https://a.com/1.zip", "https://a.com/2.zip"), BatchLinks.expand(text))
    }

    @Test
    fun `ranges expand, keeping zero padding, counting down, and nesting`() {
        assertEquals(listOf("https://s/img008.jpg", "https://s/img009.jpg", "https://s/img010.jpg"), BatchLinks.expand("https://s/img[008-010].jpg"))
        assertEquals(listOf("https://s/3", "https://s/2", "https://s/1"), BatchLinks.expand("https://s/[3-1]"))
        assertEquals(listOf("https://s/a1", "https://s/a2", "https://s/b1", "https://s/b2"), BatchLinks.expand("https://s/[a-b][1-2]"))
    }

    @Test
    fun `a huge mistyped range stops at the cap`() {
        assertEquals(BatchLinks.MAX, BatchLinks.expand("https://s/[1-99999999].jpg").size)
    }

    @Test
    fun `a video inside a playlist points at the whole playlist, but a mix or a plain video does not`() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PLabc-123_x",
            youTubePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc-123_x&index=3")
        )
        assertNull(youTubePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ"))
        assertNull(youTubePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(youTubePlaylistLink("https://example.com/watch?v=x&list=PLabc"))
    }

    @Test
    fun `archive files sharing a name are told apart by their folders, others keep their own name`() {
        fun file(name: String) = ArchiveFile(name, "u/$name", 0L, "")
        val names = ArchiveOrg.saveNames(
            listOf(file("disc1/Cover.jpg"), file("disc2/cover.jpg"), file("disc1/01 Intro.mp3"), file("a - b/c.txt"), file("a/b/c.txt"))
        )
        // The last two share a name and their folders flatten alike, so the second is numbered.
        assertEquals(listOf("disc1 - Cover.jpg", "disc2 - cover.jpg", "01 Intro.mp3", "a - b - c.txt", "a - b - c (2).txt"), names)
    }
}
