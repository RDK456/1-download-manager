package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvTest {
    private val sample = """
        #EXTM3U x-tvg-url="https://example.com/guide.xml.gz"
        #EXTINF:-1 tvg-id="2GB.au@SD" tvg-logo="https://i.ibb.co/2gb.png" group-title="News",2GB (1080p)
        https://2gblive.akamaized.net/hls/live/index.m3u8
        #EXTINF:-1 tvg-id="x" tvg-logo="" group-title="News;Public",3Cat Exclusiu 1 (1080p) [Geo-blocked]
        #EXTVLCOPT:http-referrer=https://example.com/
        https://directes.example.cat/master.m3u8
        #EXTINF:-1 group-title="Music",Radio, With Comma [Not 24/7]
        https://radio.example.com/live.m3u8
        #EXTINF:-1 group-title="News",Broken entry
        not-a-url
    """.trimIndent()

    @Test
    fun `channels are read with their logo, groups, quality and flags`() {
        val channels = IptvSource.parseM3u(sample)
        assertEquals(listOf("2GB", "3Cat Exclusiu 1", "Radio, With Comma"), channels.map { it.name })
        val first = channels[0]
        assertEquals("https://i.ibb.co/2gb.png", first.logo)
        assertEquals("1080p", first.quality)
        assertEquals(listOf("News"), first.groups)
        val second = channels[1]
        assertEquals(null, second.logo)
        assertEquals(listOf("News", "Public"), second.groups)
        assertTrue(second.geoBlocked)
        assertEquals("https://directes.example.cat/master.m3u8", second.url)
        assertEquals(listOf("Not 24/7"), channels[2].flags)
    }

    @Test
    fun `list addresses follow iptv-org's layout`() {
        assertEquals("https://iptv-org.github.io/iptv/categories/news.m3u", IptvSource.categoryUrl("news"))
        assertEquals("https://iptv-org.github.io/iptv/countries/in.m3u", IptvSource.countryUrl("in"))
    }
}
