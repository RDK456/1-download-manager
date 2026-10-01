package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A keyword or half a name has to find the thing.
 *
 * The sources could not be relied on to do it between them: each handed the query
 * to a different API with a different idea of a match, one of them matched its own
 * feed with a plain substring, and a search that found nothing stayed empty. These
 * are the cases that were returning nothing.
 */
class SearchMatchingTest {

    private fun score(name: String, query: String) = relevanceOf(name, query)

    @Test
    fun theFullNameStillScoresHighest() {
        val exact = score("The Last of Us", "the last of us")
        val half = score("The Last of Us", "last of us")
        val loose = score("The Last of Us", "witcher")
        assertTrue("an exact name must beat a partial one: $exact vs $half", exact > half)
        assertTrue("a partial name must beat a single word: $half vs $loose", half > loose)
    }

    /** The case that returned nothing: a substring has to be contiguous, and this is not. */
    @Test
    fun aHalfWordThatIsNotContiguousStillMatches() {
        assertTrue("'the witc' must find 'The Witcher'", score("The Witcher", "the witc") > 0)
        assertTrue("'witch' must find 'The Witcher'", score("The Witcher", "witch") > 0)
    }

    @Test
    fun aKeywordAloneFindsIt() {
        assertTrue(score("Cyberpunk 2077 Phantom Liberty", "phantom") > 0)
        assertTrue(score("Baldur's Gate 3", "baldur") > 0)
    }

    @Test
    fun spacingAndPunctuationDoNotHideAMatch() {
        assertTrue(
            "'lastofus' must find 'The Last of Us'",
            score("The Last of Us", "lastofus") > 0
        )
        assertTrue(
            "apostrophes must not matter",
            score("Baldur's Gate", "baldurs") > 0
        )
    }

    @Test
    fun wordOrderDoesNotMatter() {
        assertTrue(
            "'of us last the' must find 'The Last of Us'",
            score("The Last of Us", "of us last the") > 0
        )
    }

    /**
     * "the" and "of" appear in most titles. Matching on them would return almost
     * everything, which is the same as not searching.
     */
    @Test
    fun theCommonWordsAreNotTreatedAsASearch() {
        assertEquals(
            "a query of only common words matches nothing rather than everything",
            0,
            score("The Lord of the Rings", "the of")
        )
    }

    @Test
    fun somethingUnrelatedDoesNotMatch() {
        assertEquals(0, score("The Last of Us", "witcher"))
        assertEquals(0, score("Baldur's Gate 3", "cyberpunk"))
    }

    @Test
    fun theFallbackWidensInAUsefulOrder() {
        val relaxed = relaxedQueries("The Last of Us")
        assertTrue(
            "the leading words must be tried before single words: $relaxed",
            relaxed.indexOf("the last of") < relaxed.indexOf("last")
        )
        assertTrue(
            "the identifying word must be among the fallbacks: $relaxed",
            relaxed.contains("last")
        )
        assertTrue(
            "a fallback of nothing but stop words returns everything and answers " +
                "nothing, so 'the' must not be tried on its own: $relaxed",
            !relaxed.any { it.equals("the", ignoreCase = true) }
        )
        assertTrue(
            "the query itself must not be repeated as a fallback: $relaxed",
            !relaxed.any { it.equals("the last of us", ignoreCase = true) }
        )
    }

    @Test
    fun aSingleWordQueryHasNoUsefulFallback() {
        assertTrue(
            "there is nothing narrower than one word",
            relaxedQueries("witcher").isEmpty()
        )
    }

    @Test
    fun shortQueriesAreStillSearchable() {
        assertTrue("a two character query is still worth sending", isSearchable("ab"))
        assertTrue(!isSearchable("a"))
    }

    @Test
    fun rankingPutsTheBestMatchFirst() {
        val results = listOf(
            result("Some Unrelated Game 2011"),
            result("The Witcher 3: Wild Hunt"),
            result("The Witcher")
        )
        val ranked = rankByRelevance(results, "witcher")
        assertEquals("The Witcher", ranked.first().name)
        assertTrue("the unrelated row is dropped", ranked.none { it.name.contains("Unrelated") })
    }

    /**
     * A source that returns nothing relevant would otherwise be indistinguishable
     * from a source with no such title, and the user cannot act on either.
     */
    @Test
    fun aSourceThatScoredNothingKeepsItsOwnResults() {
        val results = listOf(result("Alpha"), result("Beta"))
        assertEquals(
            "results must not be silently discarded when nothing scored",
            2,
            rankByRelevance(results, "zzzz").size
        )
    }

    private fun result(name: String) = SearchResult(
        infoHash = name.hashCode().toString(16),
        name = name,
        sizeBytes = 1L,
        seeders = 1,
        leechers = 0,
        source = "test",
        magnet = "magnet:?xt=urn:btih:" + name.hashCode().toString(16)
    )
}
