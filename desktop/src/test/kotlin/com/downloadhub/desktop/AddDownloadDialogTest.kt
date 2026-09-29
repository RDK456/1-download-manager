package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-download dialog, for all three kinds of add.
 *
 * The dialog used to be torrent-only, so a magnet and a plain link went straight into the
 * queue with nowhere to be put. Making it the one place every add passes through means it
 * has to be honest about the cases where there is nothing to choose - which is two of the
 * three - rather than showing an empty pane.
 */
class AddDownloadDialogTest {

    private fun torrentFile(): File {
        val file = File.createTempFile("dlm-pre", ".torrent")
        file.writeBytes(
            ("d" +
                "4:infod" +
                "5:filesl" +
                "d6:lengthi700e4:pathl4:docs5:a.txtee" +
                "d6:lengthi2048e4:pathl3:bin5:b.exeee" +
                "e" +
                "4:name4:Pack12:piece lengthi2048ee" +
                "e").toByteArray(Charsets.ISO_8859_1)
        )
        return file
    }

    @Test
    fun aLinkGoesThroughTheDialogRatherThanStraightIntoTheQueue() {
        val pending = PendingDownload.forLink("https://example.com/big.zip")
        assertNotNull("a plain link must be given the dialog too", pending)
        assertFalse(pending!!.isTorrent)
        assertFalse(
            "a plain link has no file list, so there is nothing to tick",
            pending.hasFileList
        )
    }

    @Test
    fun aMagnetGoesThroughTheDialogWithoutPretendingItHasFiles() {
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=ubuntu"
        val pending = PendingDownload.forLink(magnet)
        assertNotNull(pending)
        assertTrue("a magnet is a torrent", pending!!.isTorrent)
        assertFalse(
            "a magnet has no file list until peers answer, and showing an empty one would " +
                "read as a broken dialog",
            pending.hasFileList
        )
        assertEquals("ubuntu", pending.name)
    }

    @Test
    fun aTorrentFileIsReadAndItsFilesListed() {
        val file = torrentFile()
        try {
            val pending = PendingDownload.forLink(file.absolutePath)
            assertNotNull(pending)
            assertTrue(pending!!.isTorrent)
            assertTrue(pending.hasFileList)
            assertEquals("Pack", pending.name)
            assertEquals(2, pending.metainfo.files.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun somethingUndownloadableIsNotEvenOfferedTheDialog() {
        // The exact shape of the bug that produced a row reading
        // `no protocol: [Judas] Chainsaw Man (Season 1)`.
        assertNull(
            PendingDownload.forLink("[Judas] Chainsaw Man (Season 1) [1080p][HEVC x265 10bit]")
        )
        assertNull(PendingDownload.forLink(""))
        assertNull(PendingDownload.forLink("   "))
    }

    @Test
    fun theFileListIsFilteredOnTheWholePathNotJustTheName() {
        val meta = com.downloadhub.core.TorrentMetainfo(
            name = "pack",
            files = listOf(
                com.downloadhub.core.TorrentFile(0, "docs/same.txt", 10L),
                com.downloadhub.core.TorrentFile(1, "bin/same.exe", 20L)
            ),
            comment = "",
            createdAtEpochMillis = 0L,
            createdBy = "",
            infoHashV1 = "a",
            infoHashV2 = "b",
            isSingleFile = false
        )

        assertEquals(2, contentRowsFor(meta, "").size)
        // Both are called "same", so a name filter would be ambiguous.
        assertEquals(1, contentRowsFor(meta, "docs").size)
        assertEquals(1, contentRowsFor(meta, "same.exe").size)
        assertEquals(0, contentRowsFor(meta, "nothing").size)
    }

    @Test
    fun aListOnlyShowsTheLastSegmentButThePathIsWhatItIsFilteredOn() {
        val meta = com.downloadhub.core.TorrentMetainfo(
            name = "pack",
            files = listOf(com.downloadhub.core.TorrentFile(0, "a/b/very-long-name.mkv", 10L)),
            comment = "",
            createdAtEpochMillis = 0L,
            createdBy = "",
            infoHashV1 = "a",
            infoHashV2 = "b",
            isSingleFile = false
        )
        val row = contentRowsFor(meta, "").single()
        assertEquals("very-long-name.mkv", row.name)
        assertEquals("a/b/very-long-name.mkv", row.path)
    }

    @Test
    fun aTorrentWithNoFilesSelectedCannotBeConfirmed() {
        val file = torrentFile()
        try {
            val meta = com.downloadhub.core.TorrentParser.parse(file)
            // What the dialog does when the user has unticked everything.
            assertNull(
                "a torrent with nothing selected must be refused, or the row never moves " +
                    "and never says why",
                com.downloadhub.core.TorrentSelection.validated(
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
    fun aLinkWithNoFileListIsConfirmableWithNothingSelected() {
        val pending = PendingDownload.forLink("https://example.com/big.zip")!!
        val request = com.downloadhub.core.TorrentSelection.validated(
            meta = pending.metainfo,
            link = pending.link,
            saveDirectory = File("C:/downloads"),
            selected = emptySet()
        )
        assertNotNull(
            "a plain link has nothing to tick, so an empty selection must not stop it " +
                "being added",
            request
        )
        assertEquals("https://example.com/big.zip", request!!.link)
    }

    @Test
    fun theStopConditionChoicesMapOntoWhatTheEngineUnderstands() {
        assertTrue(stopConditionFrom(0, "2.0") is com.downloadhub.core.TorrentStopCondition.Never)
        val ratio = stopConditionFrom(1, "2.5")
        assertTrue(ratio is com.downloadhub.core.TorrentStopCondition.AtRatio)
        assertEquals(2.5, (ratio as com.downloadhub.core.TorrentStopCondition.AtRatio).ratio, 0.001)

        val amount = stopConditionFrom(2, "500")
        assertTrue(amount is com.downloadhub.core.TorrentStopCondition.AtUploadedAmount)
        assertEquals(
            500L * 1024L * 1024L,
            (amount as com.downloadhub.core.TorrentStopCondition.AtUploadedAmount).bytes
        )

        val time = stopConditionFrom(3, "30")
        assertEquals(
            30,
            (time as com.downloadhub.core.TorrentStopCondition.AfterSeedingFor).minutes
        )
    }

    @Test
    fun anUnparseableStopValueMeansNeverRatherThanStopImmediately() {
        // A limit of zero would mean "stop now", which looks like the torrent finished the
        // instant it was added.
        assertTrue(stopConditionFrom(1, "abc") is com.downloadhub.core.TorrentStopCondition.Never)
        assertTrue(stopConditionFrom(1, "0") is com.downloadhub.core.TorrentStopCondition.Never)
        assertTrue(stopConditionFrom(2, "") is com.downloadhub.core.TorrentStopCondition.Never)
        assertTrue(stopConditionFrom(3, "-5") is com.downloadhub.core.TorrentStopCondition.Never)
    }

    @Test
    fun theDialogKeepsTheSourceFileSoAddingItActuallyQueues() {
        // This is the reported bug: the dialog opened, looked right, and Add did nothing,
        // because the request carried no file and queuing one without bytes is refused.
        val file = torrentFile()
        try {
            val meta = com.downloadhub.core.TorrentParser.parse(file)
            val request = com.downloadhub.core.TorrentSelection.validated(
                meta = meta,
                link = file.absolutePath,
                saveDirectory = File("C:/downloads"),
                selected = setOf(0, 1),
                metainfoFile = file
            )
            assertNotNull("a torrent read from a real file must be queueable", request)
            assertEquals(
                "the file has to travel with the request, or there is nothing to keep a " +
                    "copy of and nothing to read on the next start",
                file,
                request!!.metainfoFile
            )
        } finally {
            file.delete()
        }
    }
}
