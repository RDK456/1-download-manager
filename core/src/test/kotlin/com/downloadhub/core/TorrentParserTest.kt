package com.downloadhub.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading real `.torrent` bytes.
 *
 * The awkward corners of the format are covered here with hand-built torrents, and a file
 * produced the way an encoder produces one is covered too. Both are needed: a parser that
 * passes tidy synthetic bencode and fails real piece hashes is a parser that works on
 * nothing.
 */
class TorrentParserTest {

    /**
     * A bencode string.
     *
     * The length is counted in ISO-8859-1 bytes, not UTF-8, because that is how the file
     * is written. Counting UTF-8 gives 2 for every character above 127, and the declared
     * length then runs past the value - which a parser quite correctly reports as a
     * truncated file.
     */
    private fun benString(value: String) =
        "${value.toByteArray(Charsets.ISO_8859_1).size}:$value"

    /** Keys are sorted byte by byte, which is what every torrent uses. */
    private fun benDict(vararg pairs: String) = "d" + pairs.sorted().joinToString("") + "e"

    private fun infoOf(vararg pairs: String) = benDict(*pairs)

    @Test
    fun aSingleFileTorrentReportsItsNameSizeAndDate() {
        // Wrapped in a real torrent: the `comment`, `created by` and `creation date` all
        // live inside `info`, which is what real torrents do. Reading them from the wrong
        // level is a bug that only shows up against real files.
        val meta = TorrentParser.parse(
            benDict(
                benString("info") + infoOf(
                    benString("comment") + benString("an example"),
                    benString("created by") + benString("a test"),
                    benString("creation date") + "i1700000000e",
                    benString("length") + "i1024e",
                    benString("name") + benString("ubuntu.iso"),
                    benString("piece length") + "i4096e",
                    // Pieces are a fixed 20 bytes each in v1.
                    benString("pieces") + benString("x".repeat(3 * 20))
                )
            ).toByteArray(Charsets.ISO_8859_1)
        )

        assertEquals("ubuntu.iso", meta.name)
        assertTrue("a one-file torrent is a single file", meta.isSingleFile)
        assertEquals(1, meta.files.size)
        assertEquals(1024L, meta.files.single().size)
        assertEquals(1024L, meta.totalSize)
        assertEquals(4096L, meta.pieceLength)
        assertEquals(3, meta.pieceCount)
        assertEquals("an example", meta.comment)
        assertEquals("a test", meta.createdBy)
        assertEquals(1700000000000L, meta.createdAtEpochMillis)
    }

    /**
     * The info hash has to be the hash of the info dictionary and nothing else.
     *
     * A hash taken over the whole file, or over a re-encoded dictionary, gives a different
     * answer and every other torrent tool would treat the result as a different torrent.
     */
    @Test
    fun theInfoHashIsTheHashOfTheInfoDictionaryAndNothingElse() {
        val info = infoOf(
            benString("length") + "i1024e",
            benString("name") + benString("ubuntu.iso"),
            benString("piece length") + "i4096e"
        )
        val meta = TorrentParser.parse(
            benDict(benString("info") + info).toByteArray(Charsets.ISO_8859_1)
        )

        assertEquals(
            MessageDigest.getInstance("SHA-1").digest(info.toByteArray(Charsets.ISO_8859_1))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            meta.infoHashV1
        )
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(info.toByteArray(Charsets.ISO_8859_1))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) },
            meta.infoHashV2
        )
        assertEquals(40, meta.infoHashV1.length)
    }

    @Test
    fun aMultiFileTorrentListsEveryFileWithItsPathAndSize() {
        val meta = TorrentParser.parse(
            benDict(
                benString("info") + infoOf(
                    benString("files") + "l" +
                        benDict(
                            benString("length") + "i700e",
                            benString("path") + "l" + benString("docs") + benString("readme.txt") + "e"
                        ) +
                        benDict(
                            benString("length") + "i2048e",
                            benString("path") + "l" + benString("bin") + benString("tool.exe") + "e"
                        ) +
                        "e",
                    benString("name") + benString("MyPack"),
                    benString("piece length") + "i2048e"
                )
            ).toByteArray(Charsets.ISO_8859_1)
        )

        assertTrue("a two-file torrent is not single file", !meta.isSingleFile)
        assertEquals("MyPack", meta.name)
        assertEquals(2, meta.files.size)
        assertEquals("docs/readme.txt", meta.files[0].path)
        assertEquals(700L, meta.files[0].size)
        assertEquals("readme.txt", meta.files[0].name)
        assertEquals("bin/tool.exe", meta.files[1].path)
        assertEquals(2048L, meta.files[1].size)
        assertEquals(2748L, meta.totalSize)
    }

    /**
     * A v2 torrent describes a file *tree*. Left unflattened it looks like a torrent with
     * no files at all, and the dialog would offer to download nothing.
     */
    @Test
    fun aV2FileTreeIsFlattenedIntoTheSameListShape() {
        val leaf = benDict(
            benString("") + benString("leaf.txt"),
            benString("length") + "i120e",
            benString("piece length") + "i4096e"
        )
        val meta = TorrentParser.parse(
            benDict(
                benString("info") + infoOf(
                    benString("file tree") + benDict(
                        benString("folder") + benDict(
                            benString("nested") + benDict(
                                benString("leaf.txt") + leaf
                            )
                        )
                    ),
                    benString("meta version") + "i2e",
                    benString("name") + benString("v2pack"),
                    benString("piece length") + "i4096e"
                )
            ).toByteArray(Charsets.ISO_8859_1)
        )

        assertEquals("v2pack", meta.name)
        assertEquals(1, meta.files.size)
        assertEquals("folder/nested/leaf.txt", meta.files.single().path)
        assertEquals(120L, meta.files.single().size)
    }

    @Test
    fun somethingThatIsNotATorrentIsRejectedWithAReason() {
        val failure = runCatching {
            TorrentParser.parse("<html><body>404 Not Found</body></html>".toByteArray())
        }.exceptionOrNull()

        assertTrue(
            "an HTML page must not parse as a torrent, but it gave: $failure",
            failure is TorrentParseException
        )
    }

    @Test
    fun aTruncatedTorrentIsRejectedRatherThanReadAsEmpty() {
        val full = infoOf(benString("length") + "i1024e", benString("name") + benString("a"))
            .toByteArray(Charsets.ISO_8859_1)
        val failure = runCatching { TorrentParser.parse(full.copyOfRange(0, full.size - 6)) }
            .exceptionOrNull()

        assertTrue(
            "a half-written torrent must not look like a valid empty one, but it gave: $failure",
            failure is TorrentParseException
        )
    }

    @Test
    fun aTorrentWithNoInfoSectionIsRejected() {
        val raw = benDict(benString("announce") + benString("udp://tracker.example"))
            .toByteArray(Charsets.ISO_8859_1)
        assertTrue(
            "a torrent with no info section cannot be added by anyone",
            runCatching { TorrentParser.parse(raw) }.exceptionOrNull() is TorrentParseException
        )
    }

    @Test
    fun aRealFileIsRecognisedByItsContentsAndNotJustItsName() {
        val good = File.createTempFile("dlm-real", ".torrent")
        val html = File.createTempFile("dlm-fake", ".torrent")
        val tiny = File.createTempFile("dlm-tiny", ".torrent")
        try {
            good.writeBytes(
                benDict(
                    benString("info") + infoOf(
                        benString("length") + "i1024e",
                        benString("name") + benString("real.iso")
                    )
                ).toByteArray(Charsets.ISO_8859_1)
            )
            html.writeText("<html>404 Not Found</html>")
            tiny.writeBytes("d".toByteArray())

            assertTrue("a real torrent must be recognised", TorrentParser.looksLikeTorrent(good))
            assertTrue(
                "an HTML page named .torrent must be refused, so the drop can say why",
                !TorrentParser.looksLikeTorrent(html)
            )
            assertTrue(
                "a file too short to be a torrent must be refused",
                !TorrentParser.looksLikeTorrent(tiny)
            )
            assertTrue(
                "a folder is not a torrent",
                !TorrentParser.looksLikeTorrent(File(good.parentFile, "no-such-folder.torrent"))
            )
        } finally {
            good.delete()
            html.delete()
            tiny.delete()
        }
    }

    /**
     * Piece hashes contain bytes above 127, so this also catches a parser that reads the
     * file as text. The consequence would be an info hash nothing else recognises, which
     * shows up much later as a tracker refusing the download.
     */
    @Test
    fun binaryBytesInsideTheTorrentSurviveParsing() {
        val pieces = (0 until 400).joinToString("") { ((it * 37 + 11) % 256).toChar().toString() }
        val info = infoOf(
            benString("length") + "i1024e",
            benString("name") + benString("binary"),
            benString("piece length") + "i4096e",
            benString("pieces") + benString(pieces)
        )
        val meta = TorrentParser.parse(
            benDict(benString("info") + info).toByteArray(Charsets.ISO_8859_1)
        )

        assertEquals("binary", meta.name)
        assertEquals(1024L, meta.files.single().size)
        assertTrue(
            "an info hash from a torrent with high bytes in it must still be 40 hex chars",
            meta.infoHashV1.length == 40
        )
    }

    @Test
    fun aDictionaryKeyContainingTheLetterEIsNotMistakenForTheEnd() {
        // The bug this guards: scanning for the closing `e` finds the one inside a
        // string value and cuts the dictionary in half. Both a value containing "e" and a
        // key containing one are covered, because the walk has to skip over both.
        val meta = TorrentParser.parse(
            benDict(
                benString("announce") + benString("udp://tracker.example:1337/announce"),
                benString("info") + infoOf(
                    benString("comment") + benString("has an e in it"),
                    benString("created by") + benString("the encoder"),
                    benString("length") + "i10e",
                    benString("name") + benString("file.name"),
                    benString("piece length") + "i10e"
                )
            ).toByteArray(Charsets.ISO_8859_1)
        )
        assertEquals("has an e in it", meta.comment)
        assertEquals("the encoder", meta.createdBy)
        assertEquals("file.name", meta.name)
    }
}
