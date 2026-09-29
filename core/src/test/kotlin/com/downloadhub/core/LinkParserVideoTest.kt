package com.downloadhub.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which links get the quality and audio choices, and which can be downloaded at all.
 *
 * The quality choices are handed to yt-dlp, and were offered for everything: a zip, an
 * installer, a magnet. Selecting "1080p" on any of those does nothing, and a control that
 * cannot do what it says is worse than one that is not there.
 */
class LinkParserVideoTest {

    @Test
    fun youTubeLinksAreVideo() {
        assertTrue(LinkParser.isVideo("https://www.youtube.com/watch?v=abc123"))
        assertTrue(LinkParser.isVideo("https://youtu.be/abc123"))
        assertTrue(LinkParser.isVideo("https://m.youtube.com/watch?v=abc123"))
        // www. is the case that was being missed, which sent every embed URL down the
        // plain HTTP path.
        assertTrue(LinkParser.isVideo("https://www.youtube-nocookie.com/embed/abc123"))
    }

    @Test
    fun otherVideoSitesAreVideo() {
        assertTrue(LinkParser.isVideo("https://vimeo.com/12345"))
        assertTrue(LinkParser.isVideo("https://www.dailymotion.com/video/x8abcde"))
        assertTrue(LinkParser.isVideo("https://www.twitch.tv/somechannel"))
        assertTrue(LinkParser.isVideo("https://video.vimeo.com/12345"))
    }

    @Test
    fun aLinkStraightToAVideoFileIsVideo() {
        assertTrue(LinkParser.isVideo("https://example.com/movie.mp4"))
        assertTrue(LinkParser.isVideo("https://example.com/movie.mkv"))
        assertTrue(LinkParser.isVideo("https://example.com/stream.m3u8"))
        // Query strings and fragments must not hide the extension.
        assertTrue(LinkParser.isVideo("https://example.com/movie.mp4?token=abc#t=10"))
    }

    @Test
    fun ordinaryFilesAreNotVideo() {
        assertFalse(LinkParser.isVideo("https://example.com/setup.exe"))
        assertFalse(LinkParser.isVideo("https://example.com/archive.zip"))
        assertFalse(LinkParser.isVideo("https://example.com/manual.pdf"))
        assertFalse(LinkParser.isVideo("https://example.com/song.mp3"))
        assertFalse(LinkParser.isVideo("https://example.com/photo.jpg"))
        assertFalse(LinkParser.isVideo("https://example.com/download"))
        assertFalse(LinkParser.isVideo("https://example.com/"))
    }

    @Test
    fun torrentsAreNeverVideoWhateverTheNameSuggests() {
        assertFalse(
            LinkParser.isVideo(
                "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=movie.mp4"
            )
        )
        assertFalse(LinkParser.isVideo("https://example.com/movie.mp4.torrent"))
    }

    @Test
    fun anEmptyOrNonsenseLinkIsNotVideo() {
        assertFalse(LinkParser.isVideo(""))
        assertFalse(LinkParser.isVideo("   "))
        assertFalse(LinkParser.isVideo("not a url at all"))
    }

    @Test
    fun aSubdomainOfAVideoHostCountsButALookalikeDoesNot() {
        assertFalse(LinkParser.isVideo("https://notvimeo.com/12345"))
        assertFalse(LinkParser.isVideo("https://example.com/redirect?to=vimeo.com"))
    }

    /**
     * What can actually be downloaded.
     *
     * Everything else is refused at the point of adding, rather than queued and left to
     * fail later with `no protocol: <the text>` - which reads as the app failing rather
     * than as the input being wrong.
     */
    @Test
    fun onlyLinksMagnetsAndRealFilesAreFetchable() {
        assertTrue(LinkParser.isFetchable("https://example.com/file.zip"))
        assertTrue(LinkParser.isFetchable("http://example.com/file.zip"))
        assertTrue(
            LinkParser.isFetchable(
                "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3"
            )
        )

        // A bare torrent title, which is exactly what a broken add produced.
        assertFalse(
            LinkParser.isFetchable("[Judas] Chainsaw Man (Season 1) [1080p][HEVC x265]")
        )
        assertFalse(LinkParser.isFetchable("ubuntu.iso"))
        assertFalse(LinkParser.isFetchable(""))
        assertFalse(LinkParser.isFetchable("   "))

        // A .torrent path that is not there is as unfetchable as a title: it is a path to
        // nothing, and queuing it gives a row that can never start.
        assertFalse(LinkParser.isFetchable("C:/no/such/place/thing.torrent"))
    }

    @Test
    fun aTorrentFileThatIsOnDiskIsFetchable() {
        val file = java.io.File.createTempFile("dlm-fetchable", ".torrent")
        try {
            assertTrue(LinkParser.isFetchable(file.absolutePath))
            // And the caller can hand the file in rather than having it re-resolved.
            assertTrue(LinkParser.isFetchable("anything", file))
        } finally {
            file.delete()
        }
    }
}
