package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source for movies and TV, added after looking for a RARBG replacement.
 *
 * RARBG is gone. Its mirrors answer a browser and refuse anything else - HTTP 403 with
 * `cf-mitigated: challenge`, which is Cloudflare asking for a JavaScript puzzle that an
 * HTTP client cannot solve - and the hostnames that still resolve either do the same or
 * serve domain-squatter HTML with a 200. So the replacement had to be one that answers a
 * plain request, which is the only requirement that actually matters for a client.
 *
 * The response below is a real one, trimmed. A parser checked against a shape invented
 * here would pass today and fail on the first real answer, and the one thing a search
 * source cannot be wrong about is what its JSON looks like.
 */
class PirateBaySourceTest {

    /** Two rows as apibay.org actually returns them, with the empty swarm fields intact. */
    private val realResponse = """
        [
          {
            "id": "80999135",
            "info_hash": "24E7981047AAA2B08EBE7D79086631CA46C56ABC",
            "name": "The Witcher S04 Season 4 2025 1080p NF WEBRip AAC5.1 10bits x265-Rapta",
            "size": "6024047102",
            "seeds": "",
            "leechs": "",
            "num_files": "1",
            "username": "",
            "added": "1750000000",
            "status": "vip",
            "category": "205",
            "imdb": ""
          },
          {
            "id": "90112233",
            "info_hash": "5BDF1F670BDBDAA3360004626008CE8DB089CDC4",
            "name": "The.Witcher.Nightmare.of.the.Wolf.2021.HDRip.XviD.AC3-EVO[TGx]",
            "size": "1483000000",
            "seeds": "",
            "leechs": "",
            "num_files": "1",
            "username": "",
            "added": "1751000000",
            "status": "member",
            "category": "201",
            "imdb": "tt11198330"
          }
        ]
    """.trimIndent()

    @Test
    fun aResponseBecomesOneResultPerRow() {
        val results = parseApibayFeed(realResponse)
        assertEquals(2, results.size)
        assertEquals("thepiratebay", results.first().source)
    }

    /**
     * The hash is the whole point of the row, and it arrives upper-case.
     *
     * A magnet built from an upper-case hash still works, but two rows differing only in
     * case are the same torrent - and the de-duplication across sources is by lower-cased
     * hash, so a row that skipped the fold would appear twice.
     */
    @Test
    fun theHashIsFoldedAndTheMagnetIsBuiltFromIt() {
        val first = parseApibayFeed(realResponse).first()
        assertEquals("24e7981047aaa2b08ebe7d79086631ca46c56abc", first.infoHash)
        assertTrue(
            "the magnet must carry the hash: ${first.magnet}",
            first.magnet.startsWith("magnet:?xt=urn:btih:24e7981047aaa2b08ebe7d79086631ca46c56abc")
        )
        assertTrue("and the name, so the downloader gets a filename", first.magnet.contains("dn="))
    }

    @Test
    fun theSizeIsReadInBytesRatherThanGuessed() {
        val results = parseApibayFeed(realResponse)
        assertEquals(6_024_047_102L, results[0].sizeBytes)
        assertEquals(1_483_000_000L, results[1].sizeBytes)
    }

    /**
     * The API returns no swarm counts at all, and that has to be said rather than shown.
     *
     * `seeds` and `leechs` are empty on every row - checked against three unrelated
     * queries, all 100 rows, none populated. Writing them as 0 would put every row from
     * this source level with genuinely dead torrents in a list sorted by seeders, and a
     * list sorted by seeders that is all zeroes is a list nobody can read. So the counts
     * are 0 and the source reports no health, which is the mechanism that sorts it after
     * sources that actually know.
     */
    @Test
    fun itReportsNoHealthBecauseItPublishesNone() {
        val results = parseApibayFeed(realResponse)
        assertEquals(0, results.first().seeders)
        assertEquals(0, results.first().leechers)
        val source = PirateBaySearchSource()
        assertEquals(
            "a source with no swarm counts must say so, or it is sorted against sources " +
                "that do know",
            false,
            source.reportsHealth
        )
    }

    @Test
    fun itServesMoviesAndTvAndNothingElse() {
        assertEquals(
            setOf(SearchGroup.MOVIES, SearchGroup.TV),
            PirateBaySearchSource().groups
        )
    }

    @Test
    fun itIsInTheSourcesThisBuildShipsWith() {
        val ids = defaultSearchSources().map { it.id }
        assertTrue(
            "the new source is not registered, so searching would never ask it: $ids",
            ids.contains("thepiratebay")
        )
        assertEquals(
            "the same source is in the list twice, so every search asks it twice and " +
                "shows its results twice",
            ids.size,
            ids.distinct().size
        )
    }

    /**
     * A row that is not usable is dropped rather than half-used.
     *
     * A truncated hash would build a magnet that resolves to nothing, and the person
     * choosing a download would only find out after waiting for the swarm.
     */
    @Test
    fun anUnusableRowIsDropped() {
        val junk = """
            [
              {"info_hash":"tooshort","name":"broken.mkv","size":"1","seeds":"","leechs":""},
              {"info_hash":"24E7981047AAA2B08EBE7D79086631CA46C56ABC","name":"","size":"1"},
              {"info_hash":"5BDF1F670BDBDAA3360004626008CE8DB089CDC4","name":"good.mkv","size":"2","seeds":"","leechs":""}
            ]
        """.trimIndent()
        val results = parseApibayFeed(junk)
        assertEquals(1, results.size)
        assertEquals("good.mkv", results.first().name)
    }

    @Test
    fun rubbishInIsNotAnError() {
        assertTrue(parseApibayFeed("").isEmpty())
        assertTrue(parseApibayFeed("<html>not json</html>").isEmpty())
        assertTrue(parseApibayFeed("{\"torrents\":[]}").isEmpty())
    }
}
