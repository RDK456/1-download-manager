package com.downloadhub.core

import java.net.URLEncoder

// --- parsers, separated from the network so they can be tested ---------------------
//
// Every one of these takes the feed's text and returns results. None of them opens a
// connection. That split is the point: a parser can be checked against a captured response
// without a network, without a site being up, and without a change at the far end breaking
// a test suite that happens to be running.

/** EZTV's `get-torrents`, which is JSON with real swarm counts. */
fun parseEztvFeed(json: String): List<SearchResult> {
    val root = runCatching { parseJson(json) }.getOrElse { return emptyList() }
    return root.array("torrents").mapNotNull { row ->
        val hash = row.string("hash")?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = row.string("filename")?.takeIf { it.isNotBlank() }
            ?: row.string("title")
            ?: return@mapNotNull null
        SearchResult(
            infoHash = hash,
            name = title,
            sizeBytes = row.number("size_bytes"),
            seeders = row.number("seeds").toInt(),
            leechers = row.number("peers").toInt(),
            numFiles = row.number("torrents").let { 0L }.toInt(),
            source = "eztv",
            magnet = magnetFor(hash, title),
            addedAtEpochMillis = row.number("date_released_unix") * 1000L
        )
    }
}

/**
 * YTS's `list_movies`, one movie carrying several torrents.
 *
 * Each torrent becomes its own result, because that is what a person choosing a download
 * is choosing - 1080p and 720p of the same film are different files of different sizes,
 * and collapsing them loses the choice. The movie's name goes on every one of them.
 */
fun parseYtsFeed(json: String): List<SearchResult> {
    val root = runCatching { parseJson(json) }.getOrElse { return emptyList() }
    val out = ArrayList<SearchResult>()
    for (movie in root.array("data", "movies")) {
        val base = movie.string("title_long") ?: movie.string("title") ?: continue
        val added = movie.number("date_uploaded_unix") * 1000L
        for (torrent in movie.array("torrents")) {
            val hash = torrent.string("hash")?.lowercase()?.takeIf { it.isNotBlank() }
                ?: continue
            val tag = listOfNotNull(
                torrent.string("quality")?.takeIf { it.isNotBlank() },
                torrent.string("type")?.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            val name = if (tag.isEmpty()) base else "$base [$tag]"
            out += SearchResult(
                infoHash = hash,
                name = name,
                sizeBytes = torrent.number("size_bytes"),
                seeders = torrent.number("seeds").toInt(),
                leechers = torrent.number("peers").toInt(),
                source = "yts",
                magnet = magnetFor(hash, name),
                addedAtEpochMillis = added
            )
        }
    }
    return out
}

/** Nyaa's RSS, which puts its counts in its own namespace. */
fun parseNyaaFeed(xml: String): List<SearchResult> =
    rssItems(xml).mapNotNull { item ->
        val hash = rssTag(item, "nyaa:infoHash").lowercase().takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val name = rssTag(item, "title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SearchResult(
            infoHash = hash,
            name = name,
            sizeBytes = parseSize(rssTag(item, "nyaa:size")),
            seeders = rssTag(item, "nyaa:seeders").toIntOrNull() ?: 0,
            leechers = rssTag(item, "nyaa:leechers").toIntOrNull() ?: 0,
            source = "nyaa",
            magnet = magnetFor(hash, name),
            addedAtEpochMillis = parseRssDate(rssTag(item, "pubDate"))
        )
    }

/**
 * SubsPlease's RSS, which is Anime and reports no swarm counts at all.
 *
 * It is there because SubsPlease is where current anime episodes actually are, and it is
 * marked as reporting no health so a search can order it after sources that do know,
 * rather than sorting it alongside them on zeroes.
 */
fun parseSubsPleaseFeed(xml: String): List<SearchResult> =
    rssItems(xml).mapNotNull { item ->
        val title = rssTag(item, "title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val link = rssTag(item, "link")
        val hash = magnetHashFrom(link)
            ?: rssTag(item, "nyaa:infoHash").lowercase().takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        SearchResult(
            infoHash = hash,
            name = title,
            sizeBytes = parseSize(rssTag(item, "nyaa:size")),
            seeders = 0,
            leechers = 0,
            source = "subsplease",
            magnet = magnetFor(hash, title),
            addedAtEpochMillis = parseRssDate(rssTag(item, "pubDate"))
        ).also { it.reportsHealth = false }
    }

/** The info hash inside a magnet link, or null when it is not one. */
internal fun magnetHashFrom(link: String): String? =
    Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        .find(link)
        ?.groupValues
        ?.get(1)
        ?.lowercase()
        ?.takeIf { it.length >= 32 }

/**
 * The Pirate Bay's public API, which is what apibay.org serves.
 *
 * It is the one index found while looking for a RARBG replacement that answers a
 * scripted request at all: RARBG's mirrors sit behind a Cloudflare JavaScript
 * challenge - they answer a browser and return `403` with `cf-mitigated: challenge`
 * to anything else - so a torrent client cannot read them. This returns JSON to
 * an ordinary HTTP request, which is the whole requirement.
 *
 * Fields are `id`, `info_hash`, `name`, `size` in bytes, `seeds`, `leechs` and
 * `category`.
 */
fun parseApibayFeed(json: String): List<SearchResult> {
    val root = runCatching { parseJson(json) }.getOrElse { return emptyList() }
    // A top-level array, not an object with a named list in it - so no path is walked.
    // Reading it as an object is how this returned nothing at all while the site was
    // answering perfectly: `array("torrents")` looks for a field that is not there, finds
    // nothing, and reports an empty result rather than an error.
    return root.array().mapNotNull { row ->
        val hash = row.string("info_hash")?.lowercase()?.takeIf { it.length == 40 } ?: return@mapNotNull null
        val name = row.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SearchResult(
            infoHash = hash,
            name = name,
            sizeBytes = row.number("size"),
            // Empty rather than zero, because they *are* empty. The API returns no
            // swarm counts at all, and writing 0 would put every row in this source
            // alongside genuinely dead ones instead of apart from them - which is what
            // reportsHealth is for.
            seeders = row.string("seeds")?.trim()?.toIntOrNull() ?: 0,
            leechers = row.string("leechs")?.trim()?.toIntOrNull() ?: 0,
            source = "thepiratebay",
            magnet = magnetFor(hash, name),
            addedAtEpochMillis = row.string("time")?.trim()?.toLongOrNull()?.times(1000L)
                ?: row.string("added")?.trim()?.toLongOrNull()?.times(1000L)
                ?: 0L
        )
    }
}
// --- the sources ---------------------------------------------------------------------

/**
 * EZTV: television.
 *
 * Its API takes no text query - it reads limit, page and imdb_id, and silently ignores
 * keywords, answering every search with the same unfiltered page. So a show name has to be
 * matched against its recent feed first and the matching shows asked for by id. Without
 * that a search for anything returns whatever was released today, which reads as a broken
 * search rather than as a broken integration.
 */
class EztvSearchSource : SearchSource {
    override val id = "eztv"
    override val label = "EZTV"
    override val groups = setOf(SearchGroup.TV)
    override val reportsHealth = true

    override suspend fun search(query: String): List<SearchResult> {
        // Matched by relevance rather than by substring. A substring has to be
        // contiguous, so "the witc" never matched "The Witcher", and neither did
        // "witcher season 2"; the shared matcher scores words instead, which is
        // what makes a keyword or half a name find the show.
        //
        // The recent feed is the same for every query, so it is fetched once and
        // matched locally rather than refetched per source per search.
        val shows = recentShows()
        val matched = shows
            .map { it to relevanceOf(it.title, query) }
            .filter { it.second > 0 }
            .sortedWith(
                // Relevance first, then recency: a query naming one show must not
                // be outranked by whatever else happened to come out today.
                compareByDescending<Pair<EztvShow, Int>> { it.second }
                    .thenByDescending { it.first.released }
            )
            .map { it.first }
            .take(MAX_SHOWS)
        if (matched.isEmpty()) return emptyList()

        val ids = matched.joinToString(",") { it.imdbId }.takeIf { it.isNotBlank() } ?: return emptyList()
        val json = fetchText("$API?limit=$PAGE_LIMIT&imdb_id=${URLEncoder.encode(ids, "UTF-8")}")
        return parseEztvFeed(json)
    }

    private suspend fun recentShows(): List<EztvShow> {
        if (cachedShows.isNotEmpty()) return cachedShows
        val json = fetchText("$API?limit=$PAGE_LIMIT")
        val shows = parseJson(json).array("torrents")
            .mapNotNull { row ->
                val imdb = row.string("imdb_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = row.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                EztvShow(imdb, title, row.number("date_released_unix"))
            }
            // One row per episode, so the same show appears once per episode; keep the
            // newest of each, which is what "recently released" means.
            .groupBy { it.imdbId }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.released } }
        cachedShows = shows
        return shows
    }

    private data class EztvShow(val imdbId: String, val title: String, val released: Long)

    private companion object {
        const val API = "https://eztvx.to/api/get-torrents"

        /** Above this the API quietly answers with thirty, whatever was asked for. */
        const val PAGE_LIMIT = 100

        /** Two shows: enough to be useful, few enough that the result list stays readable. */
        const val MAX_SHOWS = 2

        @Volatile
        var cachedShows: List<EztvShow> = emptyList()
    }
}

/**
 * YTS: movies, at three hostnames.
 *
 * `yts.mx` is the one everybody knows and it goes down often, so the mirrors are tried in
 * turn and the first to answer wins. A source that is down must say so rather than return
 * nothing - those are different messages and the user needs the second one.
 */
class YtsSearchSource : SearchSource {
    override val id = "yts"
    override val label = "YTS"
    override val groups = setOf(SearchGroup.MOVIES)
    override val reportsHealth = true

    override suspend fun search(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        var lastReason = "unreachable"
        for (host in HOSTS) {
            val body = runCatching {
                fetchText("https://$host/api/v2/list_movies.json?limit=50&query_term=$encoded")
            }.getOrElse { failure ->
                lastReason = failure.message ?: "unreachable"
                null
            } ?: continue
            return parseYtsFeed(body)
        }
        throw java.io.IOException("YTS unreachable: $lastReason")
    }

    private companion object {
        val HOSTS = listOf("yts.mx", "yts.am", "yts.rs")
    }
}

/** Nyaa: anime and anything else someone uploads there. */
class NyaaSearchSource : SearchSource {
    override val id = "nyaa"
    override val label = "Nyaa"
    override val groups = setOf(SearchGroup.ANIME)
    override val reportsHealth = true

    override suspend fun search(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseNyaaFeed(fetchText("$BASE?page=rss&q=$encoded&c=0_0&f=0"))
    }

    private companion object {
        const val BASE = "https://nyaa.si/"
    }
}

/** SubsPlease: current anime episodes, from its RSS feed. */
class SubsPleaseSearchSource : SearchSource {
    override val id = "subsplease"
    override val label = "SubsPlease"

    /** Its feed is the latest releases, not a search, so it answers every category. */
    override val groups = setOf(SearchGroup.ANIME)

    /** Its feed carries no swarm counts at all. */
    override val reportsHealth = false

    override suspend fun search(query: String): List<SearchResult> {
        val results = parseSubsPleaseFeed(fetchText(FEED))
        // It is a latest-releases feed, so the query has to be honoured here or every
        // search returns the same week. Nothing matching means nothing to offer.
        //
        // Matched by relevance rather than by substring, for the same reason EZTV
        // is: a substring has to be contiguous, so "blue lock" missed "Blue Lock S2"
        // only by luck and "blue lo" missed it always. This week is small enough
        // that scoring every row costs nothing.
        if (query.isBlank()) return results
        return rankByRelevance(results, query)
    }

    private companion object {
        const val FEED = "https://subsplease.quest/?rss=latest"
    }
}

/**
 * FitGirl: repackaged games, from the site's own WordPress feed.
 *
 * Games were the one category with no source behind it, so choosing Games asked nobody
 * anything and came back with nothing - which reads as a broken search rather than as a
 * category nobody serves. It is a WordPress feed rather than an API: `?s=<query>&feed=rss2`
 * is a real search, and each item carries a magnet in an `href`, so there is one request and
 * no markup to break.
 *
 * The feed carries no swarm counts and no sizes, so this reports no health and every row's
 * size is zero. That is stated rather than papered over: a size of "0 B" would look like a
 * bug and an invented one would be a lie.
 */
class FitGirlSearchSource : SearchSource {
    override val id = "fitgirl"
    override val label = "FitGirl"
    override val groups = setOf(SearchGroup.GAMES)

    /** Its feed has no seeders, no leechers and no sizes. */
    override val reportsHealth = false

    override suspend fun search(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseMagnetRssFeed(fetchText("$HOME/?s=$encoded&feed=rss2"), "fitgirl")
    }

    private companion object {
        const val HOME = "https://fitgirl-repacks.site"
    }
}

/**
 * A WordPress RSS feed whose items carry their magnet in an `href`.
 *
 * Different from the other RSS reader, which pulls named tags: this one only has a magnet
 * and a title to work with, because that is all the feed publishes.
 */
fun parseMagnetRssFeed(xml: String, source: String): List<SearchResult> {
    val out = ArrayList<SearchResult>()
    for (item in rssItems(xml)) {
        val magnet = MAGNET_IN_HREF.find(item)?.groupValues?.get(1)
            ?: continue
        val hash = magnetHashFrom(magnet) ?: continue
        val name = rssTag(item, "title").takeIf { it.isNotBlank() } ?: continue
        out += SearchResult(
            infoHash = hash,
            name = name,
            sizeBytes = 0L,
            seeders = 0,
            leechers = 0,
            source = source,
            magnet = magnetFor(hash, name),
            addedAtEpochMillis = parseRssDate(rssTag(item, "pubDate"))
        ).also { it.reportsHealth = false }
    }
    return out
}

private val MAGNET_IN_HREF = Regex("""href="(magnet:\?xt=urn:btih:[^"]+)""", RegexOption.IGNORE_CASE)

/**
 * The Pirate Bay, through its public API at apibay.org.
 *
 * This is the source for Movies and TV that came out of looking for a RARBG
 * replacement. RARBG is gone: its mirrors answer a browser and return `403` with
 * `cf-mitigated: challenge` to anything that is not one, and the hostnames that still
 * resolve are either Cloudflare-blocked or domain-squatter parking pages that serve
 * HTML with a 200. A torrent client cannot read any of them, and should not try to
 * get round a bot challenge that a site has deliberately put in place.
 *
 * apibay.org answers a plain HTTP request with JSON, which is the only requirement
 * that matters here, and it indexes a great deal more than RARBG did: a search for
 * "witcher" returns the 2021 film and the fourth series from the same query.
 *
 * It carries no swarm counts. Not "no counts on some rows" - the `seeds` and
 * `leechs` fields are empty on every row, checked across three unrelated queries.
 * So this reports no health, which is the mechanism for a source that cannot tell a
 * well-seeded torrent from a dead one: results from here sort after sources that know,
 * rather than pretending to be full of torrents nobody is seeding.
 *
 * Movies and TV are two category codes and they hold genuinely different things -
 * category 201 gave six rows for "witcher", all of them films, and 205 gave a hundred,
 * all of them episodes - so both are asked for and the answers merged. Asking only one
 * would mean a search for a series returning films, which is how a source ends up
 * looking broken rather than narrow.
 */
class PirateBaySearchSource : SearchSource {
    override val id = "thepiratebay"
    override val label = "ThePirateBay"
    override val groups = setOf(SearchGroup.MOVIES, SearchGroup.TV)

    /** It publishes no seeders and no leechers, so it cannot be ranked on health. */
    override val reportsHealth = false

    override suspend fun search(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val merged = LinkedHashMap<String, SearchResult>()
        var lastReason = "unreachable"
        // Movies first so that, where the same file somehow appears in both, the
        // category that answered is the one kept.
        for (category in CATEGORIES) {
            val body = runCatching {
                fetchText("$BASE/q.php?q=$encoded&cat=$category")
            }.getOrElse { failure ->
                lastReason = failure.message ?: "unreachable"
                null
            } ?: continue
            parseApibayFeed(body).forEach { result ->
                merged.putIfAbsent(result.infoHash, result)
            }
        }
        if (merged.isEmpty() && lastReason != "unreachable") {
            throw java.io.IOException("ThePirateBay unreachable: $lastReason")
        }
        return merged.values.toList()
    }

    private companion object {
        const val BASE = "https://apibay.org"

        /** 201 is Movies, 205 is TV in HD. */
        val CATEGORIES = listOf(201, 205)
    }
}
/** Every source this build ships with. */
fun defaultSearchSources(): List<SearchSource> = listOf(
    FitGirlSearchSource(),
    EztvSearchSource(),
    YtsSearchSource(),
        PirateBaySearchSource(),
    NyaaSearchSource(),
    SubsPleaseSearchSource()
)
