package com.downloadhub.core

import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One downloadable edition of a book: its format ("EPUB", "PDF", "Kindle", "Text") and where it is. */
data class BookFile(val format: String, val url: String)

/**
 * What a free search looks for: books, films or music. Films and music come only from the
 * Internet Archive collections of public-domain or freely licensed work.
 */
enum class FreeCatalog(val label: String, val sources: List<String>, val blurb: String, val hint: String) {
    BOOKS(
        "free books",
        listOf(BookSources.GUTENBERG, BookSources.STANDARD_EBOOKS, BookSources.OPEN_LIBRARY, BookSources.ARCHIVE, BookSources.WIKISOURCE),
        "Classics and public-domain books, free to download: Project Gutenberg, Standard Ebooks, Open Library, the Internet Archive and Wikisource.",
        "Title or author"
    ),
    MOVIES(
        "free movies",
        listOf(BookSources.FEATURE_FILMS, BookSources.SILENT_FILMS, BookSources.CARTOONS, BookSources.CLASSIC_TV, BookSources.PRELINGER),
        "Public-domain feature films, silent films, cartoons and classic TV from the Internet Archive.",
        "Title, actor or director"
    ),
    MUSIC(
        "free music",
        listOf(BookSources.LIVE_CONCERTS, BookSources.NETLABELS, BookSources.AUDIOBOOKS),
        "Live concerts the bands allow to be shared, Creative Commons netlabel releases and LibriVox audiobooks.",
        "Artist, album or book"
    )
}

/**
 * A free book, film or album found by [BookSources].
 *
 * [files] is empty for an Internet Archive item until [BookSources.filesFor] asks the
 * archive what it holds - the search does not say, and asking for every result up front
 * would be one request per result.
 */
data class BookResult(
    val source: String,
    val title: String,
    val author: String,
    val year: String = "",
    val coverUrl: String? = null,
    val files: List<BookFile> = emptyList(),
    val archiveId: String? = null,
    val pageUrl: String = "",
    val catalog: FreeCatalog = FreeCatalog.BOOKS
)

/**
 * Free, legal books, films and music: Project Gutenberg, Standard Ebooks, Open Library's
 * public scans, Wikisource, and the Internet Archive's public-domain and freely licensed
 * collections. Nothing that is only lent, and no shadow libraries - every file here is one
 * its source offers to anyone.
 */
object BookSources {
    const val GUTENBERG = "Project Gutenberg"
    const val OPEN_LIBRARY = "Open Library"
    const val ARCHIVE = "Internet Archive"
    const val WIKISOURCE = "Wikisource"
    const val STANDARD_EBOOKS = "Standard Ebooks"
    const val FEATURE_FILMS = "Feature films"
    const val SILENT_FILMS = "Silent films"
    const val CARTOONS = "Cartoons"
    const val CLASSIC_TV = "Classic TV"
    const val PRELINGER = "Prelinger Archives"
    const val LIVE_CONCERTS = "Live concerts"
    const val NETLABELS = "Netlabels"
    const val AUDIOBOOKS = "LibriVox audiobooks"

    private fun enc(text: String) = URLEncoder.encode(text.trim(), "UTF-8")

    /** Every source of [catalog] at once; a source that fails or is slow costs only its own results. */
    suspend fun search(query: String, catalog: FreeCatalog = FreeCatalog.BOOKS): List<BookResult> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val q = enc(query)
        val sources: List<suspend () -> List<BookResult>> = when (catalog) {
            FreeCatalog.BOOKS -> listOf(
                // Gutenberg's own catalogue feed: Gutendex answers the same question in ~30 s.
                { parseGutenbergOpds(fetchText("https://www.gutenberg.org/ebooks/search.opds/?query=$q")) },
                { parseStandardEbooks(fetchText("https://standardebooks.org/feeds/atom/all?query=$q&per-page=24")) },
                {
                    parseOpenLibrary(fetchText("https://openlibrary.org/search.json?q=$q&ebook_access=public&has_fulltext=true&limit=30&fields=title,author_name,first_publish_year,cover_i,ia,ebook_access,key"))
                },
                { archiveCollections.getValue(FreeCatalog.BOOKS).single().let { (filter, source) -> archive(query, filter, source, catalog) } },
                { parseWikisource(fetchText("https://en.wikisource.org/w/api.php?action=query&list=search&srsearch=$q&srnamespace=0&srlimit=20&format=json")) }
            )
            else -> archiveCollections.getValue(catalog).map { (filter, source) -> { archive(query, filter, source, catalog) } }
        }
        sources.map { source -> async { runCatching { source() }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    }

    /** The Internet Archive collections each catalog draws on, as (search filter, source name). */
    private val archiveCollections = mapOf(
        FreeCatalog.MOVIES to listOf(
            "mediatype:movies AND (collection:feature_films OR collection:film_noir OR collection:sci-fi_horror OR collection:comedy_films)" to FEATURE_FILMS,
            "mediatype:movies AND collection:silent_films" to SILENT_FILMS,
            "mediatype:movies AND collection:animationandcartoons" to CARTOONS,
            "mediatype:movies AND collection:classic_tv" to CLASSIC_TV,
            "mediatype:movies AND collection:prelinger" to PRELINGER
        ),
        FreeCatalog.MUSIC to listOf(
            "mediatype:etree" to LIVE_CONCERTS,
            "mediatype:audio AND collection:netlabels" to NETLABELS,
            "mediatype:audio AND collection:librivoxaudio" to AUDIOBOOKS
        ),
        FreeCatalog.BOOKS to listOf(
            "mediatype:texts AND NOT collection:inlibrary AND NOT collection:printdisabled AND NOT access-restricted-item:true" to ARCHIVE
        )
    )

    /** How deep into each source's most-downloaded list a recommendation may reach, in pages. */
    private const val RECOMMEND_DEPTH = 40

    /**
     * Something worth opening before anything is typed: a random page from deep in each
     * source's most-downloaded list, shuffled together. Every call draws new pages, so
     * calling it again - opening the screen, or scrolling to the end - shows different
     * things rather than the same top ten every time.
     */
    suspend fun recommended(catalog: FreeCatalog, random: Random = Random.Default): List<BookResult> = coroutineScope {
        fun page() = 1 + random.nextInt(RECOMMEND_DEPTH)
        val fromArchive = archiveCollections.getValue(catalog).map { (filter, source) ->
            async { runCatching { archive("", filter, source, catalog, rows = 12, page = page()) }.getOrDefault(emptyList()) }
        }
        val fromGutenberg = async {
            if (catalog != FreeCatalog.BOOKS) emptyList() else runCatching {
                parseGutenbergOpds(fetchText("https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads&start_index=${1 + 25 * (page() - 1)}"))
            }.getOrDefault(emptyList())
        }
        (fromArchive + fromGutenberg).awaitAll().flatten().shuffled(random)
    }

    /** One Internet Archive collection search, most downloaded first; a blank [query] browses the collection. */
    private suspend fun archive(query: String, filter: String, source: String, catalog: FreeCatalog, rows: Int = 30, page: Int = 1): List<BookResult> {
        val q = enc(if (query.isBlank()) filter else "($query) AND $filter")
        return parseArchiveSearch(
            fetchText("https://archive.org/advancedsearch.php?q=$q&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year&sort[]=downloads+desc&rows=$rows&page=$page&output=json"),
            source,
            catalog
        )
    }

    /** The files of a result, asking the Internet Archive for them when the search did not say. */
    suspend fun filesFor(result: BookResult): List<BookFile> {
        if (result.files.isNotEmpty()) return result.files
        val id = result.archiveId ?: return emptyList()
        return parseArchiveFiles(fetchText("https://archive.org/metadata/${enc(id)}"), id, result.catalog)
    }

    /**
     * Gutenberg's OPDS search feed. Each book entry has its number in its id; its files and
     * cover sit at fixed addresses built from that number, so no second request is needed.
     * The entry's text is the author, or a download count when the book has none.
     */
    fun parseGutenbergOpds(xml: String): List<BookResult> =
        Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { match ->
            val entry = match.groupValues[1]
            val number = Regex("""/ebooks/(\d+)\.opds""").find(entry)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)
                ?.let(::unescapeXmlEntities)?.trim().orEmpty()
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)
                ?.let(::unescapeXmlEntities)?.trim().orEmpty()
            val base = "https://www.gutenberg.org/ebooks/$number"
            BookResult(
                source = GUTENBERG,
                title = title,
                author = content.takeUnless { it.endsWith("downloads") }.orEmpty(),
                coverUrl = "https://www.gutenberg.org/cache/epub/$number/pg$number.cover.medium.jpg",
                files = listOf(
                    BookFile("EPUB", "$base.epub3.images"),
                    BookFile("Kindle", "$base.kf8.images"),
                    BookFile("Text", "$base.txt.utf-8")
                ),
                pageUrl = base
            )
        }.filter { it.title.isNotBlank() }.toList()

    /**
     * Standard Ebooks' Atom search feed: each entry carries its cover and its files as
     * enclosures, so no second request is needed.
     */
    fun parseStandardEbooks(xml: String): List<BookResult> =
        Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { match ->
            val entry = match.groupValues[1]
            fun first(pattern: String) = Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1)
                ?.let(::unescapeXmlEntities)?.trim()
            val page = first("<id>(.*?)</id>") ?: return@mapNotNull null
            val enclosures = Regex("""<link href="([^"]+)"[^>]*rel="enclosure"[^>]*title="([^"]+)"""").findAll(entry)
                .map { unescapeXmlEntities(it.groupValues[1]).substringBefore("?source=") to it.groupValues[2] }.toList()
            BookResult(
                source = STANDARD_EBOOKS,
                title = first("<title>(.*?)</title>").orEmpty(),
                author = first("""<author>\s*<name>(.*?)</name>""").orEmpty(),
                coverUrl = first("""<media:thumbnail url="([^"]+)""""),
                files = listOfNotNull(
                    enclosures.firstOrNull { it.second.startsWith("Recommended") }?.let { BookFile("EPUB", it.first) },
                    enclosures.firstOrNull { it.first.endsWith(".azw3") }?.let { BookFile("AZW3", it.first) }
                ),
                pageUrl = page
            )
        }.filter { it.title.isNotBlank() && it.files.isNotEmpty() }.toList()

    fun parseOpenLibrary(json: String): List<BookResult> = parseJson(json).array("docs").mapNotNull { doc ->
        if (doc.string("ebook_access") != "public") return@mapNotNull null
        val ia = doc.array("ia").firstNotNullOfOrNull { (it as? JsonValue.Str)?.value } ?: return@mapNotNull null
        val cover = doc.number("cover_i").takeIf { it > 0 }
        BookResult(
            source = OPEN_LIBRARY,
            title = doc.string("title").orEmpty(),
            author = doc.array("author_name").firstNotNullOfOrNull { (it as? JsonValue.Str)?.value }.orEmpty(),
            year = doc.number("first_publish_year").takeIf { it > 0 }?.toString().orEmpty(),
            coverUrl = cover?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
            archiveId = ia,
            pageUrl = "https://openlibrary.org${doc.string("key").orEmpty()}"
        )
    }.filter { it.title.isNotBlank() }

    fun parseArchiveSearch(json: String, source: String = ARCHIVE, catalog: FreeCatalog = FreeCatalog.BOOKS): List<BookResult> =
        parseJson(json).array("response", "docs").mapNotNull { doc ->
            val id = doc.string("identifier") ?: return@mapNotNull null
            val creator = doc.string("creator") ?: doc.array("creator").firstNotNullOfOrNull { (it as? JsonValue.Str)?.value }
            BookResult(
                source = source,
                title = doc.string("title").orEmpty(),
                author = creator.orEmpty(),
                year = doc.string("year") ?: doc.number("year").takeIf { it > 0 }?.toString().orEmpty(),
                coverUrl = "https://archive.org/services/img/$id",
                archiveId = id,
                pageUrl = "https://archive.org/details/$id",
                catalog = catalog
            )
        }.filter { it.title.isNotBlank() }

    /**
     * The files an archive item holds worth offering, best first. Books: one file per
     * format, scans' raw images left out. Films: the largest copy of each video format.
     * Music: the one track, or the whole item zipped by the archive as MP3 or FLAC.
     */
    fun parseArchiveFiles(json: String, id: String, catalog: FreeCatalog = FreeCatalog.BOOKS): List<BookFile> {
        val entries = parseJson(json).array("files").mapNotNull { file ->
            val name = file.string("name") ?: return@mapNotNull null
            name to (file.string("size")?.toLongOrNull() ?: 0L)
        }
        fun url(name: String) = archiveDownloadUrl(id, name)
        return when (catalog) {
            FreeCatalog.BOOKS -> {
                val order = listOf("EPUB", "PDF", "Kindle", "Text")
                entries.mapNotNull { (name) ->
                    val format = when {
                        name.endsWith(".epub", true) -> "EPUB"
                        name.endsWith(".pdf", true) && !name.endsWith("_bw.pdf", true) -> "PDF"
                        name.endsWith(".mobi", true) -> "Kindle"
                        name.endsWith("_djvu.txt", true) -> "Text"
                        else -> return@mapNotNull null
                    }
                    BookFile(format, url(name))
                }.distinctBy { it.format }.sortedBy { order.indexOf(it.format) }
            }
            FreeCatalog.MOVIES -> {
                val order = listOf("MP4", "MKV", "OGV", "AVI")
                entries.filter { (name) -> name.substringAfterLast('.', "").uppercase() in order }
                    .sortedByDescending { it.second }
                    .distinctBy { it.first.substringAfterLast('.').uppercase() }
                    .map { (name) -> BookFile(name.substringAfterLast('.').uppercase(), url(name)) }
                    .sortedBy { order.indexOf(it.format) }
            }
            FreeCatalog.MUSIC -> {
                val mp3 = entries.filter { it.first.endsWith(".mp3", true) }
                val flac = entries.any { it.first.endsWith(".flac", true) }
                fun zip(format: String) = "https://archive.org/compress/$id/formats=${format.replace(" ", "%20")}&file=/$id.zip"
                when {
                    mp3.size == 1 -> listOf(BookFile("MP3", url(mp3.single().first)))
                    mp3.isNotEmpty() -> listOfNotNull(BookFile("MP3 album", zip("VBR MP3")), BookFile("FLAC album", zip("Flac")).takeIf { flac })
                    flac -> listOf(BookFile("FLAC album", zip("Flac")))
                    else -> emptyList()
                }
            }
        }
    }

    fun parseWikisource(json: String): List<BookResult> = parseJson(json).array("query", "search").mapNotNull { hit ->
        val title = hit.string("title") ?: return@mapNotNull null
        val page = enc(title)
        BookResult(
            source = WIKISOURCE,
            title = title,
            author = "",
            files = listOf(
                BookFile("EPUB", "https://ws-export.wmcloud.org/?lang=en&page=$page&format=epub"),
                BookFile("PDF", "https://ws-export.wmcloud.org/?lang=en&page=$page&format=pdf")
            ),
            pageUrl = "https://en.wikisource.org/wiki/${title.replace(' ', '_')}"
        )
    }

    /** A file name for a download: "Title - Author.epub", or ".zip" for a whole album. */
    fun fileName(book: BookResult, file: BookFile): String {
        val extension = when (file.format) {
            "EPUB" -> "epub"
            "PDF" -> "pdf"
            "Kindle" -> "mobi"
            "Text" -> "txt"
            "MP3 album", "FLAC album" -> "zip"
            else -> file.format.lowercase()
        }
        val base = listOf(book.title, book.author).filter { it.isNotBlank() }.joinToString(" - ").take(150)
        return LinkParser.sanitizeFileName("$base.$extension")
    }
}
