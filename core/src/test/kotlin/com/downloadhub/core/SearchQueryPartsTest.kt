package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching by what a copy should be, not only by what it is called.
 *
 * "Dune 2021 2160p x265 DTS-HD 5.1" is a request for a film *and* a specification.
 * The specification is not in any index's idea of a title, and every index ANDs or
 * phrases its tokens, so asking the whole sentence returns nothing at all while the
 * film sits right there under "Dune 2021".
 *
 * So the two are separated: the title is what an index is asked, and the
 * specification is applied to the answers - lifting the rows that have it, never
 * hiding the rows that do not.
 */
class SearchQueryPartsTest {

    private val dune4k =
        "Dune.Part.Two.2025.2160p.WEB-DL.DDP5.1.Atmos.HDR.x265-GROUP"
    private val dune1080 =
        "Dune.Part.Two.2025.1080p.WEB-DL.DDP5.1.H264-GROUP"

    @Test
    fun aTitleAndItsSpecificationAreTakenApart() {
        val parts = splitQuery("Dune 2021 2160p x265 DTS-HD 5.1")
        assertEquals("Dune 2021", parts.title)
        assertEquals(
            "the specification should have come off whole, not in pieces",
            listOf("2160p", "x265", "DTS-HD", "5.1"),
            parts.quality
        )
    }

    /**
     * The copy wanted is what is asked for, and it comes back ranked.
     */
    @Test
    fun theWantedCopyRanksAboveTheOneThatIsNotWanted() {
        val query = "Dune 2025 2160p x265"
        val wanted = relevanceOf(dune4k, query)
        val unwanted = relevanceOf(dune1080, query)
        assertTrue(
            "the 2160p x265 release should outrank the 1080p h264 one ($wanted vs $unwanted)",
            wanted > unwanted
        )
    }

    /**
     * A wanted copy that is not on offer does not make the search empty.
     *
     * This is the whole reason the specification is a preference and not a filter.
     * Someone on a metered connection asking for 2160p, in a search where only 1080p
     * exists, is better served by seeing the 1080p with the reason visible in the name
     * than by an empty list - which is also exactly what makes a search look broken.
     */
    @Test
    fun aResultWithoutTheWantedCopyIsStillShown() {
        val query = "Dune 2025 2160p x265"
        assertTrue(
            "the 1080p release must still match a search that asked for 2160p",
            matchesQuery(dune1080, query)
        )
    }

    /**
     * A long filename full of quality words does not outrank the film it claims to be.
     */
    @Test
    fun qualityWordsCannotOutweighTheTitle() {
        val exact = "Dune"
        val padded = "Dune 2160p x265 dts 5.1 hdr proper repack atmos hmax nf remux"
        assertTrue(
            "the plain title must still beat the one padded with specifications",
            relevanceOf(exact, "Dune 2160p x265 dts") > relevanceOf(padded, "Dune 2160p x265 dts")
        )
    }

    /**
     * A cast member is in no filename, so it must not cost the results.
     *
     * This is the case that made the original wording unusable: "Dune Zendaya" asked
     * an index for a film with an actor in it, and an index that is right to answer
     * precisely has nothing to say. The person still gets the film.
     */
    @Test
    fun aNameInTheQueryDoesNotCostResults() {
        assertTrue(
            "adding a cast member must not stop the film being found",
            matchesQuery(dune4k, "Dune Zendaya 2160p")
        )
        // The score may legitimately fall - a query carrying a word the filename does
        // not have *is* a looser query - but only to the partial tier. What must not
        // happen is the film dropping out of the results, which is what it did when the
        // whole sentence was handed to an index that ANDs its tokens.
        val withActor = relevanceOf(dune4k, "Dune Zendaya 2160p")
        assertTrue(
            "the result should still be a strong partial match, not a weak one ($withActor)",
            withActor >= 40
        )
    }

    /**
     * A query that is nothing but a specification has no title to ask about.
     *
     * Sending "1080p dts" to an index and then discarding everything for not being
     * about 1080p would be a search that finds nothing by construction.
     */
    @Test
    fun aQueryOfOnlySpecificationsStillAsksSomething() {
        val parts = splitQuery("1080p dts 5.1")
        assertTrue("there is no title in that: ${parts.title}", parts.titleIsEmpty)
        assertTrue(
            "the specification is what there is to ask",
            parts.quality.map { it.lowercase() }.containsAll(listOf("1080p", "dts"))
        )
        assertTrue(
            "a result that mentions them is still on topic",
            matchesQuery("Some.Movie.2020.1080p.DTS.WEB-DL", "1080p dts")
        )
    }

    /**
     * An ordinary title is not mistaken for a specification.
     *
     * The temptation with a word list is to swallow real search terms, and a title
     * that happens to contain one of the words - "Blade Runner", "HD", "The Raid" -
     * must keep its name.
     */
    @Test
    fun anOrdinaryTitleIsNotEatenByTheWordList() {
        assertEquals("Blade Runner 2049", splitQuery("Blade Runner 2049").title)
        assertEquals("The Raid 2", splitQuery("The Raid 2").title)
        assertEquals("Hereditary", splitQuery("Hereditary").title)
        // A year is not a resolution and not a codec, and must stay put.
        assertTrue(splitQuery("Alien 3").title.contains("Alien"))
    }

    /**
     * The three shapes a quality term arrives in.
     *
     * A whole word, a number with a letter on it, and a channel count that is not a
     * word at all. Missing any of them means the commonest specifications in a
     * filename are not recognised.
     */
    @Test
    fun qualityTermsAreRecognisedInAllThreeShapes() {
        listOf("bluray", "webrip", "web-dl", "repack", "proper", "hdr", "atmos", "nf")
            .forEach { assertTrue("$it should be a specification", it.isQualityTerm()) }
        listOf("2160p", "1080p", "720p", "480p", "4k", "x265", "x264", "hevc", "h264", "10bit")
            .forEach { assertTrue("$it should be a specification", it.isQualityTerm()) }
        listOf("5.1", "7.1", "2.0", "6.1", "dd5.1")
            .forEach { assertTrue("$it should be a specification", it.isQualityTerm()) }
        // And what must not be one.
        listOf("witcher", "batman", "2021", "dune", "alien").forEach {
            assertTrue("$it is a name, not a specification", !it.isQualityTerm())
        }
    }

    /**
     * A plain query is untouched by all of this.
     *
     * Every existing search has to behave exactly as it did, or this is a regression
     * dressed as a feature.
     */
    @Test
    fun anOrdinaryQueryIsUnchanged() {
        assertEquals("the witcher", splitQuery("the witcher").title)
        assertTrue(splitQuery("the witcher").quality.isEmpty())
        // A stop word is still a stop word: it carries no identity, so the two wordings
        // of the same search both find the film. The scores differ slightly, because a
        // longer query string is a slightly looser fit against a short name, and that
        // was true before any of this too.
        assertTrue(matchesQuery("The.Witcher.S01E01", "witcher"))
        assertTrue(matchesQuery("The.Witcher.S01E01", "the witcher"))
    }
}
