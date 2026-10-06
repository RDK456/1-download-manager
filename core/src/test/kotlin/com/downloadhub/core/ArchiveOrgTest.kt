package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveOrgTest {
    @Test
    fun `search rows become items, with a list of creators read as its first`() {
        val json = """{"response":{"docs":[
            {"identifier":"night_of_the_living_dead","title":"Night of the Living Dead","creator":"George A. Romero","mediatype":"movies","item_size":4321,"downloads":99},
            {"identifier":"some-app","creator":["Dev One","Dev Two"],"mediatype":"software"}
        ]}}"""
        val (film, app) = ArchiveOrg.parseSearch(json)
        assertEquals("Night of the Living Dead", film.title)
        assertEquals(4321L, film.sizeBytes)
        assertEquals("https://archive.org/details/night_of_the_living_dead", film.pageUrl)
        assertEquals("some-app", app.title) // no title: the identifier stands in
        assertEquals("Dev One", app.creator)
    }

    @Test
    fun `only uploaded, public files are offered - not derivatives or the archive's bookkeeping`() {
        val json = """{"files":[
            {"name":"My App 1.0.apk","source":"original","format":"Android Package Archive","size":"2048"},
            {"name":"sub/track one.mp3","source":"original","format":"VBR MP3","size":"100"},
            {"name":"track one.ogg","source":"derivative","format":"Ogg Vorbis"},
            {"name":"item_meta.xml","source":"original","format":"Metadata"},
            {"name":"__ia_thumb.jpg","source":"original","format":"Item Tile"},
            {"name":"item_archive.torrent","source":"metadata","format":"Archive BitTorrent"},
            {"name":"secret.zip","source":"original","private":"true"}
        ]}"""
        val files = ArchiveOrg.parseFiles(json, "item")
        assertEquals(listOf("My App 1.0.apk", "sub/track one.mp3"), files.map { it.name })
        assertEquals("https://archive.org/download/item/My%20App%201.0.apk", files[0].url)
        assertEquals("https://archive.org/download/item/sub/track%20one.mp3", files[1].url)
        assertEquals(2048L, files[0].sizeBytes)
    }

    @Test
    fun `download all asks first for many files or many bytes, not for a small item`() {
        fun files(count: Int, each: Long) = List(count) { ArchiveFile("f$it", "u$it", each, "") }
        assertFalse(ArchiveOrg.needsConfirmation(files(20, 1_000)))
        assertTrue(ArchiveOrg.needsConfirmation(files(21, 1_000)))
        assertTrue(ArchiveOrg.needsConfirmation(files(1, ArchiveOrg.CONFIRM_BYTES + 1)))
    }

    @Test
    fun `a blank query browses the type, and restricted items are always left out`() {
        val browse = java.net.URLDecoder.decode(ArchiveOrg.searchUrl("  ", ArchiveType.SOFTWARE), "UTF-8")
        assertTrue("mediatype:(software) AND NOT access-restricted-item:true" in browse)
        assertFalse("()" in browse)
        val search = java.net.URLDecoder.decode(ArchiveOrg.searchUrl("doom", ArchiveType.ALL, page = 2), "UTF-8")
        assertTrue("(doom) AND mediatype:(movies OR audio" in search)
        assertTrue("page=2" in search)
    }

    @Test
    fun `torrent recommendations are archive items with a torrent, seeded by the archive's trackers`() {
        val json = """{"response":{"docs":[
            {"identifier":"his_girl_friday","title":"His Girl Friday","btih":"8B733F925BE0DC728B4B01A1DDB978F4BB6D3451","item_size":1234},
            {"identifier":"no-torrent","title":"Nothing to seed"}
        ]}}"""
        val pick = ArchiveOrg.parseTorrentPicks(json).single()
        assertEquals("8b733f925be0dc728b4b01a1ddb978f4bb6d3451", pick.infoHash)
        assertEquals(1234L, pick.sizeBytes)
        assertTrue(pick.magnet.startsWith("magnet:?xt=urn:btih:8b733f925be0dc728b4b01a1ddb978f4bb6d3451&dn=His%20Girl%20Friday"))
        assertTrue("bt1.archive.org" in pick.magnet)
        assertFalse("the archive does not report swarm counts", pick.reportsHealth)
    }
}
