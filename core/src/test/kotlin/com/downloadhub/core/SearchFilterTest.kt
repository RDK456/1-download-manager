package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrowing a merged result list to particular sources.
 *
 * The list is already ranked when it arrives, so a filter that re-sorted it would move
 * rows for reasons that have nothing to do with the filter. And a filter that could be
 * in a third state - narrowing the list while claiming to show everything - would be
 * worse than having no filter at all.
 */
class SearchFilterTest {

    private fun row(source: String, hash: String) = SearchResult(
        infoHash = hash,
        name = "Release $hash",
        sizeBytes = 1_000_000L,
        seeders = 10,
        leechers = 2,
        source = source,
        magnet = "magnet:?xt=urn:btih:$hash"
    )

    private val results = listOf(
        row("yts", "a".repeat(40)),
        row("yts", "b".repeat(40)),
        row("thepiratebay", "c".repeat(40)),
        row("eztv", "d".repeat(40))
    )

    @Test
    fun everySourceThatAnsweredIsListedWithItsCount() {
        val counts = SearchFilter.sources(
            results,
            mapOf("yts" to "YTS", "thepiratebay" to "ThePirateBay", "eztv" to "EZTV")
        )
        // Two sources answered once each, and the order between them is the id's -
        // alphabetical, not whichever happened to reply first. Sources answer
        // concurrently, so arrival order is not a thing that can be relied on, and a
        // filter whose chips reshuffled between two identical searches would be read as
        // a different list each time.
        assertEquals(
            listOf("YTS" to 2, "EZTV" to 1, "ThePirateBay" to 1),
            counts.map { it.label to it.count }
        )
    }

    @Test
    fun twoSearchesOverTheSameResultsGiveTheSameFilter() {
        val one = SearchFilter.sources(results.reversed(), mapOf("yts" to "YTS"))
        val two = SearchFilter.sources(results, mapOf("yts" to "YTS"))
        assertEquals(
            "sources answer concurrently, so the filter must not depend on who replied first",
            one.map { it.id },
            two.map { it.id }
        )
    }

    @Test
    fun theSourceWithTheMostRowsComesFirst() {
        val counts = SearchFilter.sources(results, mapOf("eztv" to "EZTV"))
        assertEquals(
            "a filter listing sites should start with the one that answered best",
            "yts",
            counts.first().id
        )
    }

    @Test
    fun aSourceThatReturnedNothingIsNotListed() {
        // NYaa is in the shipped sources and answers nothing for a film, so it must not
        // appear as a row reading "Nyaa 0" - that is a question with no good answer.
        val counts = SearchFilter.sources(results, mapOf("nyaa" to "Nyaa"))
        assertTrue(counts.none { it.id == "nyaa" })
    }

    @Test
    fun aSourceWithNoLabelFallsBackToItsId() {
        val counts = SearchFilter.sources(listOf(row("mystery", "e".repeat(40))))
        assertEquals("mystery", counts.single().label)
    }

    @Test
    fun choosingNothingShowsEverything() {
        assertEquals(results, SearchFilter.apply(results, emptySet()))
    }

    @Test
    fun choosingASourceShowsOnlyItsRows() {
        val filtered = SearchFilter.apply(results, setOf("yts"))
        assertEquals(listOf("yts", "yts"), filtered.map { it.source })
    }

    @Test
    fun choosingSeveralSourcesShowsAllOfThem() {
        val filtered = SearchFilter.apply(results, setOf("yts", "eztv"))
        assertEquals(3, filtered.size)
    }

    @Test
    fun filteringKeepsTheOrderItWasGiven() {
        // The list arrived ranked. Rows moving because a filter was applied would mean
        // the ranking is not what it claims to be.
        val ranked = listOf(
            row("yts", "a".repeat(40)),
            row("eztv", "d".repeat(40)),
            row("yts", "b".repeat(40))
        )
        assertEquals(
            ranked.map { it.infoHash },
            SearchFilter.apply(ranked, setOf("yts", "eztv")).map { it.infoHash }
        )
    }

    @Test
    fun choosingASourceWithNoRowsShowsNothingRatherThanEverything() {
        // The opposite error, and the worse one: a filter that matched nothing and fell
        // back to the full list would look like the filter did not work.
        assertTrue(SearchFilter.apply(results, setOf("nyaa")).isEmpty())
    }
}