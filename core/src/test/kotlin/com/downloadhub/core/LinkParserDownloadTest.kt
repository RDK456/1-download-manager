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

    /** GitHub's release downloads redirect to a blob id and name the file only in this header. */
    @Test
    fun `the name in a plain Content-Disposition beats the url`() {
        val url = "https://release-assets.githubusercontent.com/x/2ba46a50-441e-4720?sig=1"
        assertTrue(LinkParser.fileNameFrom(url, "attachment; filename=1-download-manager-1.5.3.msi") == "1-download-manager-1.5.3.msi")
        assertTrue(LinkParser.fileNameFrom(url, "attachment; filename=\"a b.zip\"") == "a b.zip")
        assertTrue(LinkParser.fileNameFrom(url, "attachment; filename*=UTF-8''caf%C3%A9.pdf") == "café.pdf")
    }
}
