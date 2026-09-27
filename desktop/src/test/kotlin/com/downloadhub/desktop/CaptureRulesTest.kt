package com.downloadhub.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser hand-off is a local HTTP endpoint, which is the part of this app most
 * exposed to things it does not control. These tests pin the rules that keep it
 * closed: loopback only, a per-install token, and a short list of hosts.
 */
class CaptureRulesTest {

    private val token = "abc123def456"

    // --- authorisation -------------------------------------------------------

    @Test
    fun aRequestWithTheRightTokenFromLoopbackIsAllowed() {
        assertTrue(
            CaptureRules.isAuthorised("127.0.0.1:38621", token, token)
        )
        assertTrue(CaptureRules.isAuthorised("localhost:38621", token, token))
    }

    @Test
    fun aRequestWithoutTheTokenIsRejected() {
        assertFalse(CaptureRules.isAuthorised("127.0.0.1:38621", null, token))
        assertFalse(CaptureRules.isAuthorised("127.0.0.1:38621", "", token))
        assertFalse(CaptureRules.isAuthorised("127.0.0.1:38621", "wrong-token-here", token))
    }

    @Test
    fun aTokenOfTheRightLengthButWrongContentIsRejected() {
        // Guards against a comparison that only checks the length.
        assertFalse(CaptureRules.isAuthorised("127.0.0.1", "abd123def456", token))
        assertFalse(CaptureRules.isAuthorised("127.0.0.1", "abc123def457", token))
    }

    @Test
    fun aRemoteHostHeaderIsRejected() {
        // DNS rebinding: a page resolves its own domain to 127.0.0.1 and then sends
        // this request. The Host header is what the server sees, and it is not ours.
        assertFalse(CaptureRules.isAuthorised("evil.example.com", token, token))
        assertFalse(CaptureRules.isAuthorised("attacker.test:38621", token, token))
    }

    @Test
    fun aMissingHostHeaderIsRejected() {
        assertFalse(CaptureRules.isAuthorised(null, token, token))
        assertFalse(CaptureRules.isAuthorised("", token, token))
    }

    @Test
    fun anUnsetTokenRejectsEverything() {
        // A blank shared secret must never mean "accept all".
        assertFalse(CaptureRules.isAuthorised("127.0.0.1", "", ""))
        assertFalse(CaptureRules.isAuthorised("127.0.0.1", null, ""))
    }

    @Test
    fun hostHeadersAreCaseInsensitive() {
        assertTrue(CaptureRules.isAuthorised("LOCALHOST:38621", token, token))
        assertTrue(CaptureRules.isAuthorised("127.0.0.1:38621", token, token))
    }

    // --- link validation -----------------------------------------------------

    @Test
    fun knownMediaHostsAreAccepted() {
        val accepted = listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://github.com/o/r/releases/download/v1/file.zip",
            "https://objects.githubusercontent.com/x/y",
            "https://mediafire.com/file/abc",
            "https://archive.org/download/item/file.mp4",
            "https://cdn.kernel.org/pub/linux/kernel/v6.x/file.tar.xz"
        )
        accepted.forEach { link ->
            assertEquals("expected $link to be accepted", link, CaptureRules.normaliseLink(link))
        }
    }

    @Test
    fun magnetsAreAccepted() {
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f651b4c8d5e9c9a1a4a3&dn=ubuntu"
        assertEquals(magnet, CaptureRules.normaliseLink(magnet))
    }

    @Test
    fun unknownHostsAreRejected() {
        // Not a blanket allow: the app must not become a relay for any address a
        // compromised page decides to hand it.
        assertNull(CaptureRules.normaliseLink("https://random.example.org/file.zip"))
        assertNull(CaptureRules.normaliseLink("https://192.168.1.10/share/file.mp4"))
    }

    @Test
    fun nonDownloadSchemesAreRejected() {
        assertNull(CaptureRules.normaliseLink("file:///C:/Windows/System32/evil.dll"))
        assertNull(CaptureRules.normaliseLink("javascript:alert(1)"))
        assertNull(CaptureRules.normaliseLink("data:text/html,hi"))
        assertNull(CaptureRules.normaliseLink("ftp://example.com/x.zip"))
    }

    @Test
    fun blankAndMissingLinksAreRejected() {
        assertNull(CaptureRules.normaliseLink(null))
        assertNull(CaptureRules.normaliseLink(""))
        assertNull(CaptureRules.normaliseLink("    "))
    }

    @Test
    fun anAbsurdlyLongLinkIsRejected() {
        val long = "https://github.com/" + "a".repeat(CaptureRules.MAX_LINK_LENGTH)
        assertNull(CaptureRules.normaliseLink(long))
    }

    @Test
    fun aMagnetWithoutARealHashIsRejected() {
        assertNull(CaptureRules.normaliseLink("magnet:?dn=nothing-useful"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(
            "https://youtu.be/abc",
            CaptureRules.normaliseLink("  https://youtu.be/abc  ")
        )
    }

    @Test
    fun aFreshTokenIsLongAndUrlSafe() {
        val generated = CaptureServer.newToken()
        assertEquals(32, generated.length)
        // Lower-case alphanumerics only: no punctuation that would need escaping in
        // a URL or an HTTP header, and no look-alike characters.
        assertTrue(
            "a token must be safe to paste into a URL and a header, got '$generated'",
            generated.all { it in 'a'..'z' || it in '0'..'9' }
        )
        assertTrue("two tokens must differ", generated != CaptureServer.newToken())
    }
}
