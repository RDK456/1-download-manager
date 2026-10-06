package com.downloadhub.core

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One downloadable edition of a book: its format ("EPUB", "PDF", "Kindle", "Text") and where it is. */
data class BookFile(val format: String, val url: String)

/**
 * A free book found by [BookSources].
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
    val pageUrl: String = ""
)

/**
 * Free, legal books: Project Gutenberg, Open Library's public scans, the Internet
 * Archive's openly downloadable texts and Wikisource. Nothing that is only lent, and no
 * shadow libraries - every file here is one its source offers to anyone.
 */
object BookSources {
    const val GUTENBERG = "Project Gutenberg"
    const val OPEN_LIBRARY = "Open Library"
    const val ARCHIVE = "Internet Archive"
    const val WIKISOURCE = "Wikisource"

    private fun enc(text: String) = URLEncoder.encode(text.trim(), "UTF-8")

    /** Every source at once; a source that fails or is slow costs only its own results. */
    suspend fun search(query: String): List<BookResult> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val q = enc(query)
        listOf(
            // Gutenberg's own catalogue feed: Gutendex answers the same question in ~30 s.
            async {
                runCatching { parseGutenbergOpds(fetchText("https://www.gutenberg.org/ebooks/search.opds/?query=$q")) }
                    .getOrDefault(emptyList())
            },
            async {
                runCatching {
                    parseOpenLibrary(fetchText("https://openlibrary.org/search.json?q=$q&ebook_access=public&has_fulltext=true&limit=30&fields=title,author_name,first_publish_year,cover_i,ia,ebook_access,key"))
                }.getOrDefault(emptyList())
            },
            async {
                runCatching {
                    val filter = enc("($query) AND mediatype:texts AND NOT collection:inlibrary AND NOT collection:printdisabled AND NOT access-restricted-item:true")
                    parseArchiveSearch(fetchText("https://archive.org/advancedsearch.php?q=$filter&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year&sort[]=downloads+desc&rows=30&output=json"))
                }.getOrDefault(emptyList())
            },
            async {
                runCatching {
                    parseWikisource(fetchText("https://en.wikisource.org/w/api.php?action=query&list=search&srsearch=$q&srnamespace=0&srlimit=20&format=json"))
                }.getOrDefault(emptyList())
            }
        ).awaitAll().flatten()
    }

    /** The files of a result, asking the Internet Archive for them when the search did not say. */
    suspend fun filesFor(result: BookResult): List<BookFile> {
        if (result.files.isNotEmpty()) return result.files
        val id = result.archiveId ?: return emptyList()
        return parseArchiveFiles(fetchText("https://archive.org/metadata/${enc(id)}"), id)
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

    fun parseArchiveSearch(json: String): List<BookResult> = parseJson(json).array("response", "docs").mapNotNull { doc ->
        val id = doc.string("identifier") ?: return@mapNotNull null
        val creator = doc.string("creator") ?: doc.array("creator").firstNotNullOfOrNull { (it as? JsonValue.Str)?.value }
        BookResult(
            source = ARCHIVE,
            title = doc.string("title").orEmpty(),
            author = creator.orEmpty(),
            year = doc.string("year") ?: doc.number("year").takeIf { it > 0 }?.toString().orEmpty(),
            coverUrl = "https://archive.org/services/img/$id",
            archiveId = id,
            pageUrl = "https://archive.org/details/$id"
        )
    }.filter { it.title.isNotBlank() }

    /** The book files an archive item holds, best formats first; scans' raw images are left out. */
    fun parseArchiveFiles(json: String, id: String): List<BookFile> {
        val files = parseJson(json).array("files").mapNotNull { file ->
            val name = file.string("name") ?: return@mapNotNull null
            val format = when {
                name.endsWith(".epub", true) -> "EPUB"
                name.endsWith(".pdf", true) && !name.endsWith("_bw.pdf", true) -> "PDF"
                name.endsWith(".mobi", true) -> "Kindle"
                name.endsWith("_djvu.txt", true) -> "Text"
                else -> return@mapNotNull null
            }
            val path = name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            BookFile(format, "https://archive.org/download/$id/$path")
        }
        val order = listOf("EPUB", "PDF", "Kindle", "Text")
        return files.distinctBy { it.format }.sortedBy { order.indexOf(it.format) }
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

    /** A file name for a book download: "Title - Author.epub". */
    fun fileName(book: BookResult, file: BookFile): String {
        val extension = when (file.format) {
            "EPUB" -> "epub"
            "PDF" -> "pdf"
            "Kindle" -> "mobi"
            else -> "txt"
        }
        val base = listOf(book.title, book.author).filter { it.isNotBlank() }.joinToString(" - ").take(150)
        return LinkParser.sanitizeFileName("$base.$extension")
    }
}
