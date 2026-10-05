package com.downloadhub.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackTunnelTest {
    private val live = InetAddress.getByName("127.0.0.1")
    private val dead = InetAddress.getByName("127.0.0.2") // nothing listens on it

    private fun echoServer(): ServerSocket = ServerSocket(0, 8, live).also { server ->
        Thread {
            runCatching {
                server.accept().use { s ->
                    val line = s.getInputStream().bufferedReader().readLine()
                    s.getOutputStream().write("$line\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @Test
    fun `an address that does not answer is skipped, then tried last`() {
        echoServer().use { server ->
            val socket = FallbackTunnel.connectFirst(listOf(dead, live), server.localPort)
            assertNotNull("the second address answers, so a socket must come back", socket)
            socket!!.use { assertEquals(live, it.inetAddress) }
            assertEquals(listOf(live, dead), FallbackTunnel.trialOrder(listOf(dead, live)))
        }
    }

    @Test
    fun `a CONNECT request is relayed byte for byte`() {
        val tunnel = FallbackTunnel().also { it.start() }
        try {
            echoServer().use { server ->
                Socket(InetAddress.getLoopbackAddress(), tunnel.port).use { client ->
                    client.soTimeout = 10_000
                    val out = client.getOutputStream()
                    out.write("CONNECT 127.0.0.1:${server.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    out.flush()
                    val reader = client.getInputStream().bufferedReader()
                    assertTrue(reader.readLine().contains(" 200 "))
                    while (reader.readLine().isNotEmpty()) Unit
                    out.write("hello\n".toByteArray())
                    out.flush()
                    assertEquals("hello", reader.readLine())
                }
            }
        } finally {
            tunnel.close()
        }
    }
}
