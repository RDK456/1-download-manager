package com.downloadhub.core

import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One thing a search found.
 *
 * The same shape every source returns, so the list can merge results from ten of them and
 * still sort them by one rule. [source] is kept because a user who gets a bad result wants
 * to know where it came from, and because a source that reports no swarm counts has to be
 * distinguished from one reporting zero - "no seeders" and "this site does not say" are
 * different claims and the list must not make the first out of the second.
 */
data class SearchResult(
    val infoHash: String,
    val name: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val source: String,
    /**
     * The magnet to hand the downloader.
     *
     * Present even when the source gave only an info hash, because [magnetFor] can build
     * one and a result the user can act on is worth more than one that is only a hash.
     */
    val magnet: String,
    val numFiles: Int = 0,
    /** When the source published it, in epoch millis, or zero when it did not say. */
    val addedAtEpochMillis: Long = 0L
) {
    /** Whether this source reports swarm health at all. */
    var reportsHealth: Boolean = true
        internal set
}

/** What a result can be found under. */
enum class SearchGroup(val label: String) {
    GAMES("Games"),
    MOVIES("Movies"),
    TV("TV"),
    ANIME("Anime");

    companion object {
        fun fromValue(value: String?): SearchGroup =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MOVIES
    }
}

/**
 * A place torrents are found.
 *
 * One interface so a new source is a class rather than a change to the search, the results
 * list and both user interfaces. Every implementation is asked for the same thing and every
 * one is allowed to fail: a source that is down says so in its own [SearchOutcome.failures]
 * entry and the search carries on, because ten sources where one is unreachable is a
 * working search and a search that gives up is not.
 */
interface SearchSource {
    val id: String
    val label: String

    /**
     * The groups this source answers for.
     *
     * Empty means it appears under "Everything" only. A general index belongs to several
     * and says so rather than being duplicated once per group.
     */
    val groups: Set<SearchGroup> get() = emptySet()

    /**
     * Whether this source reports seeders and peers.
     *
     * False means its feed carries none, so a zero there means unknown - and a filter that
     * drops rows with no seeders must never apply to those, or it would quietly hide every
     * result from a source that simply does not publish the number.
     */
    val reportsHealth: Boolean get() = true

    suspend fun search(query: String): List<SearchResult>
}

/** One source that could not answer, and why. */
data class SearchFailure(val source: String, val label: String, val reason: String)

/**
 * Everything one search produced: what was found, and what could not be asked.
 *
 * The failures are part of the result rather than an error thrown. "Four results, Nyaa is
 * unreachable" is a thing the user needs to be told, and it is also a thing that must not
 * stop the other nine from answering.
 */
data class SearchOutcome(
    val query: String,
    val results: List<SearchResult>,
    val failures: List<SearchFailure> = emptyList()
) {
    val isEmpty: Boolean get() = results.isEmpty()

    /** One line naming the sources that could not answer, or nothing. */
    fun offlineNote(): String? {
        if (failures.isEmpty()) return null
        if (failures.size == 1) {
            val f = failures.first()
            return "${f.label} could not be reached (${f.reason})."
        }
        val names = failures.joinToString(", ") { it.label }
        return "$names could not be reached. The rest of the results are complete."
    }
}

/**
 * Asks every source and merges what comes back.
 *
 * Concurrently, because they are independent and one of them being slow must not hold up
 * the others - a search that answers as each source arrives is far better than one that
 * waits for the slowest. Nothing here throws: a source that fails becomes a
 * [SearchFailure] and the search still returns.
 */
suspend fun searchSources(
    sources: List<SearchSource>,
    query: String,
    group: SearchGroup? = null,
    onPartial: (List<SearchResult>) -> Unit = {}
): SearchOutcome = coroutineScope {
    val applicable = sources.filter { group == null || group in it.groups || it.groups.isEmpty() }
    val trimmed = query.trim()

    // What the indexes are asked is the *title*, not the whole sentence.
    //
    // "Dune 2021 2160p x265 DTS-HD 5.1 Zendaya" asked whole asks every index for a
    // film with a particular codec, a particular audio layout and a particular person
    // in it. Every index ANDs its tokens or phrases them, so it comes back with
    // nothing - and the film is sitting right there under "Dune 2021". Worse, the
    // cast member is in no filename in any index, because a file is named after the
    // release and not after who is in it, so that token could only ever cost results.
    //
    // The wanted copy is not thrown away. It is applied to the answers by
    // [relevanceOf], where it lifts the rows that have it above the rows that do not
    // without ever hiding them - so a search for 2160p shows the 1080p when that is
    // all there is, ranked below it, which is a more useful answer than nothing.
    val ask = splitQuery(trimmed).toAsk

    val deferred = applicable.map { source ->
        async {
            // Its own failure, caught here rather than left to escape.
            //
            // A source that throws inside `async` cancels its siblings, so letting one out
            // would lose the other sources' results too - which is the exact opposite of
            // what a search over four sources should do when one of them is down.
            try {
                Answer.Ok(source, askLeniently(source, ask))
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                // Real cancellation - the user closed the window - is not a failed source,
                // and swallowing it would leave the search hanging after nobody wants it.
                throw cancellation
            } catch (failure: Throwable) {
                Answer.Failed(
                    source,
                    failure.message?.takeIf { it.isNotBlank() }
                        ?: failure::class.simpleName
                        ?: "failed"
                )
            }
        }
    }

    val found = ArrayList<SearchResult>()
    val failed = ArrayList<SearchFailure>()
    for (answer in deferred.awaitAll()) {
        when (answer) {
            is Answer.Ok -> found += answer.results.map { it.withSource(answer.source) }
            is Answer.Failed -> failed += SearchFailure(
                answer.source.id,
                answer.source.label,
                answer.reason
            )
        }
    }
    SearchOutcome(trimmed, mergeSearchResults(found), failed)
        .also { onPartial(it.results) }
}

/** What one source's part of a search came back as. */
private sealed interface Answer {
    class Ok(val source: SearchSource, val results: List<SearchResult>) : Answer
    class Failed(val source: SearchSource, val reason: String) : Answer
}

/**
 * Asks a source, and asks again more loosely when the first answer is nothing.
 *
 * Searching for a keyword or half a name has to work, and no single source can be
 * trusted to do it: each hands the query to a different API with different ideas
 * about what a match is. Some AND every token and return nothing for anything
 * short of the full title; one matches its own recent feed with a plain substring,
 * so "witch" finds "The Witcher" and "the witc" does not.
 *
 * So the full query goes first, and only if it produced nothing that actually
 * matches is it tried again - each meaningful word on its own, longest first. The
 * extra requests happen only for a search that found nothing, so a search that
 * worked the first time costs exactly what it did before.
 *
 * A source that fails on the first attempt is not retried: the fallback is for
 * sources that answered with nothing, not for ones that are down, and a second
 * round of failures would only delay the failure being reported.
 */
private suspend fun askLeniently(source: SearchSource, query: String): List<SearchResult> {
    if (query.isEmpty()) return emptyList()
    val first = source.search(query)
    if (first.isNotEmpty() && first.any { matchesQuery(it.name, query) }) {
        return rankByRelevance(first, query)
    }
    val seen = LinkedHashSet(first.map { it.infoHash.lowercase(Locale.US) })
    val gathered = ArrayList(first)
    for (narrower in relaxedQueries(query).take(MAX_FALLBACK_QUERIES)) {
        val extra = try {
            source.search(narrower)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The source answered the first query and then failed. That is a source
            // going down mid-search, not a query that was too narrow, so widening
            // again would only turn one failure into three.
            null
        } ?: break
        extra.filter { matchesQuery(it.name, query) }
            .filter { seen.add(it.infoHash.lowercase(Locale.US)) }
            .forEach { gathered += it }
        if (gathered.size >= MIN_USEFUL_FALLBACK_RESULTS) break
    }
    return rankByRelevance(gathered, query)
}

/** How many extra times one source may be asked before its first answer is trusted. */
private const val MAX_FALLBACK_QUERIES = 3

/** Stop widening once a fallback has clearly worked. */
private const val MIN_USEFUL_FALLBACK_RESULTS = 8

/** Stamps a result with its source's identity, including whether that source reports health. */
private fun SearchResult.withSource(source: SearchSource): SearchResult =
    also { it.reportsHealth = source.reportsHealth }

/**
 * Merges results from several sources and removes the duplicates.
 *
 * Two things are being dealt with. The same torrent legitimately appears on several
 * indexes, and listing it four times is worse than useless - the user cannot tell which
 * copy has the seeds. And a source that does not publish swarm counts arrives with zeroes,
 * so a duplicate whose first copy came from such a source must not win over a later copy
 * that knows better.
 *
 * So the first result for an info hash is kept, but its counts are raised if a later copy
 * knows more, and the label it is attributed to becomes the one that reported health when
 * only one of them did.
 */
fun mergeSearchResults(results: List<SearchResult>): List<SearchResult> {
    val byHash = LinkedHashMap<String, SearchResult>()
    for (result in results) {
        val key = result.infoHash.lowercase(Locale.US)
        val existing = byHash[key]
        if (existing == null) {
            byHash[key] = result
            continue
        }
        val knowsMore = result.reportsHealth && !existing.reportsHealth
        byHash[key] = existing.copy(
            name = if (result.name.length > existing.name.length) result.name else existing.name,
            // Never lower a count we already have, and never take zeroes from a source
            // that does not publish them.
            seeders = maxOf(existing.seeders, if (result.reportsHealth) result.seeders else 0),
            leechers = maxOf(existing.leechers, if (result.reportsHealth) result.leechers else 0),
            numFiles = maxOf(existing.numFiles, result.numFiles),
            addedAtEpochMillis = maxOf(existing.addedAtEpochMillis, result.addedAtEpochMillis),
            source = if (knowsMore) result.source else existing.source
        ).also { it.reportsHealth = existing.reportsHealth || result.reportsHealth }
    }
    return byHash.values.toList()
}

/**
 * Sorts the way a person choosing a download would: the ones that will actually arrive
 * first, and biggest first among equals.
 *
 * A result whose source does not publish seeders goes after those that do, because "we do
 * not know" and "nobody is sharing it" are different answers and putting them in one
 * arbitrary order is how a live result ends up under a dead one.
 */
fun sortSearchResults(results: List<SearchResult>): List<SearchResult> =
    results.sortedWith(
        compareByDescending<SearchResult> { it.reportsHealth }
            .thenByDescending { it.seeders }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.name.lowercase(Locale.US) }
    )

/**
 * A magnet link for an info hash.
 *
 * Built here rather than asked of the source, because several of them return only a hash
 * and a result the user cannot act on is not a result. [displayName] goes in as the name
 * so the downloader shows something better than forty hex characters.
 */
fun magnetFor(infoHash: String, displayName: String = ""): String {
    val hash = infoHash.trim().removePrefix("urn:btih:").lowercase(Locale.US)
    val name = displayName.trim().ifEmpty { hash }
    val encoded = name
        .replace("+", "%2B")
        .replace(" ", "%20")
    return "magnet:?xt=urn:btih:$hash&dn=$encoded"
}

/** Whether a query is worth sending. An empty box means "browse", which no source supports. */
fun isSearchable(query: String): Boolean = query.trim().length >= 2
