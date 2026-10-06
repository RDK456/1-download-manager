package com.downloadhub.core

import java.net.URLEncoder

/** What to browse on the Internet Archive, as the archive's own media types. */
enum class ArchiveType(val label: String, val mediatypes: String) {
    ALL("All", "movies OR audio OR etree OR texts OR software OR image OR data"),
    VIDEO("Video", "movies"),
    AUDIO("Audio", "audio OR etree"),
    TEXTS("Books & texts", "texts"),
    SOFTWARE("Software & apps", "software"),
    IMAGES("Images", "image"),
    DATA("Data", "data")
}

/** One item on archive.org: a film, an album, a book, a program, a data set. */
data class ArchiveItem(
    val id: String,
    val title: String,
    val creator: String = "",
    val mediatype: String = "",
    val sizeBytes: Long = 0L,
    val downloads: Long = 0L
) {
    val pageUrl: String get() = "${ArchiveOrg.BASE}/details/$id"
    val thumbnailUrl: String get() = "${ArchiveOrg.BASE}/services/img/$id"

    /** The archive's media type in words ("movies" is Video, "etree" a concert recording). */
    val typeLabel: String get() = when (mediatype) {
        "movies" -> "Video"
        "audio", "etree" -> "Audio"
        "texts" -> "Book"
        "software" -> "Software"
        "image" -> "Image"
        "data" -> "Data"
        else -> mediatype.replaceFirstChar { it.uppercase() }
    }
}

/** One file an item holds, as uploaded. */
data class ArchiveFile(val name: String, val url: String, val sizeBytes: Long, val format: String)

/**
 * The whole Internet Archive, any media type: search or browse the most downloaded, then
 * list an item's files to queue one or all of them. Lending-library and otherwise
 * restricted items are left out, since their files cannot be downloaded.
 */
object ArchiveOrg {
    const val BASE = "https://archive.org"
    const val PAGE_SIZE = 50

    /** The search address; a blank [query] browses [type]'s most downloaded items. */
    fun searchUrl(query: String, type: ArchiveType, page: Int = 1): String {
        val q = listOfNotNull(
            query.trim().takeIf { it.isNotEmpty() }?.let { "($it)" },
            "mediatype:(${type.mediatypes})",
            "NOT access-restricted-item:true"
        ).joinToString(" AND ")
        return "$BASE/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}" +
            listOf("identifier", "title", "creator", "mediatype", "item_size", "downloads").joinToString("") { "&fl[]=$it" } +
            "&sort[]=downloads+desc&rows=$PAGE_SIZE&page=${page.coerceAtLeast(1)}&output=json"
    }

    suspend fun search(query: String, type: ArchiveType, page: Int = 1): List<ArchiveItem> =
        parseSearch(fetchText(searchUrl(query, type, page)))

    suspend fun files(id: String): List<ArchiveFile> =
        parseFiles(fetchText("$BASE/metadata/${URLEncoder.encode(id, "UTF-8")}"), id)

    fun parseSearch(json: String): List<ArchiveItem> = parseJson(json).array("response", "docs").mapNotNull { doc ->
        val id = doc.string("identifier") ?: return@mapNotNull null
        ArchiveItem(
            id = id,
            title = doc.string("title") ?: id,
            creator = doc.string("creator") ?: doc.array("creator").firstNotNullOfOrNull { (it as? JsonValue.Str)?.value }.orEmpty(),
            mediatype = doc.string("mediatype").orEmpty(),
            sizeBytes = doc.number("item_size"),
            downloads = doc.number("downloads")
        )
    }

    /**
     * The files as uploaded. The archive's own bookkeeping (metadata XML, the item's
     * torrent, thumbnails) and the copies it derives from the originals are left out, so
     * "download all" fetches each thing once. Private files cannot be downloaded at all.
     */
    fun parseFiles(json: String, id: String): List<ArchiveFile> = parseJson(json).array("files").mapNotNull { file ->
        val name = file.string("name") ?: return@mapNotNull null
        if (file.string("source") != "original" || file.string("private") == "true") return@mapNotNull null
        if (BOOKKEEPING.any { name.endsWith(it, ignoreCase = true) }) return@mapNotNull null
        ArchiveFile(name, archiveDownloadUrl(id, name), file.number("size"), file.string("format").orEmpty())
    }

    /** "Download all" asks first above this many files or bytes, so a huge item is never queued by a stray click. */
    const val CONFIRM_FILES = 20
    const val CONFIRM_BYTES = 2L * 1024 * 1024 * 1024

    fun needsConfirmation(files: List<ArchiveFile>): Boolean =
        files.size > CONFIRM_FILES || files.sumOf { it.sizeBytes } > CONFIRM_BYTES

    /**
     * A file name per file, in order, none the same. A file is saved under its own name
     * unless another file in the item shares it (`disc1/cover.jpg`, `disc2/cover.jpg`); then
     * its folders go into the name (`disc1 - cover.jpg`), so both arrive and each says where
     * it came from. Compared ignoring case, because Windows file names do.
     */
    fun saveNames(files: List<ArchiveFile>): List<String> {
        val base = files.map { it.name.substringAfterLast('/') }
        val clashing = base.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys
        val seen = mutableMapOf<String, Int>()
        return files.mapIndexed { i, file ->
            val wanted = if (base[i].lowercase() in clashing) file.name.replace("/", " - ") else base[i]
            val name = LinkParser.sanitizeFileName(wanted)
            // Flattening can still meet ("a/b - c" and "a - b/c"): number the later ones.
            when (val count = seen.merge(name.lowercase(), 1, Int::plus)!!) {
                1 -> name
                else -> if ('.' in name) "${name.substringBeforeLast('.')} ($count).${name.substringAfterLast('.')}" else "$name ($count)"
            }
        }
    }

    private val BOOKKEEPING = listOf("_meta.xml", "_files.xml", "_meta.sqlite", "_reviews.xml", "__ia_thumb.jpg", "_archive.torrent")
}

/** Where archive.org serves [name] from item [id], each path segment encoded. */
internal fun archiveDownloadUrl(id: String, name: String): String =
    "${ArchiveOrg.BASE}/download/$id/" + name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
