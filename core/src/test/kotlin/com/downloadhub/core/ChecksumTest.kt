package com.downloadhub.core

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ChecksumTest {
    private fun file() = File.createTempFile("checksum", ".txt").apply {
        writeText("abc")
        deleteOnExit()
    }

    @Test
    fun matchesTheRightHashAndRejectsTheWrongOne() {
        val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(true, Checksum.matches(file(), sha256.uppercase()))
        assertEquals(false, Checksum.matches(file(), "0".repeat(64)))
        assertEquals(true, Checksum.matches(file(), "900150983cd24fb0d6963f7d28e17f72"))
    }

    @Test
    fun anUnknownLengthIsNotAHash() {
        assertNull(Checksum.matches(file(), "abc123"))
    }
}

