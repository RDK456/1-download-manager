package com.downloadhub.core

/**
 * Narrowing a merged result list down to the sources the user actually wants.
 *
 * A search merges every applicable source into one ranked list, which is right for
 * finding the best release and wrong for answering "what does this one site have?".
 * Someone who likes a particular index, or who wants to compare two, needs to take
 * the others out - and re-running the search with one source would be the wrong tool,
 * because a source is slow and flaky and the results are already in hand.
 *
 * So the filter works on what came back rather than on what was asked for.
 */
object SearchFilter {

    /**
     * The sources that appear in [results], with how many rows each contributed.
     *
     * Ordered by count, most first, so the source that answered best is the one nearest
     * the finger. A source that returned nothing is absent rather than shown at zero:
     * a row reading "FitGirl 0" next to a search that never asked FitGirl is a question
     * with no good answer, and the source list has one entry per site rather than one
     * entry per site that happened to reply.
     *
     * [labels] maps a source id to the name to show. An id with no entry falls back to
     * the id itself, which is never ideal but is always honest about where a row came
     * from - and a source added to [defaultSearchSources] without a label is a bug
     * worth seeing rather than hiding behind an empty chip.
     */
    fun sources(
        results: List<SearchResult>,
        labels: Map<String, String> = emptyMap()
    ): List<SearchSourceCount> {
        val counts = LinkedHashMap<String, Int>()
        for (result in results) {
            counts[result.source] = (counts[result.source] ?: 0) + 1
        }
        return counts.entries
            .map { (id, count) -> SearchSourceCount(id, labels[id] ?: id, count) }
            .sortedWith(compareByDescending<SearchSourceCount> { it.count }.thenBy { it.id })
    }

    /**
     * The rows of [results] from the chosen sources.
     *
     * An empty [selected] means every source, which is what the UI starts on. That is
     * the same answer as selecting all of them, so "none chosen" and "all chosen" can
     * never disagree - a filter that can be in a third state, showing a filtered list
     * while claiming to show everything, is worse than no filter.
     *
     * Order is left exactly as it came. The list is already ranked by relevance and
     * health together, and re-sorting after a filter would change rows' relative order
     * for reasons that have nothing to do with the filter.
     */
    fun apply(results: List<SearchResult>, selected: Set<String>): List<SearchResult> {
        if (selected.isEmpty()) return results
        return results.filter { it.source in selected }
    }
}

/** One source's share of a result list, as shown on a filter control. */
data class SearchSourceCount(
    /** The [SearchResult.source] value rows from this source carry. */
    val id: String,
    /** What to put on the control: the source's own name where it has one. */
    val label: String,
    /** How many rows it contributed. */
    val count: Int
)