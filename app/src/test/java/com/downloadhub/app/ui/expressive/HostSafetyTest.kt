package com.downloadhub.app.ui.expressive

import com.downloadhub.app.ui.expressive.model.isLocalAddress
import com.downloadhub.app.ui.expressive.model.isLocalHost
import com.downloadhub.app.ui.expressive.model.isLocalUrl
import com.downloadhub.app.ui.expressive.model.parseIpLiteral
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSafetyTest {
    private fun local(h: String) = assertTrue("$h should be local", isLocalHost(h))
    private fun public(h: String) = assertFalse("$h should be public", isLocalHost(h))

    @Test fun loopbackAndPrivateRangesAreLocal() {
        listOf(
            "127.0.0.1", "127.8.9.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "0.0.0.0", "100.64.0.1", "100.100.100.200", "198.18.0.1", "224.0.0.1", "255.255.255.255"
        ).forEach(::local)
    }

    @Test fun publicAddressesAreNot() {
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "100.63.0.1", "100.128.0.1", "1.1.1.1").forEach(::public)
    }

    @Test fun publicNamesAreNotLocalByText() {
        listOf("example.com", "releases.ubuntu.com", "cdn.example.co.uk").forEach(::public)
    }

    @Test fun localNamesAreLocal() {
        listOf("localhost", "api.localhost", "printer.local", "nas.lan", "router", "build.internal", "x.home.arpa", "LOCALHOST.", "vault.private").forEach(::local)
    }

    // The spellings a text match misses.

    @Test fun inetAtonShortOctalAndHexFormsAreParsed() {
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), parseIpLiteral("127.1"))
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), parseIpLiteral("0177.0.0.1"))
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), parseIpLiteral("0x7f.0.0.1"))
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), parseIpLiteral("0x7f000001"))
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), parseIpLiteral("2130706433"))
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), parseIpLiteral("10.1"))
        listOf("127.1", "0177.0.0.1", "0x7f.0.0.1", "0x7f000001", "2130706433", "0300.0250.0.1", "10.1").forEach(::local)
    }

    @Test fun malformedNumbersAreNotAddressesButAreNotTrustedEither() {
        assertNull(parseIpLiteral("256.1.1.1"))
        assertNull(parseIpLiteral("1.2.3.4.5"))
        assertNull(parseIpLiteral("09.1.1.1"))
        assertNull(parseIpLiteral("1..2"))
        local("256.1.1.1")
        local("1.2.3.4.5")
    }

    @Test fun everyWayOfWritingTheIpv6LoopbackIsLocal() {
        listOf("::1", "[::1]", "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001", "::0001").forEach(::local)
    }

    @Test fun ipv6PrivateAndLinkLocalRangesAreLocal() {
        listOf("fe80::1", "[fe80::1%eth0]", "fc00::1", "fd12:3456::1", "fec0::1", "ff02::1", "::").forEach(::local)
    }

    @Test fun mappedAndEmbeddedIpv4InIpv6IsJudgedByTheV4Part() {
        listOf("::ffff:127.0.0.1", "::ffff:7f00:1", "::ffff:192.168.0.2", "::ffff:c0a8:2", "::127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::", "2002:c0a8:1::1").forEach(::local)
        listOf("::ffff:8.8.8.8", "::ffff:808:808", "64:ff9b::808:808", "2002:808:808::1").forEach(::public)
    }

    @Test fun publicIpv6IsNot() {
        listOf("2606:4700:4700::1111", "2001:4860:4860::8888").forEach(::public)
    }

    @Test fun badIpv6IsNotParsedButIsTreatedWithSuspicion() {
        assertNull(parseIpLiteral("1::2::3"))
        assertNull(parseIpLiteral("12345::1"))
        assertNull(parseIpLiteral("1:2:3:4:5:6:7:8:9"))
        assertNotNull(parseIpLiteral("1:2:3:4:5:6:7:8"))
    }

    @Test fun missingHostCountsAsLocal() {
        local("")
        assertTrue(isLocalHost(null))
        assertTrue(isLocalUrl("not a url"))
    }

    @Test fun wholeUrlsAreJudgedByTheirHost() {
        assertTrue(isLocalUrl("http://192.168.1.10:8080/file.zip"))
        assertTrue(isLocalUrl("https://localhost/x"))
        assertTrue(isLocalUrl("http://0x7f.1/x"))
        assertTrue(isLocalUrl("http://[::ffff:7f00:1]/x"))
        assertTrue(isLocalUrl("https://user:pw@127.0.0.1/x"))
        assertFalse(isLocalUrl("https://releases.ubuntu.com/24.04/x.iso"))
    }

    @Test fun addressBytesAreChecked() {
        assertTrue(isLocalAddress(byteArrayOf(10, 1, 2, 3)))
        assertFalse(isLocalAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
    }
}
