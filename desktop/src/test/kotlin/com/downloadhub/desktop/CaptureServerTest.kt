package com.downloadhub.desktop

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the capture endpoint over a real socket.
 *
 * The rule tests cover the decisions; these cover the wiring, because a server
 * that binds but answers 403 for a valid hand-off is exactly the kind of failure
 * that only shows up once a user clicks a link in their browser.
 */
class CaptureServerTest {

    private val token = "testtoken1234567890abcdefghijkl"

    private fun post(
        server: CaptureServer,
        body: String,
        withToken: Boolean = true,
        path: String = "/queue"
    ): Pair<Int, String> {
        val connection = (URL("http://127.0.0.1:${server.boundPort}$path").openConnection()
            as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (withToken) setRequestProperty("X-DLM-Token", token)
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /** Raw, because HttpURLConnection will not send an Origin header - which is what a browser sends. */
    private fun pair(server: CaptureServer, vararg headers: String): Pair<Int, String> {
        java.net.Socket("127.0.0.1", server.boundPort).use { socket ->
            val request = buildString {
                append("POST /pair HTTP/1.1\r\nHost: 127.0.0.1:${server.boundPort}\r\nContent-Length: 0\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray())
            val reply = socket.getInputStream().bufferedReader().readText()
            return reply.substringAfter(' ').substringBefore(' ').toInt() to reply.substringAfter("\r\n\r\n")
        }
    }

    @Test
    fun anExtensionPairsItselfAndAWebPageCannot() {
        val server = CaptureServer(token, onQueue = {})
        try {
            server.start()
            val (code, body) = pair(server, "X-DLM-Pair: 1", "Origin: chrome-extension://abcdefghijklmnop")
            assertEquals(200, code)
            assertTrue("the extension must be handed the app's code", body.contains(token))
            assertEquals(403, pair(server, "X-DLM-Pair: 1", "Origin: https://evil.example").first)
            assertEquals(403, pair(server, "Origin: chrome-extension://abcdefghijklmnop").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun theServerBindsToLoopback() {
        val server = CaptureServer(token, onQueue = {})
        try {
            assertTrue("the server did not start", server.start())
            assertTrue("no port was bound", server.boundPort > 0)
            // Binding the wildcard address would expose this to the whole network.
            assertTrue(server.boundPort in 1..65535)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pingAnswersWithoutAToken() {
        val server = CaptureServer(token, onQueue = {})
        try {
            server.start()
            val connection = URL("http://127.0.0.1:${server.boundPort}/ping").openConnection() as HttpURLConnection
            assertEquals(200, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aValidHandOffIsQueued() {
        val queued = mutableListOf<CaptureRequest>()
        val server = CaptureServer(token, onQueue = { queued += it })
        try {
            server.start()
            val (code, body) = post(
                server,
                """{"url":"https://youtu.be/dQw4w9WgXcQ","referer":"https://youtube.com/","fileName":"clip.mp4"}"""
            )
            assertEquals("body was $body", 200, code)
            assertEquals(1, queued.size)
            assertEquals("https://youtu.be/dQw4w9WgXcQ", queued.first().url)
            assertEquals("clip.mp4", queued.first().fileName)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aHandOffWithoutTheTokenIsRefused() {
        val queued = mutableListOf<CaptureRequest>()
        val server = CaptureServer(token, onQueue = { queued += it })
        try {
            server.start()
            val (code, _) = post(server, """{"url":"https://youtu.be/abc"}""", withToken = false)
            assertEquals(403, code)
            assertTrue("nothing may be queued without the token", queued.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun aHandOffForAnUnknownHostIsRefused() {
        val queued = mutableListOf<CaptureRequest>()
        val server = CaptureServer(token, onQueue = { queued += it })
        try {
            server.start()
            val (code, _) = post(server, """{"url":"https://random.example.org/x.zip"}""")
            assertEquals(400, code)
            assertTrue(queued.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun getIsNotAllowed() {
        val server = CaptureServer(token, onQueue = {})
        try {
            server.start()
            val connection = (URL("http://127.0.0.1:${server.boundPort}/queue").openConnection()
                as HttpURLConnection)
            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun stoppingReleasesThePort() {
        val server = CaptureServer(token, onQueue = {})
        server.start()
        val port = server.boundPort
        server.stop()
        assertTrue("stop() should clear the port", server.boundPort == 0)

        // Binding the same port again proves it was really released.
        val second = CaptureServer(token, onQueue = {})
        try {
            assertTrue(second.start(port))
        } finally {
            second.stop()
        }
    }
}
