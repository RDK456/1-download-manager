package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DialogHelpersTest {
    @Test
    fun hostIsTheSiteWithoutWww() {
        assertEquals("example.com", hostOf("https://www.example.com/a/b.zip"))
        assertEquals("not a link", hostOf("not a link"))
    }

    @Test
    fun freeSpaceWalksUpToAFolderThatExists() {
        // A folder that has not been created yet is still on a drive with room.
        assertNotNull(freeSpace(System.getProperty("java.io.tmpdir") + "/dlm-not-yet/made"))
        assertNull(freeSpace("  "))
    }
}
