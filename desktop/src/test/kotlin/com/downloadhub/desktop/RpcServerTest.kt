package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcServerTest {
    private val added = mutableListOf<String>()
    private val paused = mutableListOf<String>()
    private val server = RpcServer(
        secret = { "s3cret" },
        list = { listOf(DownloadItem(id = "a1", url = "https://x/f.zip", fileName = "f.zip")) },
        add = { added.add(it); "new1" },
        pause = { paused.add(it) },
        resume = {},
        remove = {}
    )

    private fun body(method: String, params: String) = """{"jsonrpc":"2.0","id":1,"method":"aria2.$method","params":[$params]}"""

    @Test
    fun needsTheSecret() {
        assertTrue(server.call(body("pause", "\"token:wrong\",\"a1\"")).contains("Unauthorized"))
        assertTrue(server.call(body("pause", "\"a1\"")).contains("Unauthorized"))
        assertTrue(paused.isEmpty())
    }

    @Test
    fun addsLinksButNotLocalPaths() {
        assertTrue(server.call(body("addUri", "\"token:s3cret\",[\"https://x/y.zip\"]")).contains("new1"))
        server.call(body("addUri", "\"token:s3cret\",[\"C:\\\\secret.torrent\"]"))
        assertEquals(listOf("https://x/y.zip"), added)
    }

    @Test
    fun pausesAKnownDownloadOnly() {
        server.call(body("pause", "\"token:s3cret\",\"a1\""))
        assertTrue(server.call(body("pause", "\"token:s3cret\",\"nope\"")).contains("No such download"))
        assertEquals(listOf("a1"), paused)
    }

    @Test
    fun overHttpRefusesWebPagesAndAnswersLocalCallers() {
        val port = ServerSocket(0).use { it.localPort }
        assertTrue(server.start(port))
        try {
            fun send(extra: String): String = Socket("127.0.0.1", port).use { socket ->
                val json = body("tellStatus", "\"token:s3cret\",\"a1\"")
                socket.getOutputStream().write(
                    ("POST /jsonrpc HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$extra" +
                        "Content-Length: ${json.length}\r\n\r\n$json").toByteArray()
                )
                socket.getInputStream().readBytes().decodeToString()
            }
            assertTrue(send("").contains("f.zip"))
            assertTrue(send("Origin: https://evil.example\r\n").startsWith("HTTP/1.1 403"))
        } finally {
            server.stop()
        }
    }
}
