package com.downloadhub.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only an extension may be handed the pairing code: never a web page, never across the network. */
class PairingRulesTest {
    @Test
    fun `an extension asking on loopback is given the code`() {
        assertTrue(CaptureRules.mayPair("127.0.0.1:38621", "1", "chrome-extension://abcdef"))
        assertTrue(CaptureRules.mayPair("127.0.0.1:38621", "1", "moz-extension://1234-5678"))
        assertTrue(CaptureRules.mayPair("localhost:38621", "1", null))
    }

    @Test
    fun `a web page, a missing header or another host is refused`() {
        assertFalse(CaptureRules.mayPair("127.0.0.1:38621", "1", "https://evil.example"))
        assertFalse(CaptureRules.mayPair("127.0.0.1:38621", "1", "null"))
        assertFalse(CaptureRules.mayPair("127.0.0.1:38621", null, "chrome-extension://abcdef"))
        assertFalse(CaptureRules.mayPair("evil.example:38621", "1", "chrome-extension://abcdef"))
    }
}
