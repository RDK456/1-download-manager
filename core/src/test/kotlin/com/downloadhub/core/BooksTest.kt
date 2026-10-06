package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BooksTest {
    /** Gutenberg's OPDS feed, with the "Authors" navigation entry that is not a book. */
    @Test
    fun `gutenberg books come from the feed, with files and cover at fixed addresses`() {
        val xml = """<feed><entry><id>https://www.gutenberg.org/ebooks/authors/search.opds/?query=x</id><title>Authors</title></entry>
            <entry><id>https://www.gutenberg.org/ebooks/84.opds</id><title>Frankenstein; or, the modern prometheus</title>
            <content type="text">Mary Wollstonecraft Shelley</content></entry>
            <entry><id>https://www.gutenberg.org/ebooks/11659.opds</id><title>The World&#39;s Greatest Books</title>
            <content type="text">1083 downloads</content></entry></feed>"""
        val books = BookSources.parseGutenbergOpds(xml)
        assertEquals(2, books.size)
        val book = books.first()
        assertEquals("Mary Wollstonecraft Shelley", book.author)
        assertEquals("https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg", book.coverUrl)
        assertEquals("https://www.gutenberg.org/ebooks/84.epub3.images", book.files.first().url)
        assertEquals("a download count is not an author", "", books[1].author)
    }

    @Test
    fun `open library keeps only public scans, and points at the archive copy`() {
        val json = """{"docs":[
            {"title":"Dracula","author_name":["Bram Stoker"],"first_publish_year":1897,"cover_i":123,"ia":["dracula00stok"],"ebook_access":"public","key":"/works/OL1W"},
            {"title":"Lent","ia":["lent01"],"ebook_access":"borrowable"}]}"""
        val book = BookSources.parseOpenLibrary(json).single()
        assertEquals("dracula00stok", book.archiveId)
        assertEquals("1897", book.year)
        assertEquals("https://covers.openlibrary.org/b/id/123-M.jpg", book.coverUrl)
        assertTrue(book.files.isEmpty())
    }

    @Test
    fun `archive search reads a creator given as a list`() {
        val json = """{"response":{"docs":[{"identifier":"x1","title":"A Book","creator":["Someone","Other"],"year":"1900"}]}}"""
        val book = BookSources.parseArchiveSearch(json).single()
        assertEquals("Someone", book.author)
        assertEquals("https://archive.org/services/img/x1", book.coverUrl)
    }

    @Test
    fun `archive files are the book formats, best first, with names made safe for a url`() {
        val json = """{"files":[{"name":"scan_jp2.zip"},{"name":"My Book_djvu.txt"},{"name":"My Book.pdf"},{"name":"My Book_bw.pdf"},{"name":"My Book.epub"}]}"""
        val files = BookSources.parseArchiveFiles(json, "x1")
        assertEquals(listOf("EPUB", "PDF", "Text"), files.map { it.format })
        assertEquals("https://archive.org/download/x1/My%20Book.epub", files.first().url)
    }

    @Test
    fun `wikisource pages are offered as an epub and a pdf export`() {
        val json = """{"query":{"search":[{"title":"The Raven (Poe)"}]}}"""
        val book = BookSources.parseWikisource(json).single()
        assertEquals("https://ws-export.wmcloud.org/?lang=en&page=The+Raven+%28Poe%29&format=epub", book.files.first().url)
    }

    @Test
    fun `a book file is named after its title and author`() {
        val book = BookResult(BookSources.GUTENBERG, "Frankenstein", "Shelley, Mary")
        assertEquals("Frankenstein - Shelley, Mary.epub", BookSources.fileName(book, BookFile("EPUB", "u")))
    }

    @Test
    fun `standard ebooks entries carry their epub, kindle file and cover`() {
        val xml = """<feed><entry>
            <id>https://standardebooks.org/ebooks/charles-dickens/our-mutual-friend</id>
            <title>Our Mutual Friend</title>
            <author><name>Charles Dickens</name></author>
            <media:thumbnail url="https://standardebooks.org/c.jpg" height="525" width="350"/>
            <link href="https://standardebooks.org/d/omf.epub?source=feed" length="1" rel="enclosure" title="Recommended compatible epub" type="application/epub+zip" />
            <link href="https://standardebooks.org/d/omf_advanced.epub?source=feed" length="1" rel="enclosure" title="Advanced epub" type="application/epub+zip" />
            <link href="https://standardebooks.org/d/omf.azw3?source=feed" length="1" rel="enclosure" title="Amazon Kindle azw3" type="application/x-mobipocket-ebook" />
            </entry></feed>"""
        val book = BookSources.parseStandardEbooks(xml).single()
        assertEquals("Charles Dickens", book.author)
        assertEquals("https://standardebooks.org/c.jpg", book.coverUrl)
        assertEquals(listOf("EPUB" to "https://standardebooks.org/d/omf.epub", "AZW3" to "https://standardebooks.org/d/omf.azw3"), book.files.map { it.format to it.url })
        assertEquals("Our Mutual Friend - Charles Dickens.azw3", BookSources.fileName(book, book.files[1]))
    }

    @Test
    fun `films offer the largest copy of each format, albums a zip of the whole item`() {
        val films = """{"files":[{"name":"film_512kb.mp4","size":"100"},{"name":"film.mp4","size":"900"},{"name":"film.ogv","size":"50"},{"name":"film.jpg","size":"1"}]}"""
        assertEquals(
            listOf("MP4" to "https://archive.org/download/f1/film.mp4", "OGV" to "https://archive.org/download/f1/film.ogv"),
            BookSources.parseArchiveFiles(films, "f1", FreeCatalog.MOVIES).map { it.format to it.url }
        )
        val album = """{"files":[{"name":"t1.mp3"},{"name":"t2.mp3"},{"name":"t1.flac"}]}"""
        assertEquals(
            listOf("MP3 album" to "https://archive.org/compress/a1/formats=VBR%20MP3&file=/a1.zip", "FLAC album" to "https://archive.org/compress/a1/formats=Flac&file=/a1.zip"),
            BookSources.parseArchiveFiles(album, "a1", FreeCatalog.MUSIC).map { it.format to it.url }
        )
        val single = """{"files":[{"name":"song one.mp3"}]}"""
        assertEquals("https://archive.org/download/s1/song%20one.mp3", BookSources.parseArchiveFiles(single, "s1", FreeCatalog.MUSIC).single().url)
    }
}
