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
    val parts = splitQuery(query)
    val base = if (parts.titleIsEmpty) {
        // A query of nothing but specifications - "1080p dts". There is no title to
        // ask about, so everything that mentions them is equally on topic and the
        // quality bonus is the whole of the answer.
        //
        // This is not the same as a query of nothing but *common words*, which is
        // handled below and scores zero. "The of a" identifies nothing and matches
        // nothing; "1080p dts" says what is wanted of a copy and matches anything that
        // offers it. Collapsing the two would mean either searching for common words
        // returns everything, or searching by specification returns nothing.
        BASE_FOR_SPECIFICATION_ONLY
    } else {
        relevanceOfTitle(name, parts.toAsk)
    }
    if (base <= 0) return 0
    return (base + qualityBonus(name, parts.quality)).coerceAtMost(100)
}

/**
 * What a result scores when the query named no film at all.
 *
 * Below every tier that involves a title matching, so a copy found by specification
 * sorts under the right film rather than above it.
 */
private const val BASE_FOR_SPECIFICATION_ONLY = 30

/**
 * The score for the part of the query that identifies the thing.
 *
 * This is the original five tiers, unchanged, applied to the title alone. Keeping it
 * whole is the point: the tiers are about identity, and a codec is not an identity.
 */
private fun relevanceOfTitle(name: String, titleQuery: String): Int {
    val nameWords = significantWords(name)
    val queryWords = significantWords(titleQuery)
    if (queryWords.isEmpty()) {
        // A query of nothing but common words. It identifies nothing, so it matches
        // nothing - which is the whole reason a list of stop words exists, and the
        // reason this is not the same case as a query of pure specifications.
        return 0
    }

    val squashedName = squash(name)
    val squashedQuery = squash(titleQuery)
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

/**
 * What the wanted copy is worth to a result that has it.
 *
 * A preference, never a requirement. Asking for 2160p and being shown 1080p is a
 * reasonable answer - it ranks below the 2160p, and the reason is visible in the name
 * - while refusing to show it at all means the search returns nothing whenever the
 * wanted copy is not on offer. Someone on a metered connection asking for 1080p
 * deserves to see that nothing at 1080p was found, not an empty list.
 *
 * So it is added to a score and never subtracted from the right to be listed. It is
 * capped hard, because a long filename can carry ten of these and they would otherwise
 * outweigh the title and put "Dune 2160p x265 dts 5.1 hdr proper repack" above the
 * exact film it claims to be.
 */
private fun qualityBonus(name: String, quality: List<String>): Int {
    if (quality.isEmpty()) return 0
    val squashedName = squash(name)
    val found = quality.count { term -> squashedName.contains(squash(term)) }
    if (found == 0) return 0
    return (QUALITY_BONUS_EACH * found).coerceAtMost(QUALITY_BONUS_MAX)
}

/** What one wanted quality term is worth when the result has it. */
private const val QUALITY_BONUS_EACH = 6

/**
 * The most the quality terms can add.
 *
 * Twelve points: enough to lift a 2160p release above a 1080p one that matched the
 * title slightly better, and not enough to outrank a clearly closer title.
 */
private const val QUALITY_BONUS_MAX = 12

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
