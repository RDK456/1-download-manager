package com.downloadhub.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMediaParserTest {
    private val base = "https://example.com/watch/episode-1"

    @Test
    fun findsVideoAndAudioTags() {
        val html = """
            <html><body>
              <video controls poster="/poster.jpg">
                <source src="/media/movie.mp4" type="video/mp4">
                <source src="//cdn.example.com/media/movie.webm" type="video/webm">
              </video>
              <audio src="https://cdn.example.com/audio/track.m4a"></audio>
            </body></html>
        """.trimIndent()

        val items = PageMediaParser.parse(html, base)
        val urls = items.map { it.url }
        assertTrue(urls.contains("https://example.com/media/movie.mp4"))
        assertTrue(urls.contains("https://cdn.example.com/media/movie.webm"))
        assertTrue(urls.contains("https://cdn.example.com/audio/track.m4a"))

        val mp4 = items.first { it.url.endsWith("movie.mp4") }
        assertEquals(MediaKind.VIDEO, mp4.kind)
        assertEquals("mp4", mp4.extension)
        assertEquals("track.m4a", items.first { it.url.endsWith("track.m4a") }.label)
        assertEquals(MediaKind.AUDIO, items.first { it.url.endsWith("track.m4a") }.kind)
    }

    @Test
    fun findsOpenGraphAndPlayerMetadata() {
        val html = """
            <head>
              <meta property="og:video" content="https://cdn.example.com/embed/trailer.mp4">
              <meta property="og:audio" content="https://cdn.example.com/audio/commentary.mp3">
              <meta name="twitter:player:stream" content="https://cdn.example.com/stream.mp4">
            </head>
        """.trimIndent()

        val urls = PageMediaParser.parse(html, base).map { it.url }
        assertTrue(urls.contains("https://cdn.example.com/embed/trailer.mp4"))
        assertTrue(urls.contains("https://cdn.example.com/audio/commentary.mp3"))
        assertTrue(urls.contains("https://cdn.example.com/stream.mp4"))
    }

    @Test
    fun findsHlsPlaylistLinks() {
        val html = """
            <link rel="alternate" type="application/x-mpegURL"
                  href="https://cdn.example.com/hls/master.m3u8">
        """.trimIndent()
        val item = PageMediaParser.parse(html, base).first()
        assertEquals("https://cdn.example.com/hls/master.m3u8", item.url)
        assertEquals(MediaKind.VIDEO, item.kind)
    }

    @Test
    fun findsDownloadableAnchorLinks() {
        val html = """
            <a href="/downloads/subs.srt">English subtitles</a>
            <a href="https://example.com/files/manual.pdf">User manual</a>
            <a href="/about">About us</a>
            <a href="javascript:void(0)">Nothing</a>
        """.trimIndent()

        val items = PageMediaParser.parse(html, base)
        assertEquals(2, items.size)
        assertEquals("English subtitles", items.first { it.extension == "srt" }.label)
        assertTrue(items.any { it.url == "https://example.com/files/manual.pdf" })
        assertTrue(items.none { it.label.contains("About") })
    }

    @Test
    fun findsEmbeddedPlayersAndJsonLd() {
        val html = """
            <iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ"></iframe>
            <script type="application/ld+json">
              {"@type":"VideoObject","name":"Clip",
               "contentUrl":"https://cdn.example.com/clips/clip.mp4"}
            </script>
        """.trimIndent()

        val items = PageMediaParser.parse(html, base)
        val player = items.first { it.kind == MediaKind.PLAYER }
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ", player.url)
        assertTrue(items.any { it.url == "https://cdn.example.com/clips/clip.mp4" })
    }

    @Test
    fun resolvesRelativeAndProtocolRelativeUrls() {
        val html = """
            <video src="media/relative.mp4"></video>
            <audio src="//cdn.example.com/abs.m4a"></audio>
        """.trimIndent()
        val urls = PageMediaParser.parse(html, base).map { it.url }
        assertTrue(urls.contains("https://example.com/watch/media/relative.mp4"))
        assertTrue(urls.contains("https://cdn.example.com/abs.m4a"))
    }

    @Test
    fun ignoresNonMediaAndDuplicates() {
        val html = """
            <video src="/media/one.mp4"></video>
            <source src="/media/one.mp4" type="video/mp4">
            <img src="/image/poster.jpg">
            <script src="/js/player.js"></script>
        """.trimIndent()

        val items = PageMediaParser.parse(html, base)
        assertEquals(1, items.size)
        assertEquals("https://example.com/media/one.mp4", items.first().url)
    }

    @Test
    fun handlesEmptyAndMediaFreePages() {
        assertTrue(PageMediaParser.parse("", base).isEmpty())
        assertTrue(PageMediaParser.parse("<html><body><p>hello</p></body></html>", base).isEmpty())
    }

    @Test
    fun detectsUrlsThatAreAlreadyMedia() {
        assertTrue(PageMediaParser.looksLikeDirectMedia("https://cdn.example.com/clip.mp4"))
        assertTrue(PageMediaParser.looksLikeDirectMedia("https://cdn.example.com/live/index.m3u8?token=1"))
        assertTrue(!PageMediaParser.looksLikeDirectMedia("https://example.com/watch/1"))
        assertTrue(!PageMediaParser.looksLikeDirectMedia("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun handlesRealWorldVideoTagWithRelativeSource() {
        // Shape taken from a live page: the <video> element carries no src of its
        // own, and the media lives in a child <source> with a relative path.
        val html = """
            <video id="video1" style="width:600px;max-width:100%;" controls>
              <source src="mov_bbb.mp4" type="video/mp4">
              <source src="mov_bbb.ogg" type="video/ogg">
            </video>
        """.trimIndent()
        val items = PageMediaParser.parse(html, "https://www.w3schools.com/html/html5_video.asp")
        assertEquals(2, items.size)
        assertTrue(items.any { it.url == "https://www.w3schools.com/html/mov_bbb.mp4" })
        assertTrue(items.any { it.url == "https://www.w3schools.com/html/mov_bbb.ogg" })
        assertEquals(MediaKind.VIDEO, items.first().kind)
    }

    @Test
    fun labelsFallbackToDecodedFileName() {
        val html = """<video src="https://cdn.example.com/deep/path/My%20Movie.mp4"></video>"""
        val item = PageMediaParser.parse(html, base).first()
        // URI.getPath() decodes the escape, which makes a friendlier label.
        assertEquals("My Movie.mp4", item.label)
    }

    @Test
    fun respectsTheCandidateLimit() {
        val html = (1..80).joinToString(" ") { """<video src="/media/file$it.mp4"></video>""" }
        val items = PageMediaParser.parse(html, base, limit = 10)
        assertEquals(10, items.size)
    }

    @Test
    fun mediaCandidateCarriesNoSizeByDefault() {
        val item = MediaCandidate("https://x/y.mp4", "y.mp4", MediaKind.VIDEO, "mp4")
        assertNull(item.sizeBytes)
    }
}
