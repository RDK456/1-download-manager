package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.model.isLocalHost
import com.downloadhub.app.ui.expressive.model.isLocalUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSafetyTest {
    @Test fun loopbackAndPrivateRangesAreLocal() {
        for (h in listOf("127.0.0.1", "127.8.9.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "0.0.0.0", "100.64.0.1")) {
            assertTrue(h, isLocalHost(h))
        }
    }

    @Test fun publicAddressesAreNot() {
        for (h in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "100.63.0.1", "example.com", "releases.ubuntu.com")) {
            assertFalse(h, isLocalHost(h))
        }
    }

    @Test fun localNamesAreLocal() {
        for (h in listOf("localhost", "api.localhost", "printer.local", "nas.lan", "router", "build.internal", "x.home.arpa", "LOCALHOST.")) {
            assertTrue(h, isLocalHost(h))
        }
    }

    @Test fun ipv6Loopback_linkLocalAndUniqueLocal() {
        for (h in listOf("::1", "[::1]", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:192.168.0.2")) {
            assertTrue(h, isLocalHost(h))
        }
        assertFalse(isLocalHost("2606:4700:4700::1111"))
        assertFalse(isLocalHost("::ffff:8.8.8.8"))
    }

    @Test fun disguisedLiteralsAreLocal() {
        assertTrue(isLocalHost("2130706433"))
        assertTrue(isLocalHost("0x7f000001"))
    }

    @Test fun missingHostCountsAsLocal() {
        assertTrue(isLocalHost(null))
        assertTrue(isLocalHost("  "))
        assertTrue(isLocalUrl("not a url"))
    }

    @Test fun wholeUrlsAreJudgedByTheirHost() {
        assertTrue(isLocalUrl("http://192.168.1.10:8080/file.zip"))
        assertTrue(isLocalUrl("https://localhost/x"))
        assertFalse(isLocalUrl("https://releases.ubuntu.com/24.04/x.iso"))
        assertTrue(isLocalUrl("https://user:pw@127.0.0.1/x"))
    }
}
