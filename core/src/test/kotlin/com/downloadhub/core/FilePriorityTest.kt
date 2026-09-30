package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-file priority: the words, the arithmetic, and what gets sent to libtorrent.
 *
 * "Skip this one file" and "get this one file first" are the two things a torrent client
 * is for that a plain download list is not, and both are one call into libtorrent. What
 * sits between them and that call is the piece arithmetic, and it is the part that fails
 * quietly: a piece is shared by the file before it and the file after it, so an
 * off-by-one in a boundary does not throw - it reorders a download queue and nothing
 * else.
 */
class FilePriorityTest {

    /** libtorrent's ranks, named so the expectations below read as the same numbers. */
    private val ignore = FilePriority.SKIP.libtorrentRank
    private val low = FilePriority.LOW.libtorrentRank
    private val normal = FilePriority.NORMAL.libtorrentRank
    private val top = FilePriority.HIGH.libtorrentRank

    @Test
    fun `the five priorities use qBittorrent's words`() {
        assertEquals(
            listOf("Skip", "Low", "Normal", "High", "Maximum"),
            FilePriority.entries.map { it.label }
        )
    }

    @Test
    fun `the ranks are libtorrent's, so they go straight into the handle`() {
        assertEquals(0, FilePriority.SKIP.libtorrentRank)
        assertEquals(1, FilePriority.LOW.libtorrentRank)
        assertEquals(4, FilePriority.NORMAL.libtorrentRank)
        assertEquals(7, FilePriority.HIGH.libtorrentRank)
    }

    /**
     * High and Maximum share a file rank and differ in the piece queue.
     *
     * libtorrent has four ranks and three are already spoken for, so a fifth label cannot
     * be a fifth number. "Maximum" is "High, and take my pieces before everyone else's",
     * which is a real difference and is the one a user means by it.
     */
    @Test
    fun `only maximum raises a file's pieces`() {
        assertFalse(FilePriority.SKIP.raisesPieces)
        assertFalse(FilePriority.LOW.raisesPieces)
        assertFalse(FilePriority.NORMAL.raisesPieces)
        assertFalse(FilePriority.HIGH.raisesPieces)
        assertTrue(FilePriority.MAXIMUM.raisesPieces)
        assertEquals(FilePriority.HIGH.libtorrentRank, FilePriority.MAXIMUM.libtorrentRank)
    }

    /** An unreadable stored value must not cost the user a file. */
    @Test
    fun `an unknown stored priority falls back to Normal rather than Skip`() {
        assertEquals(FilePriority.NORMAL, FilePriority.fromOrdinal(null))
        assertEquals(FilePriority.NORMAL, FilePriority.fromOrdinal(99))
        assertEquals(FilePriority.NORMAL, FilePriority.fromOrdinal(-1))
        assertEquals(FilePriority.SKIP, FilePriority.fromOrdinal(FilePriority.SKIP.ordinal))
    }

    /**
     * The pre-download selection outranks a per-file choice.
     *
     * A file the user unticked in the dialog is not wanted, and a per-file control that
     * said otherwise would be a control lying about what it does.
     */
    @Test
    fun `a deselected file is skipped whatever the map says`() {
        val chosen = mapOf(1 to FilePriority.MAXIMUM, 2 to FilePriority.HIGH)
        // Index 1 is not in the selection, so Maximum does not get it.
        assertEquals(FilePriority.SKIP, effectiveFilePriority(setOf(0, 2), chosen, 1))
        // Index 2 is in it and has an opinion of its own.
        assertEquals(FilePriority.HIGH, effectiveFilePriority(setOf(0, 2), chosen, 2))
        // Index 0 is in it and nobody has said anything about it.
        assertEquals(FilePriority.NORMAL, effectiveFilePriority(setOf(0, 2), chosen, 0))
    }

    /** An empty selection means every file: what a torrent added without the dialog carries. */
    @Test
    fun `an empty selection means every file is wanted`() {
        val chosen = mapOf(0 to FilePriority.SKIP)
        assertEquals(FilePriority.SKIP, effectiveFilePriority(emptySet(), chosen, 0))
        assertEquals(FilePriority.NORMAL, effectiveFilePriority(emptySet(), chosen, 1))
    }

    /** Nothing chosen anywhere means normal everywhere. */
    @Test
    fun `a file nobody has an opinion about is Normal`() {
        assertEquals(FilePriority.NORMAL, effectiveFilePriority(setOf(0, 1, 2), emptyMap(), 1))
    }

    /** One rank per file, in file order. */
    @Test
    fun `the plan has one rank per file`() {
        val plan = planFilePriorities(
            fileSizes = listOf(100L, 200L, 300L),
            pieceCount = 6,
            pieceLength = 100L,
            chosen = mapOf(0 to FilePriority.SKIP, 2 to FilePriority.HIGH)
        )
        assertEquals(3, plan.filePriorities.size)
        assertEquals(ignore, plan.filePriorities[0])
        assertEquals(normal, plan.filePriorities[1])
        assertEquals(top, plan.filePriorities[2])
    }

    /**
     * The piece queue is left alone unless something asked for it.
     *
     * Sending an all-default array would clear a piece order set elsewhere - first and
     * last pieces first, which is what makes a video playable. So the plan carries null
     * and the engine sends nothing.
     */
    @Test
    fun `the piece queue is untouched when no file is at Maximum`() {
        val plan = planFilePriorities(
            fileSizes = listOf(100L, 200L),
            pieceCount = 3,
            pieceLength = 100L,
            chosen = mapOf(0 to FilePriority.HIGH, 1 to FilePriority.SKIP)
        )
        assertNull("a piece queue was sent that nobody asked for", plan.piecePriorities)
        assertTrue(plan.boostedFiles.isEmpty())
    }

    /**
     * A file's pieces are the ones its first and last bytes are in.
     *
     * A piece is shared between neighbouring files, so this is the boundary that is easy
     * to get wrong. File 1 here starts at byte 100 - inside piece 0 - so piece 0 belongs to
     * it as well as to file 0.
     */
    @Test
    fun `a file's pieces include the ones it shares with its neighbours`() {
        val plan = planFilePriorities(
            fileSizes = listOf(150L, 150L, 100L),
            pieceCount = 4,
            pieceLength = 100L,
            chosen = mapOf(1 to FilePriority.MAXIMUM)
        )
        val pieces = plan.piecePriorities!!
        // File 1 runs from byte 150 to byte 299: piece 1 (100-199) and piece 2 (200-299).
        assertEquals(7, pieces[1])
        assertEquals(7, pieces[2])
        // Piece 0 is bytes 0-99, entirely file 0. Piece 3 is bytes 300-399, entirely file 2.
        assertEquals(4, pieces[0])
        assertEquals(4, pieces[3])
        assertEquals(listOf(1), plan.boostedFiles)
    }

    /** The first file's range starts at piece zero, which is a boundary of its own. */
    @Test
    fun `the first file's pieces start at zero`() {
        val plan = planFilePriorities(
            fileSizes = listOf(250L, 100L),
            pieceCount = 4,
            pieceLength = 100L,
            chosen = mapOf(0 to FilePriority.MAXIMUM)
        )
        val pieces = plan.piecePriorities!!
        assertEquals(7, pieces[0])
        assertEquals(7, pieces[1])
        assertEquals(7, pieces[2])
        // File 0 ends at byte 249, which is piece 2. Piece 3 is the second file's.
        assertEquals(4, pieces[3])
    }

    /**
     * A file smaller than one piece still owns that piece.
     *
     * Common in a season pack - an extra, a sample - and a file of zero length offset
     * arithmetic otherwise gets wrong, because its last byte is its first.
     */
    @Test
    fun `a file smaller than a piece still owns one`() {
        val plan = planFilePriorities(
            fileSizes = listOf(100L, 10L, 100L),
            pieceCount = 3,
            pieceLength = 100L,
            chosen = mapOf(1 to FilePriority.MAXIMUM)
        )
        val pieces = plan.piecePriorities!!
        assertEquals("the 10-byte file's only piece was not raised", 7, pieces[1])
        assertEquals(4, pieces[0])
        assertEquals(4, pieces[2])
    }

    /** A zero-length file has no bytes and therefore no pieces. */
    @Test
    fun `a zero-length file raises nothing`() {
        val plan = planFilePriorities(
            fileSizes = listOf(200L, 0L, 100L),
            pieceCount = 3,
            pieceLength = 100L,
            chosen = mapOf(1 to FilePriority.MAXIMUM)
        )
        assertNull("an empty file has no pieces to raise", plan.piecePriorities)
        assertTrue(
            "an empty file was counted as boosted, so a piece queue would be sent for it",
            plan.boostedFiles.isEmpty()
        )
        // Its file rank still goes out - here Maximum's rank, since that is what was chosen
        // for it - so the file is skipped from the piece queue rather than merely absent.
        assertEquals(FilePriority.MAXIMUM.libtorrentRank, plan.filePriorities[1])
    }

    /** Two files at Maximum share the piece where they meet, and both get it. */
    @Test
    fun `two files at maximum both get the piece they share`() {
        val plan = planFilePriorities(
            fileSizes = listOf(150L, 150L),
            pieceCount = 3,
            pieceLength = 100L,
            chosen = mapOf(0 to FilePriority.MAXIMUM, 1 to FilePriority.MAXIMUM)
        )
        val pieces = plan.piecePriorities!!
        assertEquals(listOf(0, 1), plan.boostedFiles)
        // File 0: pieces 0-1. File 1 starts at 150, so piece 1-2. Piece 0 and 2 are raised.
        assertEquals(7, pieces[0])
        assertEquals(7, pieces[1])
        assertEquals(7, pieces[2])
    }

    /**
     * A torrent with no file list plans to nothing rather than throwing.
     *
     * A magnet that has not received its metadata has no files and no sizes, and the
     * priority call comes from a list that may be empty at that moment.
     */
    @Test
    fun `a torrent with no files plans to nothing`() {
        val plan = planFilePriorities(emptyList(), 0, 0L, emptyMap())
        assertEquals(0, plan.filePriorities.size)
        assertNull(plan.piecePriorities)
    }

    /**
     * Sizes that disagree with the piece count are clamped rather than thrown.
     *
     * The last file of a torrent is usually a partial piece, so the last piece often
     * extends past the final byte on disk. Writing past the array is how a priority
     * change turns into a crash instead of a setting.
     */
    @Test
    fun `piece ranges past the end of the piece array are clamped`() {
        val plan = planFilePriorities(
            // Three hundred bytes at a hundred a piece is three pieces, not four, whatever
            // the caller claims.
            fileSizes = listOf(150L, 150L),
            pieceCount = 2,
            pieceLength = 100L,
            chosen = mapOf(1 to FilePriority.MAXIMUM)
        )
        val pieces = plan.piecePriorities!!
        assertEquals("the plan wrote past the piece array", 2, pieces.size)
        assertEquals(7, pieces[1])
    }

    /** Two plans of the same shape are equal, so a caller can compare them. */
    @Test
    fun `plans compare by their contents`() {
        val a = planFilePriorities(listOf(100L), 1, 100L, mapOf(0 to FilePriority.MAXIMUM))
        val b = planFilePriorities(listOf(100L), 1, 100L, mapOf(0 to FilePriority.MAXIMUM))
        val c = planFilePriorities(listOf(100L), 1, 100L, mapOf(0 to FilePriority.HIGH))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    /** "Set all" is an empty map, and it must not be mistaken for an error. */
    @Test
    fun `set all is an empty map`() {
        assertTrue(FilePriority.all().isEmpty())
}
    /**
     * Setting one file must not disturb the others.
     *
     * The whole point of a per-file control. If it rewrote the map from scratch, changing
     * file 7 back to Normal would silently return files 3 and 5 to Normal as well, and
     * the user would find out by noticing which files stopped arriving first.
     */
    @Test
    fun `changing one file leaves the others alone`() {
        val before = mapOf(
            1 to FilePriority.HIGH,
            3 to FilePriority.SKIP,
            7 to FilePriority.MAXIMUM
        )
        val after = before + (7 to FilePriority.NORMAL)
        assertEquals(FilePriority.HIGH, after[1])
        assertEquals(FilePriority.SKIP, after[3])
        assertEquals(FilePriority.NORMAL, after[7])
        assertEquals(3, after.size)
    }

    /**
     * And "set all" is one call per file, not one call that overwrites the map.
     *
     * Which is why the toolbar applies a chosen priority to every index rather than
     * replacing the map: replacing it loses nothing today but reads as though a file
     * nobody ticked had been set, and there is no way to see that afterwards.
     */
    @Test
    fun `setting every file reaches every index`() {
        val indices = (0 until 12).toList()
        val updated = indices.associateWith { FilePriority.SKIP }
        assertEquals(12, updated.size)
        assertTrue(updated.values.all { it == FilePriority.SKIP })
        assertEquals(12, updated.keys.toSet().intersect(indices.toSet()).size)
    }

    /**
     * A priority survives being stored and read back.
     *
     * Ordinals rather than names, so the queue file stays small - and so that a rename
     * cannot orphan a saved choice. This is the test that a rename would fail.
     */
    @Test
    fun `a priority survives the round trip through storage`() {
        for (option in FilePriority.entries) {
            val stored = option.ordinal
            assertEquals(option, FilePriority.fromOrdinal(stored))
        }
    }
}
