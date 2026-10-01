package com.downloadhub.core

import java.util.Locale

/**
 * How well a result answers a query, 0 meaning not at all.
 *
 * Searching for a keyword or half a name has to work, and the sources cannot be
 * relied on to do it: some take the query straight to an API that ANDs its tokens
 * and returns nothing for anything less than the exact title, and one matches its
 * own recent feed with a plain substring - so "witch" finds "The Witcher" but
 * "the witc" does not, because a substring has to be contiguous.
 *
 * So the decision is made here, once, over the text both sides actually have,
 * and every source is judged the same way. The tiers, strongest first:
 *
 * - the name is the query, once punctuation and spacing are ignored;
 * - the name contains the whole query;
 * - every word of the query appears somewhere in the name, in any order;
 * - some word of the query appears - this is the half-name case;
 * - a run of characters from the query appears with the spaces taken out, which
 *   is what catches "lastofus" for "The Last of Us".
 *
 * A query word is only counted when it is long enough to mean something. "a",
 * "of" and "the" appear in most titles and matching on them returns everything,
 * which is the same as not searching.
 */
fun relevanceOf(name: String, query: String): Int {
    val nameWords = significantWords(name)
    val queryWords = significantWords(query)
    if (queryWords.isEmpty()) return 0

    val squashedName = squash(name)
    val squashedQuery = squash(query)
    if (squashedQuery.isEmpty()) return 0

    if (squashedName == squashedQuery) return 100
    if (squashedName.contains(squashedQuery)) {
        // Both "The Witcher" and "The Witcher 3: Wild Hunt" contain "witcher", so
        // containment alone cannot order them - and the shorter one is the closer
        // match. The penalty is capped so a long title is never pushed below a
        // completely different one that merely scored lower for other reasons.
        val slack = (squashedName.length - squashedQuery.length).coerceIn(0, 25)
        return 90 - slack
    }

    // Every meaningful word present, whatever the order: "of us last the" still
    // finds "The Last of Us".
    val hits = queryWords.count { word -> nameWords.any { it.contains(word) } }
    if (hits == queryWords.size) {
        // Longer names are looser matches, so the margin prefers a name that is
        // closer to the query rather than merely longer than it.
        return 80 - (nameWords.size - queryWords.size).coerceAtLeast(0).coerceAtMost(20)
    }

    // Some of them. This is the half-name case, and a single strong word is
    // worth more than several weak ones: "witcher" beats "us 2011".
    val single = queryWords.size == 1
    val partial = if (single) 60 else 40
    val longest = queryWords.maxByOrNull { it.length }?.length ?: 0
    return if (hits > 0) (partial + longest).coerceAtMost(75) else 0
}

/** Whether a result is worth showing at all for a query. */
fun matchesQuery(name: String, query: String): Boolean = relevanceOf(name, query) > 0

/**
 * Progressively broader queries to try when a source came back empty.
 *
 * The order matters and is deliberate: the full query first, then the query with
 * its later words dropped, then each meaningful word alone from longest to
 * shortest. "The Last of Us" therefore also tries "The Last of", "The Last",
 * "Last" and "The" - so a source that ANDs every token strictly still gets a
 * chance with the one word that identifies the title.
 *
 * Only used when the first attempt found nothing, because asking every source
 * four times for a search that already worked would quadruple the requests.
 */
fun relaxedQueries(query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.length < 2) return emptyList()
    val words = trimmed.split(' ').filter { it.isNotBlank() }
    val out = LinkedHashSet<String>()

    for (drop in 1 until words.size) {
        // The leading words, longest first: "the last of us" -> "the last of".
        val candidate = words.take(words.size - drop).joinToString(" ")
        // A leading-words attempt made of nothing but stop words is worse than no
        // attempt: asking a source for "the" returns its whole catalogue, which
        // would be ranked as though it answered the question.
        if (candidate.split(' ').any { it.isMeaningful() }) out += candidate
    }
    // Every meaningful word on its own, longest first, so the identifying word is
    // tried before the common one.
    words.map { it.lowercase(Locale.US) }
        .filter { it.isMeaningful() }
        .sortedByDescending { it.length }
        .forEach { out += it }

    out.remove(trimmed.lowercase(Locale.US))
    return out.filter { it.length >= 2 }
}

/** Whether a single word is worth sending on its own. */
private fun String.isMeaningful(): Boolean =
    length >= MIN_WORD && lowercase(Locale.US) !in STOP_WORDS

/**
 * Words worth matching on, lowercased, with the punctuation stripped off each.
 *
 * The length floor alone is not enough: "the" is exactly three characters, so it
 * passes a floor set at three, and a query of "the of" would then match most
 * titles in existence. [STOP_WORDS] catches the grammatical glue that appears in
 * half of all titles and identifies none of them.
 */
private fun significantWords(value: String): List<String> =
    value.lowercase(Locale.US)
        .split(NOT_A_WORD)
        .map { it.trim() }
        .filter { it.length >= MIN_WORD && it !in STOP_WORDS }

/**
 * Short, common, and meaningless on their own.
 *
 * A short list, deliberately. Every word here is safe to drop because it carries
 * no identity of its own - it is not a name, a place or a year - and a longer list
 * would start eating real search terms.
 */
private val STOP_WORDS = setOf(
    "the", "and", "for", "with", "from", "that", "this", "these", "those",
    "you", "are", "was", "were", "his", "her", "its", "our", "their",
    "not", "but", "all", "out", "who", "what", "when", "where", "how",
    "into", "over", "than", "then", "them", "they", "will", "with", "full",
    "part", "vol", "volume", "complete", "edition", "uncut"
)

/** The same text with every separator removed, for matching across spacing. */
private fun squash(value: String): String =
    value.lowercase(Locale.US).filter { it.isLetterOrDigit() }

/** Below this a "word" matches so much that matching on it returns everything. */
private const val MIN_WORD = 3

private val NOT_A_WORD = Regex("[^\\p{L}\\p{N}]+")

/**
 * Keeps what matches, ranked by how well.
 *
 * A source answers with whatever its own API decided was relevant, which is
 * often a wider set than the query asked for. This is where the width is cut,
 * and the best matches come first rather than the source's own order.
 */
fun rankByRelevance(results: List<SearchResult>, query: String): List<SearchResult> {
    val scored = results.map { it to relevanceOf(it.name, query) }
    val kept = scored.filter { it.second > 0 }
    return if (kept.isEmpty()) {
        // Nothing scored. Returning nothing would turn "the source is being
        // unhelpful" into "there are no results", and the user cannot tell those
        // apart - so its own results are left alone rather than discarded.
        results
    } else {
        kept.sortedWith(
            compareByDescending<Pair<SearchResult, Int>> { it.second }
                .thenByDescending { if (it.first.reportsHealth) 1 else 0 }
                .thenByDescending { it.first.seeders }
        ).map { it.first }
    }
}
