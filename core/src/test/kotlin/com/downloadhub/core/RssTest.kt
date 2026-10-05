package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RssTest {
    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:torrent="http://xmlns.ezrss.it/0.1/" xmlns:nyaa="https://nyaa.si/xmlns/nyaa">
          <channel>
            <item>
              <title>Show S01E02 1080p WEB</title>
              <torrent:magnetURI>magnet:?xt=urn:btih:AAAA</torrent:magnetURI>
              <guid>ep2</guid>
            </item>
            <item>
              <title>Show S01E03 720p</title>
              <enclosure url="https://example.com/ep3.torrent" type="application/x-bittorrent"/>
            </item>
            <item>
              <title>Anime 12</title>
              <link>https://nyaa.si/view/1</link>
              <nyaa:infoHash>0123456789abcdef0123456789abcdef01234567</nyaa:infoHash>
            </item>
            <item><title>No download here</title><link>https://example.com/page</link></item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun rssItemsGiveTheirDownloadLinks() {
        val items = RssParser.parse(rss)
        assertEquals("magnet:?xt=urn:btih:AAAA", items[0].link)
        assertEquals("ep2", items[0].guid)
        assertEquals("https://example.com/ep3.torrent", items[1].link)
        assertTrue(items[2].link.startsWith("magnet:?xt=urn:btih:0123456789abcdef"))
        // A plain web page link is kept; it is the only thing that article offers.
        assertEquals(4, items.size)
    }

    @Test
    fun atomEntriesParseToo() {
        val atom = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry><title>Ubuntu 26.04</title><id>u1</id><link href="https://example.com/ubuntu.torrent"/></entry>
            </feed>
        """.trimIndent()
        assertEquals(listOf(RssItem("Ubuntu 26.04", "https://example.com/ubuntu.torrent", "u1")), RssParser.parse(atom))
    }

    @Test
    fun rulesMatchWordsOrRegexAndHonourExclusions() {
        val words = RssRule("show", mustContain = "show 1080p", mustNotContain = "cam")
        assertTrue(words.matches("Show S01E02 1080p WEB"))
        assertFalse(words.matches("Show S01E03 720p"))
        assertFalse(words.matches("Show 1080p CAM"))
        val regex = RssRule("regex", mustContain = "S01E0[2-3]", useRegex = true)
        assertTrue(regex.matches("Show S01E03 720p"))
        assertFalse(RssRule("off", "show", enabled = false).matches("Show"))
        assertFalse("a broken regex matches nothing", RssRule("bad", "(", useRegex = true).matches("("))
    }
}
