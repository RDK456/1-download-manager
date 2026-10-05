package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerStatusTest {
    @Test
    fun trackerStatesReadAsQBittorrentWordsThem() {
        assertEquals("Not contacted yet", trackerStatus(contacted = false, updating = false, working = false, fails = 0))
        assertEquals("Updating...", trackerStatus(contacted = true, updating = true, working = true, fails = 0))
        assertEquals("Working", trackerStatus(contacted = true, updating = false, working = true, fails = 0))
        assertEquals("Not working", trackerStatus(contacted = true, updating = false, working = false, fails = 3))
    }
}
