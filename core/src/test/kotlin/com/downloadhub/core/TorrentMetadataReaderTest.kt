package com.downloadhub.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a magnet's file list.
 *
 * The point of the feature is that a magnet is not a download with no file list. It is a
 * download whose file list has not been fetched yet, and it is a few kilobytes from the
 * swarm. Before this, the pre-download dialog told the user a magnet had nothing to
 * choose - which was true of the dialog and false of the torrent, and made a torrent the
 * one kind of download where you could not see what you were about to get or take three
 * files out of forty.
 *
 * The waiting itself cannot be tested here: it needs peers, and there is no swarm in a
 * unit test. What is tested is everything either side of it - that the reader refuses
 * what is not a magnet, and that libtorrent's reading of a torrent is turned into exactly
 * the same shape the file parser produces, so the dialog cannot tell which it got.
 */
/** The piece size every torrent in here is cut into. */
private const val PIECE_LENGTH = 262144L

/** A piece hash is a SHA-1: twenty bytes each. */
private const val HASH_BYTES = 20

class TorrentMetadataReaderTest {

    /**
     * Sizes that are exact multiples of the piece length.
     *
     * In a multi-file torrent every file except the last is padded up to a piece
     * boundary, so the piece count is derived from the padded total rather than the sum
     * of the file sizes. libtorrent works it out that way and refuses a torrent whose
     * hashes do not match ("incorrect number of piece hashes") - where the file parser
     * is content to read the file list out of it regardless. Using multiples of the piece
     * length makes the two agree without either having to pad.
     */
    private val sizes = listOf(
        PIECE_LENGTH * 13440L,
        PIECE_LENGTH * 918L,
        PIECE_LENGTH * 878L,
        PIECE_LENGTH * 1254L
    )

    private val magnet =
        "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=Example+Release"

    /**
     * The same torrent the file parser is tested against, so the two readings can be
     * compared directly. Written the way an encoder writes it: ISO-8859-1 lengths, keys
     * sorted, real piece hashes.
     */
    private fun torrentBytes(): ByteArray {
        val latin1 = Charsets.ISO_8859_1
        fun str(v: String) = "${v.toByteArray(latin1).size}:$v"
        fun num(v: Long) = "i${v}e"
        fun dict(pairs: List<String>) =
            "d" + pairs.sortedBy { it.substringBefore(':') }.joinToString("") + "e"
        fun pair(k: String, value: String) = str(k) + value

        val files = sizes.joinToString("") { size ->
            dict(
                listOf(
                    pair("length", num(size)),
                    pair("path", "l" + str("Example Release") + str("part-$size.mkv") + "e")
                )
            )
        }
        // The piece hashes have to cover the data. libtorrent checks this and refuses
        // the file outright - "incorrect number of piece hashes" - where the file parser
        // is content to read the file list out of it regardless. So the count is derived
        // from the sizes rather than invented, or this is a test of a file that is not
        // really a torrent.
        val total = sizes.sum()
        val pieceCount = ((total + PIECE_LENGTH - 1) / PIECE_LENGTH).toInt()
        // Twenty bytes per piece, not one: a piece hash is a SHA-1, and libtorrent
        // counts the bytes rather than the pieces. One per piece is a torrent that
        // looks fine to the file parser - which only reads the file list out of it -
        // and is refused outright by libtorrent as "incorrect number of piece hashes".
        val pieces = (0 until (pieceCount * HASH_BYTES)).joinToString("") { seed ->
            ((seed * 37 + 11) % 256).toChar().toString()
        }
        val info = dict(
            listOf(
                pair("created by", str("qBittorrent v4.6.0")),
                pair("files", "l" + files + "e"),
                pair("name", str("Example Release")),
                pair("piece length", num(PIECE_LENGTH)),
                pair("pieces", str(pieces))
            )
        )
        return dict(listOf(pair("info", info))).toByteArray(latin1)
    }

    @Test
    fun somethingThatIsNotAMagnetIsRefusedWithoutTouchingTheNetwork() {
        val failure = TorrentMetadataReader.read("https://example.com/file.zip")
        assertTrue(
            "an http link needs no metadata lookup, and going to the network for one " +
                "would be a slow way to learn that",
            failure.isFailure
        )
        assertTrue(
            "and it must say so rather than fail obscurely: ${failure.exceptionOrNull()}",
            failure.exceptionOrNull()?.message.orEmpty().contains("not a magnet")
        )
    }

    @Test
    fun aMagnetWithNoInfoHashIsRefused() {
        val failure = TorrentMetadataReader.read("magnet:?dn=nothing-useful")
        assertTrue(
            "a magnet with no xt= is not a magnet, and there is nothing to look up",
            failure.isFailure
        )
    }

    @Test
    fun aMagnetWithNobodyOnItFailsRatherThanHanging() {
        // No swarm, so this must give up. Not run for its full twenty seconds here: the
        // point is that a short timeout is honoured, and the default is tested by reading
        // the constant.
        val result = TorrentMetadataReader.read(magnet, timeoutMillis = 900L)
        assertTrue(
            "with no peers this cannot succeed, and must say so instead of blocking the " +
                "caller",
            result.isFailure
        )
        assertNotNull(result.exceptionOrNull()?.message)
    }

    @Test
    fun theTimeoutIsLongEnoughToBeWorthWaitingAndShortEnoughToBeWaitedFor() {
        assertTrue(
            "a tracker-less magnet can need most of a minute to reach a peer through DHT; " +
                "the dialog stays usable meanwhile, so the wait can be that long but no longer",
            TorrentMetadataReader.DEFAULT_TIMEOUT_MILLIS in 30_000L..90_000L
        )
    }

    /**
     * libtorrent's reading becomes the same shape the file parser produces.
     *
     * This is the part that makes the feature work rather than merely appear to: the file
     * list, the folder name, the totals and the information block all read a
     * [TorrentMetainfo], so if the two sources disagreed in shape the dialog would render
     * a magnet's list differently from a `.torrent`'s - the same torrent, two layouts.
     */
    @Test
    fun aTorrentReadByLibtorrentIsTheSameShapeTheParserProduces() {
        val file = File.createTempFile("dlm-meta", ".torrent")
        try {
            file.writeBytes(torrentBytes())
            val fromLibtorrent = TorrentMetadataReader.readFile(file)
            assertTrue("libtorrent must accept the torrent: ${fromLibtorrent.exceptionOrNull()}", fromLibtorrent.isSuccess)

            val fromParser = TorrentParser.parse(file)
            val meta = fromLibtorrent.getOrThrow()

            assertEquals("the same name", fromParser.name, meta.name)
            assertEquals("the same number of files", fromParser.files.size, meta.files.size)
            assertEquals("the same total size", fromParser.totalSize, meta.totalSize)
            assertEquals("the same piece size", fromParser.pieceLength, meta.pieceLength)
            assertEquals("the same per-file sizes", fromParser.files.map { it.size }, meta.files.map { it.size })
            assertEquals("and the same info hash", fromParser.infoHashV1, meta.infoHashV1)
            assertTrue(
                "neither is a single file: ${meta.isSingleFile}",
                fromParser.isSingleFile == meta.isSingleFile
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun aFetchedListIsUsableByTheSameRulesAsAParsedOne() {
        // The point of the comparison above, stated as behaviour: a magnet's selection
        // goes through the same validation, so an unticked file really is left out and a
        // full selection really is everything.
        val file = File.createTempFile("dlm-meta2", ".torrent")
        try {
            file.writeBytes(torrentBytes())
            val meta = TorrentMetadataReader.readFile(file).getOrThrow()
            assertEquals(4, meta.files.size)

            val all = TorrentSelection.validated(
                meta = meta,
                link = magnet,
                saveDirectory = File("C:/downloads"),
                selected = TorrentSelection.allSelected(meta)
            )
            assertNotNull("a fetched list must be queueable", all)
            assertEquals(4, all!!.selectedFiles.size)

            val some = TorrentSelection.validated(
                meta = meta,
                link = magnet,
                saveDirectory = File("C:/downloads"),
                selected = setOf(1, 2)
            )
            assertNotNull("a partial selection must be queueable", some)
            assertEquals(2, some!!.selectedFiles.size)
            assertTrue(
                "and must not carry the files that were left out",
                TorrentSelection.selectedSize(meta, some.selectedFiles) <
                    TorrentSelection.selectedSize(meta, all.selectedFiles)
            )

            assertNull(
                "a magnet with everything unticked is still a refusal, exactly as it is " +
                    "for a .torrent",
                TorrentSelection.validated(
                    meta = meta,
                    link = magnet,
                    saveDirectory = File("C:/downloads"),
                    selected = emptySet()
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun aFetchedNameBecomesTheFolderForAMultiFileTorrent() {
        val file = File.createTempFile("dlm-meta3", ".torrent")
        try {
            file.writeBytes(torrentBytes())
            val meta = TorrentMetadataReader.readFile(file).getOrThrow()
            assertEquals(
                "without it the files land loose beside everything else already downloaded",
                "Example Release",
                TorrentSelection.contentFolder(meta, "")
            )
        } finally {
            file.delete()
        }
    }
}
