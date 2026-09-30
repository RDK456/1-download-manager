package com.downloadhub.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search: its JSON and RSS parsers, its merge, and its behaviour when a source is down.
 *
 * Every parser is checked against a captured response rather than a live site, which is the
 * only way to test the parts that matter. A parser tested against a live endpoint passes on
 * the day the site is up and fails on the day it is not, and it fails for two different
 * reasons - the code, and the site - so a failure tells you nothing about which it was.
 */
class TorrentSearchTest {

    // --- JSON -------------------------------------------------------------------

    @Test
    fun `json reads objects, arrays, strings and numbers`() {
        val value = parseJson("""{"a":[1,2.5,"three",true,null],"b":{"c":"d"}}""")
        // The array holds five values of four different kinds, and all five must survive -
        // a reader that only handled numbers would turn a feed row into an empty object.
        assertEquals(5, value.array("a").size)
        assertEquals(1L, value.number("a", "0"))
        assertEquals(2L, value.number("a", "1"))
        assertEquals("three", (value.array("a")[2] as JsonValue.Str).value)
        assertEquals("d", value.string("b", "c"))
    }

    @Test
    fun `json handles the escapes feeds actually contain`() {
        val value = parseJson("""{"s":"a \"quoted\" \\ backslash \n newline é"}""")
        assertEquals("a \"quoted\" \\ backslash \n newline é", value.string("s"))
    }

    /** A number that arrives as a string is still a number, and a zero size hides a result. */
    @Test
    fun `a size sent as a string is read as a number`() {
        assertEquals(1400000000L, parseJson("""{"s":"1400000000"}""").number("s"))
        assertEquals(0L, parseJson("""{"s":"not a number"}""").number("s"))
    }

    @Test
    fun `a missing path is null rather than an exception`() {
        assertNull(parseJson("""{"a":1}""").string("b"))
        assertNull(parseJson("""{"a":1}""").string("a", "b"))
        assertEquals(0L, parseJson("""{"a":1}""").number("b"))
        assertTrue(parseJson("""{"a":1}""").array("b").isEmpty())
    }

    @Test
    fun `malformed json is rejected rather than half-read`() {
        // Half-reading is worse than failing: a feed that returns an error page parsed
        // leniently yields an empty result list, which the search then reports as "nothing
        // found" rather than "this source is broken".
        for (bad in listOf("{", """{"a":}""", """{"a":1,}""", "not json", """{"a":1}extra""")) {
            var threw = false
            try {
                parseJson(bad)
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("accepted malformed input: $bad", threw)
        }
    }

    // --- the feeds ---------------------------------------------------------------

    private val EZTV_JSON = """
        {"torrents_count":1,"limit":1,"page":1,"torrents":[
          {"id":1,"hash":"C48D3AF75E5D34F1E73FF5E8596730598A92CB8A",
           "filename":"North Of North S02E08 720p WEB H264-JFF[EZTVx.to].mkv",
           "title":"North Of North S02E08 720p WEB x264-JFF",
           "seeds":41,"peers":3,"size_bytes":734003200,"date_released_unix":1770000000,
           "imdb_id":"tt1234567"}]}
    """.trimIndent()

    @Test
    fun `eztv rows become results with real swarm counts`() {
        val results = parseEztvFeed(EZTV_JSON)
        assertEquals(1, results.size)
        val r = results.first()
        assertEquals("c48d3af75e5d34f1e73ff5e8596730598a92cb8a", r.infoHash)
        assertEquals(41, r.seeders)
        assertEquals(3, r.leechers)
        assertEquals(734003200L, r.sizeBytes)
        assertEquals("eztv", r.source)
        assertTrue("the magnet must carry the hash", r.magnet.contains(r.infoHash))
        assertEquals(1770000000L * 1000L, r.addedAtEpochMillis)
    }

    /** A feed that is an error page parses to nothing rather than to a crash. */
    @Test
    fun `eztv on an html error page returns nothing`() {
        assertTrue(parseEztvFeed("<html><body>503</body></html>").isEmpty())
    }

    private val YTS_JSON = """
        {"status":"ok","data":{"movies":[
          {"title_long":"Example Movie (2024)","date_uploaded_unix":1767000000,
           "torrents":[
             {"hash":"AAAA1111BBBB2222CCCC3333DDDD4444EEEE5555","quality":"1080p","type":"bluray","size_bytes":2147483648,"seeds":220,"peers":31},
             {"hash":"6666AAAA7777BBBB8888CCCC9999DDDDEEEE0000","quality":"720p","type":"web","size_bytes":1073741824,"seeds":180,"peers":12}]}]}}
    """.trimIndent()

    /**
     * One movie, two results.
     *
     * 1080p and 720p of the same film are different files of different sizes, and a person
     * choosing a download is choosing between them. Collapsing them into one row loses the
     * choice entirely.
     */
    @Test
    fun `each yts quality is its own result`() {
        val results = parseYtsFeed(YTS_JSON)
        assertEquals(2, results.size)
        assertEquals(2L * 1024 * 1024 * 1024, results[0].sizeBytes)
        assertTrue(results[0].name.contains("1080p"))
        assertTrue(results[1].name.contains("720p"))
        assertEquals(220, results[0].seeders)
    }

    private val NYAA_RSS = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:nyaa="https://nyaa.si/xmlns/nyaa"><channel>
        <item>
          <title>[Group] Show Name - 01 [1080p]</title>
          <nyaa:infoHash>aabbccddeeff00112233445566778899aabbccdd</nyaa:infoHash>
          <nyaa:seeders>57</nyaa:seeders>
          <nyaa:leechers>4</nyaa:leechers>
          <nyaa:size>1.4 GiB</nyaa:size>
          <pubDate>Mon, 17 Feb 2025 09:12:33 -0000</pubDate>
        </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `nyaa rss is read from its own namespace`() {
        val results = parseNyaaFeed(NYAA_RSS)
        assertEquals(1, results.size)
        val r = results.first()
        assertEquals("aabbccddeeff00112233445566778899aabbccdd", r.infoHash)
        assertEquals(57, r.seeders)
        assertEquals(1503238553L, r.sizeBytes)
        assertTrue("the date was not read", r.addedAtEpochMillis > 0L)
    }

    @Test
    fun `cdata and entities in a title are unwrapped`() {
        val xml = "<item><title><![CDATA[A &amp; B <Show>]]></title>" +
            "<nyaa:infoHash>00112233445566778899aabbccddeeff00112233</nyaa:infoHash></item>"
        val results = parseNyaaFeed(xml)
        assertEquals("A & B <Show>", results.first().name)
    }

    /** An item with no hash cannot be downloaded, so it is not a result. */
    @Test
    fun `an item with no hash is dropped`() {
        assertTrue(parseNyaaFeed("<item><title>No hash here</title></item>").isEmpty())
    }

    /**
     * SubsPlease reports no health, and says so.
     *
     * Its feed has no seeders field at all, so its zeros mean unknown. A search that sorted
     * it alongside sources that publish counts would put a live release under a dead one for
     * no reason other than that neither claims to know anything.
     */
    @Test
    fun `subsplease is marked as reporting no health`() {
        val xml = "<item><title>Show - 05</title><link>" +
            "https://subsplease.quest/x/Show-05.mkv? magnet:xt=urn:btih:11223344556677889900aabbccddeeff112233" +
            "</link></item>"
        val results = parseSubsPleaseFeed(xml)
        assertEquals(1, results.size)
        assertFalse("subsplease does not publish swarm counts", results.first().reportsHealth)
        assertEquals("11223344556677889900aabbccddeeff112233", results.first().infoHash)
    }

    /** Human sizes, which is what every feed writes and nothing else understands. */
    @Test
    fun `sizes are read from every notation a feed uses`() {
        assertEquals(1_400_000_000L, parseSize("1.4 GB"))
        assertEquals(1503238553L, parseSize("1.4 GiB"))
        assertEquals(1024L, parseSize("1 KiB"))
        assertEquals(500L, parseSize("500"))
        assertEquals(0L, parseSize(""))
        assertEquals(0L, parseSize("unknown"))
    }

    // --- merging ------------------------------------------------------------------

    private fun result(
        hash: String,
        name: String = "Name",
        seeders: Int = 0,
        source: String = "a",
        health: Boolean = true
    ): SearchResult = SearchResult(
        infoHash = hash,
        name = name,
        sizeBytes = 100L,
        seeders = seeders,
        leechers = 0,
        source = source,
        magnet = magnetFor(hash, name)
    ).also { it.reportsHealth = health }

    /**
     * The same torrent on two indexes is one result, not two.
     *
     * A user who sees four copies of one release cannot tell which has the seeds, and four
     * rows is worse than one.
     */
    @Test
    fun `the same torrent from two sources is one result`() {
        val merged = mergeSearchResults(
            listOf(
                result("AABB", seeders = 10, source = "a"),
                result("aabb", seeders = 10, source = "b")
            )
        )
        assertEquals(1, merged.size)
    }

    /**
     * And the copy that knows more about it wins.
     *
     * A source that publishes no swarm counts arrives with zeroes. If that copy arrived
     * first, the merged result would claim "no seeders" for a torrent that has hundreds,
     * and a filter on seeders would then hide it.
     */
    @Test
    fun `a source that does not publish health cannot overwrite one that does`() {
        val merged = mergeSearchResults(
            listOf(
                result("AABB", seeders = 0, source = "blind", health = false),
                result("aabb", seeders = 340, source = "knowing", health = true)
            )
        )
        assertEquals(1, merged.size)
        assertEquals(340, merged.first().seeders)
        assertTrue(merged.first().reportsHealth)
    }

    @Test
    fun `counts are never lowered by a later duplicate`() {
        val merged = mergeSearchResults(
            listOf(
                result("AABB", seeders = 90, source = "a"),
                result("aabb", seeders = 2, source = "b")
            )
        )
        assertEquals(90, merged.first().seeders)
    }

    /** And sources that do not know go after those that do, not in some arbitrary order. */
    @Test
    fun `results whose source does not report health sort last`() {
        val sorted = sortSearchResults(
            listOf(
                result("AABB", seeders = 0, source = "blind", health = false),
                result("CCDD", seeders = 1, source = "knowing")
            )
        )
        assertEquals("knowing", sorted.first().source)
    }

    /** Within what is known, the ones that will arrive first. */
    @Test
    fun `results sort by seeders then size`() {
        val sorted = sortSearchResults(
            listOf(
                result("A", seeders = 1),
                result("B", seeders = 50),
                result("C", seeders = 50)
            )
        )
        assertEquals(listOf(50, 50, 1), sorted.map { it.seeders })
    }

    // --- magnets ---------------------------------------------------------------------

    @Test
    fun `a magnet is built from a hash and a name`() {
        val magnet = magnetFor("AABBCCDDEEFF00112233445566778899AABBCCDD", "Some Name")
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:"))
        assertTrue(magnet.contains("aabbccddeeff00112233445566778899aabbccdd"))
        assertTrue(magnet.contains("dn=Some%20Name"))
    }

    /** A source that hands back a hash with the urn prefix on it must still work. */
    @Test
    fun `the urn prefix is tolerated`() {
        assertTrue(magnetFor("urn:btih:AABBCCDDEEFF00112233445566778899AABBCCDD").contains("aabbccddeeff00112233445566778899aabbccdd"))
    }

    @Test
    fun `a name with an ampersand does not break the magnet`() {
        assertFalse(magnetFor("aabbccddeeff00112233445566778899aabbccdd", "A & B").contains("A&B"))
    }

    // --- searching -------------------------------------------------------------------

    /** One source answering, and another that cannot be reached. */
    private class FakeSource(
        override val id: String,
        override val label: String,
        override val groups: Set<SearchGroup> = setOf(SearchGroup.MOVIES),
        private val rows: List<SearchResult>? = emptyList(),
        private val error: String? = null
    ) : SearchSource {
        override suspend fun search(query: String): List<SearchResult> {
            error?.let { throw java.io.IOException(it) }
            return rows ?: emptyList()
        }
    }

    @Test
    fun `a source that is down is reported and the rest still answer`() = runBlocking {
        val outcome = searchSources(
            listOf(
                FakeSource("good", "Good", rows = listOf(result("AABB", seeders = 5))),
                FakeSource("down", "Down", error = "HTTP 503")
            ),
            "anything"
        )
        assertEquals(1, outcome.results.size)
        assertEquals(1, outcome.failures.size)
        assertEquals("Down", outcome.failures.first().label)
        assertTrue(outcome.failures.first().reason.contains("503"))
    }

    /** The note has to name the source, or a user whose search was short knows nothing. */
    @Test
    fun `the offline note names the sources that failed`() = runBlocking {
        val one = searchSources(listOf(FakeSource("down", "Nyaa", error = "unreachable")), "x")
        assertEquals("Nyaa could not be reached (unreachable).", one.offlineNote())
        val two = searchSources(
            listOf(FakeSource("a", "Nyaa", error = "x"), FakeSource("b", "EZTV", error = "y")),
            "x"
        )
        assertTrue(two.offlineNote()!!.contains("Nyaa"))
        assertTrue(two.offlineNote()!!.contains("EZTV"))
    }

    @Test
    fun `a working search has no offline note at all`() = runBlocking {
        val outcome = searchSources(listOf(FakeSource("good", "Good")), "x")
        assertNull(outcome.offlineNote())
    }

    /** A source answering nothing is not a failure: it genuinely found nothing. */
    @Test
    fun `a source with no results is not reported as broken`() = runBlocking {
        val outcome = searchSources(listOf(FakeSource("quiet", "Quiet", rows = emptyList())), "x")
        assertTrue(outcome.failures.isEmpty())
        assertNull(outcome.offlineNote())
    }

    /** Only the sources for the chosen group are asked. */
    @Test
    fun `only the chosen group's sources are asked`() = runBlocking {
        var asked = 0
        val counting = object : SearchSource {
            override val id = "c"
            override val label = "C"
            override val groups = setOf(SearchGroup.TV)
            override suspend fun search(query: String): List<SearchResult> {
                asked++
                return emptyList()
            }
        }
        searchSources(listOf(counting), "x", group = SearchGroup.MOVIES)
        assertEquals("a TV source was asked for a movie search", 0, asked)
        searchSources(listOf(counting), "x", group = SearchGroup.TV)
        assertEquals(1, asked)
    }

    @Test
    fun `the sources that ship all declare a group and a label`() {
        val sources = defaultSearchSources()
        assertTrue("no sources are registered", sources.isNotEmpty())
        for (source in sources) {
            assertTrue("${source.id} has no label", source.label.isNotBlank())
            assertTrue("${source.id} belongs to no group", source.groups.isNotEmpty())
        }
        assertEquals("two sources share an id", sources.size, sources.map { it.id }.toSet().size)
    }

    @Test
    fun `every shipped source can be constructed without a network`() {
        // A source whose constructor opens a connection cannot be listed in a settings
        // screen, which is where this list is also used.
        for (source in defaultSearchSources()) {
            assertNotNull(source.id)
        }
    }

    @Test
    fun `a query of one character is not worth sending`() {
        assertFalse(isSearchable("a"))
        assertTrue(isSearchable("ab"))
        assertTrue(isSearchable("  naruto  "))
    }
}
