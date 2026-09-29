package com.downloadhub.core

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The torrent pre-download dialog's data.
 *
 * Two things, and both are places where being wrong is invisible until much later. A
 * wrong file list offers files that are not in the torrent. A wrong info hash makes every
 * other client treat this as a different torrent, and the tracker refuses it.
 */
class TorrentSelectionTest {

    private fun benString(value: String) =
        "${value.toByteArray(Charsets.ISO_8859_1).size}:$value"

    private fun benDict(vararg pairs: String) = "d" + pairs.sorted().joinToString("") + "e"

    /** A two-file torrent, which is enough to check selection and totals. */
    private fun twoFileTorrent(): TorrentMetainfo = TorrentParser.parse(
        benDict(
            benString("info") + benDict(
                benString("files") + "l" +
                    benDict(
                        benString("length") + "i700e",
                        benString("path") + "l" + benString("docs") + benString("a.txt") + "e"
                    ) +
                    benDict(
                        benString("length") + "i2048e",
                        benString("path") + "l" + benString("bin") + benString("b.exe") + "e"
                    ) +
                    "e",
                benString("name") + benString("Pack"),
                benString("piece length") + "i2048e"
            )
        ).toByteArray()
    )

    private fun singleFileTorrent(name: String = "one.mkv"): TorrentMetainfo = TorrentParser.parse(
        benDict(
            benString("info") + benDict(
                benString("length") + "i1024e",
                benString("name") + benString(name),
                benString("piece length") + "i4096e"
            )
        ).toByteArray()
    )

    private fun writeTorrent(): File {
        val file = File.createTempFile("dlm-sel", ".torrent")
        file.writeBytes(
            benDict(
                benString("info") + benDict(
                    benString("files") + "l" +
                        benDict(
                            benString("length") + "i700e",
                            benString("path") + "l" + benString("docs") + benString("a.txt") + "e"
                        ) +
                        benDict(
                            benString("length") + "i2048e",
                            benString("path") + "l" + benString("bin") + benString("b.exe") + "e"
                        ) +
                        "e",
                    benString("name") + benString("Pack"),
                    benString("piece length") + "i2048e"
                )
            ).toByteArray()
        )
        return file
    }

    @Test
    fun everyFileStartsSelected() {
        val meta = twoFileTorrent()
        assertEquals(setOf(0, 1), TorrentSelection.allSelected(meta))
        assertEquals(2748L, TorrentSelection.selectedSize(meta, TorrentSelection.allSelected(meta)))
    }

    @Test
    fun unselectingAFileDropsItFromTheTotal() {
        val meta = twoFileTorrent()
        assertEquals(700L, TorrentSelection.selectedSize(meta, setOf(0)))
        assertEquals(2048L, TorrentSelection.selectedSize(meta, setOf(1)))
    }

    @Test
    fun aTorrentWithNothingSelectedIsRefusedRatherThanQueued() {
        val meta = twoFileTorrent()
        val file = writeTorrent()
        try {
            assertNull(
                "queuing a torrent with no files gives a row that never moves and never " +
                    "says why, so it must be refused",
                TorrentSelection.validated(
                    meta = meta,
                    link = file.absolutePath,
                    saveDirectory = File("C:/downloads"),
                    selected = emptySet(),
                    metainfoFile = file
                )
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun indicesThatAreNotInTheTorrentAreDropped() {
        val file = writeTorrent()
        try {
            val request = TorrentSelection.validated(
                meta = twoFileTorrent(),
                link = file.absolutePath,
                saveDirectory = File("C:/downloads"),
                selected = setOf(0, 7, 99),
                metainfoFile = file
            )
            assertNotNull(request)
            assertEquals(
                "an index the torrent does not have must not be carried into the request",
                setOf(0),
                request!!.selectedFiles
            )
        } finally {
            file.delete()
        }
    }

    /**
     * A magnet and a plain link have no file list, so an empty selection is normal for
     * them. Refusing one would make the pre-download dialog unusable for everything except
     * `.torrent` files.
     */
    @Test
    fun aMagnetWithNoFileListIsStillQueueable() {
        val request = TorrentSelection.validated(
            meta = TorrentMetainfo.empty("ubuntu"),
            link = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3",
            saveDirectory = File("C:/downloads"),
            selected = emptySet()
        )
        assertNotNull(
            "a magnet has no files to choose from, so an empty selection is not a refusal",
            request
        )
        assertFalse(request!!.hasFileList)
    }

    @Test
    fun aLinkThatCannotBeDownloadedIsRefused() {
        // The exact shape of the bug that produced a row reading
        // `no protocol: [Judas] Chainsaw Man (Season 1)`.
        assertNull(
            TorrentSelection.validated(
                meta = TorrentMetainfo.empty("[Judas] Chainsaw Man (Season 1)"),
                link = "[Judas] Chainsaw Man (Season 1) [1080p][HEVC x265 10bit]",
                saveDirectory = File("C:/downloads"),
                selected = emptySet()
            )
        )
    }

    @Test
    fun aTorrentFileThatIsNotOnDiskIsRefused() {
        assertNull(
            "queuing a torrent whose .torrent has gone gives a row that can never start",
            TorrentSelection.validated(
                meta = twoFileTorrent(),
                link = "C:/no/such/thing.torrent",
                saveDirectory = File("C:/downloads"),
                selected = setOf(0),
                metainfoFile = File("C:/no/such/thing.torrent")
            )
        )
    }

    @Test
    fun aMultiFileTorrentGetsAFolderAndASingleFileTorrentDoesNot() {
        val multi = twoFileTorrent()
        assertEquals("Pack", TorrentSelection.contentFolder(multi, ""))
        assertEquals("Renamed", TorrentSelection.contentFolder(multi, "Renamed"))
        // One file in a folder of its own is just a file with an extra click to get to it.
        assertEquals("", TorrentSelection.contentFolder(singleFileTorrent(), ""))
    }

    @Test
    fun aFolderNameThatCannotExistOnDiskIsMadeSafe() {
        assertEquals("AC_DC_ Live", TorrentSelection.safeName("AC/DC: Live"))
        assertEquals("_etc_passwd", TorrentSelection.safeName("\\etc\\passwd"))
        assertTrue(
            "a name that is only dots cannot be written",
            TorrentSelection.safeName("...").isEmpty()
        )
    }

    @Test
    fun theWholeDialogsWorthOfChoicesSurviveIntoTheRequest() {
        val file = writeTorrent()
        try {
            val request = TorrentSelection.validated(
                meta = twoFileTorrent(),
                link = file.absolutePath,
                saveDirectory = File("C:/downloads"),
                selected = setOf(0, 1),
                metainfoFile = file,
                sequential = true,
                firstLastPiecesFirst = true,
                startImmediately = false,
                stopCondition = TorrentStopCondition.AtRatio(1.5),
                chosenFolder = "My Pack"
            )
            assertNotNull(request)
            with(request!!) {
                assertTrue(sequentialDownload)
                assertTrue(downloadFirstAndLastPiecesFirst)
                assertTrue(
                    "not starting it immediately has to reach the request, or the " +
                        "dialog's choice is a lie",
                    !startImmediately
                )
                assertEquals("My Pack", contentFolder)
                assertEquals(TorrentStopCondition.AtRatio(1.5), stopCondition)
                assertEquals(File("C:/downloads"), saveDirectory)
                assertEquals(file, metainfoFile)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun stopConditionsMapOntoShareLimitsTheEngineAlreadyUnderstands() {
        assertTrue("no condition means no limit", !TorrentStopCondition.Never.toShareLimits(1000L).enabled)

        val ratio = TorrentStopCondition.AtRatio(2.0).toShareLimits(1000L)
        assertTrue(ratio.enabled)
        assertEquals(2.0, ratio.ratioLimit, 0.0001)

        val time = TorrentStopCondition.AfterSeedingFor(30).toShareLimits(1000L)
        assertTrue(time.enabled)
        assertEquals(30, time.seedTimeLimitMinutes)

        // 500 bytes uploaded on a 1000-byte download is a ratio of 0.5, and that is what
        // makes it stop once it gets there rather than after an arbitrary amount.
        val amount = TorrentStopCondition.AtUploadedAmount(500L).toShareLimits(1000L)
        assertEquals(0.5, amount.ratioLimit, 0.0001)
        assertTrue(amount.enabled)
    }

    @Test
    fun anUploadedAmountOnAnUnknownSizeMustNotBecomeAnInfiniteRatio() {
        // downloadedBytes of 0 would divide by zero, and a limit that then reads as
        // infinity never stops - which quietly looks like the setting does nothing.
        val amount = TorrentStopCondition.AtUploadedAmount(500L).toShareLimits(0L)
        assertEquals(0.0, amount.ratioLimit, 0.0001)
        assertTrue(
            "with nothing downloaded there is nothing to compare against",
            !amount.enabled
        )
    }

    @Test
    fun everyStopConditionIsRecognisedAsALimitOrNotOne() {
        assertFalse(TorrentStopCondition.Never.isLimited)
        assertTrue(TorrentStopCondition.AtRatio(1.0).isLimited)
        assertTrue(TorrentStopCondition.AtUploadedAmount(1L).isLimited)
        assertTrue(TorrentStopCondition.AfterSeedingFor(1).isLimited)
    }

    @Test
    fun aDroppedFileBecomesARequestWithEverythingSelected() {
        val file = writeTorrent()
        try {
            val result = TorrentSelection.fromFile(file, File("C:/downloads"))

            assertTrue("a dropped torrent must queue: ${result.exceptionOrNull()}", result.isSuccess)
            val request = result.getOrThrow()
            assertEquals("Pack", request.metainfo.name)
            assertEquals(setOf(0, 1), request.selectedFiles)
            assertEquals(file, request.metainfoFile)
            assertTrue(
                "a drop should start the download, since that is what dropping a file " +
                    "means everywhere else on the desktop",
                request.startImmediately
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun aDroppedFileThatIsNotATorrentSaysSoRatherThanQueuingNothing() {
        val file = File.createTempFile("dlm-drop", ".torrent")
        try {
            file.writeText("<html>404 Not Found</html>")
            val result = TorrentSelection.fromFile(file, File("C:/downloads"))

            assertTrue("a non-torrent must be refused", result.isFailure)
            assertTrue(
                "the reason should name the file, so the user knows which drop was bad",
                result.exceptionOrNull()?.message.orEmpty().contains(file.name)
            )
        } finally {
            file.delete()
        }
    }

}
