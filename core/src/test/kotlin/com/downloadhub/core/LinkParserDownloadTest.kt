package com.downloadhub.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserDownloadTest {
    @Test
    fun `file links and magnets are downloads, pages are not`() {
        assertTrue(LinkParser.looksLikeDownload("magnet:?xt=urn:btih:aabbccddeeff00112233445566778899aabbccdd"))
        assertTrue(LinkParser.looksLikeDownload("https://example.com/files/Setup.EXE"))
        assertTrue(LinkParser.looksLikeDownload("https://cdn.example.com/a/movie.mkv?token=abc#t=1"))
        assertTrue(LinkParser.looksLikeDownload("http://example.com/x.torrent"))
        assertFalse(LinkParser.looksLikeDownload("https://example.com/"))
        assertFalse(LinkParser.looksLikeDownload("https://example.com/article.html"))
        assertFalse(LinkParser.looksLikeDownload("https://example.com/download.php?file=movie.mkv"))
        assertFalse(LinkParser.looksLikeDownload("ftp://example.com/a.zip"))
        assertFalse(LinkParser.looksLikeDownload("just some text"))
    }
}
