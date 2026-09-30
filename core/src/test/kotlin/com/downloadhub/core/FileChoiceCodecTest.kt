package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage form of a torrent's file choices.
 *
 * Leniency is the point of most of these. A column written by an older build, or null, or
 * edited by hand, has to read as something harmless - because the alternative is a database
 * that cannot be opened, and a user who cannot open their app cannot be told why.
 */
class FileChoiceCodecTest {

    @Test
    fun `selected files survive a round trip`() {
        val selected = setOf(0, 3, 7, 11)
        assertEquals(selected, FileChoiceCodec.decodeSelected(FileChoiceCodec.encodeSelected(selected)))
    }

    /** An empty selection is not the same as no column. Both mean every file. */
    @Test
    fun `an empty selection is an empty string`() {
        assertEquals("", FileChoiceCodec.encodeSelected(emptyList()))
        assertTrue(FileChoiceCodec.decodeSelected("").isEmpty())
        assertTrue(FileChoiceCodec.decodeSelected(null).isEmpty())
    }

    /**
     * Order is not information, and a stable order means an unchanged selection produces an
     * unchanged string - so re-saving does not rewrite the row for nothing.
     */
    @Test
    fun `the order files were ticked in is not kept`() {
        assertEquals("0,1,2", FileChoiceCodec.encodeSelected(listOf(2, 0, 1)))
    }

    /** A duplicate is a tick box the user pressed twice, not two files. */
    @Test
    fun `a repeated index is stored once`() {
        assertEquals("1,4", FileChoiceCodec.encodeSelected(listOf(4, 1, 4, 1)))
    }

    @Test
    fun `priorities survive a round trip`() {
        val priorities = mapOf(
            0 to FilePriority.SKIP,
            4 to FilePriority.HIGH,
            9 to FilePriority.MAXIMUM
        )
        assertEquals(priorities, FileChoiceCodec.decodePriorities(FileChoiceCodec.encodePriorities(priorities)))
    }

    /** Every priority, through storage, in order index. */
    @Test
    fun `every priority survives storage`() {
        for (option in FilePriority.entries) {
            val stored = FileChoiceCodec.encodePriorities(mapOf(3 to option))
            assertEquals(
                "$option did not survive storage",
                mapOf(3 to option),
                FileChoiceCodec.decodePriorities(stored)
            )
        }
    }

    /**
     * A half-written column must not take the app down.
     *
     * A trailing separator, a missing colon, letters where numbers belong, whitespace, a
     * negative index: each of these is something a hand-edited row or an interrupted write
     * produces, and each must read as "not set" rather than as a crash.
     */
    @Test
    fun `a damaged column reads as nothing rather than throwing`() {
        val damaged = listOf(
            "", " ", ",", ",,", "0:4,", "0:", ":4", "0:4:9", "abc", "0:abc",
            "-1:4", "0:-1", "0:99", "999:0", "0:4,,3:7", " 0 : 4 ", "0:4;3:7"
        )
        for (value in damaged) {
            // The assertion is that it returns at all.
            FileChoiceCodec.decodePriorities(value)
            FileChoiceCodec.decodeSelected(value)
        }
        // The ones that still carry a usable pair must still be read.
        val skip = "${FilePriority.SKIP.ordinal}"
        val high = "${FilePriority.HIGH.ordinal}"
        assertEquals(mapOf(0 to FilePriority.SKIP), FileChoiceCodec.decodePriorities("0:$skip"))
        assertEquals(mapOf(3 to FilePriority.HIGH), FileChoiceCodec.decodePriorities("0:9,,3:$high"))
        assertEquals(mapOf(0 to FilePriority.NORMAL), FileChoiceCodec.decodePriorities(" 0 : ${FilePriority.NORMAL.ordinal} "))
    }

    /**
     * An ordinal this build does not know is dropped, not defaulted.
     *
     * The distinction matters: "not set" means Normal, so turning an unrecognised ordinal
     * into Normal would report a choice the user never made - and in the one case where the
     * stored value was Maximum from a newer build, it would silently stop raising that
     * file's pieces.
     */
    @Test
    fun `an ordinal from a newer build is dropped rather than defaulted`() {
        // The known half reads; the unknown ordinal is dropped rather than defaulted.
        val normal = "${FilePriority.NORMAL.ordinal}"
        val decoded = FileChoiceCodec.decodePriorities("0:$normal,3:99")
        assertEquals(mapOf(0 to FilePriority.NORMAL), decoded)
        assertTrue("an unknown ordinal became a choice", 3 !in decoded)
    }

    /** A negative index is not a file. */
    @Test
    fun `a negative index is dropped`() {
        assertTrue(FileChoiceCodec.decodePriorities("-2:4").isEmpty())
        assertTrue(FileChoiceCodec.decodeSelected("-2,1") == setOf(1))
    }

    /** Empty in, empty out, for both, on a column that has never been written. */
    @Test
    fun `a column that was never written is empty for both`() {
        assertTrue(FileChoiceCodec.decodePriorities(null).isEmpty())
        assertTrue(FileChoiceCodec.decodeSelected(null).isEmpty())
    }

    /**
     * A big torrent's column stays a column and not a document.
     *
     * A four-hundred-file season with a priority on every file is about 3.4 kB. Worth
     * knowing, because the alternative to one column is a table with a row per file.
     */
    @Test
    fun `four hundred priorities is a few kilobytes`() {
        val stored = FileChoiceCodec.encodePriorities(
            (0 until 400).associateWith { FilePriority.NORMAL }
        )
        assertTrue("400 priorities took ${stored.length} characters", stored.length < 4096)
        assertEquals(400, FileChoiceCodec.decodePriorities(stored).size)
    }
}
