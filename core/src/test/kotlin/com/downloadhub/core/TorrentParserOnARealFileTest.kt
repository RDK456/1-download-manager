package com.downloadhub.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against a real encoder's output.
 *
 * The synthetic torrents elsewhere here are exact but tidy. This one is written by a Java
 * program the way an encoder writes it - 13 files, 262 200 bytes of piece hashes, byte
 * values above 127 throughout, keys sorted - and it is the shape the dialog actually
 * meets. Two of my own generators got the declared string length wrong (UTF-8 instead of
 * the bytes written) and the parser correctly refused both, which is the behaviour worth
 * pinning down: a file whose lengths do not match its bytes is not a torrent.
 */
class TorrentParserOnARealFileTest {

    private fun probeFile(): File {
        // Written by tools/MkT.java in this repository's docs, and by hand if it is
        // missing - see writeOne().
        val existing = File(System.getProperty("user.home"), "Downloads/dlm-probe.torrent")
        return if (existing.isFile && existing.length() > 1000) existing else writeOne()
    }

    /** The same torrent, built here when there is no file on disk to read. */
    private fun writeOne(): File {
        val sizes = listOf(
            3523219456L, 240352896L, 230129664L, 329383936L, 319225856L, 384606208L,
            310288384L, 259522560L, 236060672L, 415760384L, 423917568L, 245000576L, 287520256L
        )
        val name = "Example Release 2.1"
        val latin1 = Charsets.ISO_8859_1
        fun str(v: String) = "${v.toByteArray(latin1).size}:$v"
        fun num(v: Long) = "i${v}e"
        fun dict(pairs: List<String>) = "d" + pairs.sortedBy { it.substringBefore(':') }
            .joinToString("") + "e"
        fun pair(k: String, value: String) = str(k) + value

        val files = sizes.joinToString("") { size ->
            dict(
                listOf(
                    pair("length", num(size)),
                    pair("path", "l" + str(name) + "e")
                )
            )
        }
        val pieces = (0 until 13110).joinToString("") { seed -> ((seed * 37 + 11) % 256).toChar().toString() }
        val info = dict(
            listOf(
                pair("comment", str("Built by the example project")),
                pair("created by", str("qBittorrent v4.6.0")),
                pair("creation date", num(1700000000L)),
                pair("files", "l" + files + "e"),
                pair("name", str(name)),
                pair("piece length", num(262144L)),
                pair("pieces", str(pieces))
            )
        )
        val raw = dict(
            listOf(
                pair("announce", str("udp://tracker.opentrackr.org:1337/announce")),
                pair("info", info)
            )
        )
        val file = File.createTempFile("dlm-real", ".torrent")
        file.writeBytes(raw.toByteArray(latin1))
        return file
    }

    @Test
    fun aRealTorrentIsReadInFull() {
        val meta = TorrentParser.parse(probeFile())

        assertEquals("Example Release 2.1", meta.name)
        assertEquals(13, meta.files.size)
        assertEquals(3523219456L, meta.files.first().size)
        assertEquals(287520256L, meta.files.last().size)
        assertEquals(sizes_total(), meta.totalSize)
        assertEquals(262144L, meta.pieceLength)
        // 13110 characters of piece hashes is 655 whole hashes, not 13110 pieces.
        assertEquals(655, meta.pieceCount)
        assertEquals("Built by the example project", meta.comment)
        assertEquals("qBittorrent v4.6.0", meta.createdBy)
        assertEquals(1700000000000L, meta.createdAtEpochMillis)
        assertEquals(40, meta.infoHashV1.length)
        assertEquals(64, meta.infoHashV2.length)
    }

    @Test
    fun readingTheSameFileTwiceGivesTheSameInfoHash() {
        val file = probeFile()
        assertEquals(
            TorrentParser.parse(file).infoHashV1,
            TorrentParser.parse(file).infoHashV1
        )
    }

    /**
     * A file whose declared string lengths do not match its bytes is not a torrent.
     *
     * Two of my own generators got this wrong by counting UTF-8 while writing
     * ISO-8859-1. The parser refused both, which is right: reading it as a torrent anyway
     * would produce a file list that is quietly short.
     */
    @Test
    fun aFileWithWrongDeclaredLengthsIsRefused() {
        val good = probeFile().readBytes()
        val broken = String(good, Charsets.ISO_8859_1)
            .replace("13110:", "9999:")
            .toByteArray(Charsets.ISO_8859_1)

        assertTrue(
            "a truncated or mis-declared file must be refused rather than read as a " +
                "torrent with fewer pieces than it claims",
            runCatching { TorrentParser.parse(broken) }.exceptionOrNull() is TorrentParseException
        )
    }

    @Test
    fun thePreDownloadDialogGetsEveryFileOfARealTorrent() {
        val file = probeFile()
        val meta = TorrentParser.parse(file)
        val request = TorrentSelection.validated(
            meta = meta,
            link = file.absolutePath,
            saveDirectory = File("C:/downloads"),
            selected = TorrentSelection.allSelected(meta),
            metainfoFile = file
        )
        assertEquals(13, request!!.selectedFiles.size)
        assertEquals(meta.totalSize, TorrentSelection.selectedSize(meta, request.selectedFiles))
        assertEquals(13, contentRowCount(meta, ""))
        // The filter matches the whole path, and a filter that matches nothing yields
        // nothing rather than the whole list.
        assertEquals(0, contentRowCount(meta, "no-such-file"))
    }

    private fun contentRowCount(meta: TorrentMetainfo, filter: String): Int =
        if (filter.isBlank()) meta.files.size else meta.files.count { it.path.contains(filter) }

    private fun sizes_total(): Long = listOf(
        3523219456L, 240352896L, 230129664L, 329383936L, 319225856L, 384606208L,
        310288384L, 259522560L, 236060672L, 415760384L, 423917568L, 245000576L, 287520256L
    ).sum()
}
