package com.downloadhub.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCheckTest {
    @Test
    fun fairPlayAndSampleAesAreRefused() {
        assertTrue(StreamCheck.isDrmProtectedHls("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"x\"\n"))
        assertTrue(StreamCheck.isDrmProtectedHls("#EXT-X-KEY:METHOD=AES-128,URI=\"skd://key\"\n"))
    }

    @Test
    fun plainAesAndClearStreamsAreAllowed() {
        assertFalse(StreamCheck.isDrmProtectedHls("#EXT-X-KEY:METHOD=AES-128,URI=\"https://a/key\"\n"))
        assertFalse(StreamCheck.isDrmProtectedHls("#EXTM3U\n#EXTINF:4,\nseg.ts\n"))
    }
}
