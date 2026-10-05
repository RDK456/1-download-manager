package com.downloadhub.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpOptionsTest {
    @Test
    fun headerLinesParseAndBadOnesAreDropped() {
        val parsed = HttpRequestOptions.parseHeaders("Referer: https://a.example/x\nbroken line\n: no name\nX-Token:  abc:def \nBad Name: v")
        assertEquals(mapOf("Referer" to "https://a.example/x", "X-Token" to "abc:def"), parsed)
        assertEquals(parsed, HttpRequestOptions.parseHeaders(HttpRequestOptions.formatHeaders(parsed)))
    }

    @Test
    fun theServerReceivesHeadersCookiesAndLogin() {
        var seen: com.sun.net.httpserver.Headers? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seen = exchange.requestHeaders
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val options = HttpRequestOptions(
                headers = mapOf("Referer" to "https://site.example/page", "User-Agent" to "Custom/1"),
                cookies = "session=42; theme=dark",
                username = "amy",
                password = "s3cret"
            )
            val connection = URL("http://127.0.0.1:${server.address.port}/f").openConnection(Proxy.NO_PROXY)
            connection.setRequestProperty("User-Agent", "Engine/1")
            options.applyTo(connection)
            (connection as java.net.HttpURLConnection).responseCode
            val headers = seen!!
            assertEquals("https://site.example/page", headers.getFirst("Referer"))
            assertEquals("a header typed for the download wins", "Custom/1", headers.getFirst("User-Agent"))
            assertEquals("session=42; theme=dark", headers.getFirst("Cookie"))
            assertEquals("Basic YW15OnMzY3JldA==", headers.getFirst("Authorization"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun proxySettingsMapToJavaProxies() {
        assertEquals(Proxy.NO_PROXY, ProxySetting(ProxyType.NONE).toProxy())
        assertNull(ProxySetting(ProxyType.SYSTEM).toProxy())
        val socks = ProxySetting(ProxyType.SOCKS, "127.0.0.1", 1080).toProxy()!!
        assertEquals(Proxy.Type.SOCKS, socks.type())
        assertTrue(socks.address().toString().contains("1080"))
        assertEquals("a half-filled proxy goes direct", Proxy.NO_PROXY, ProxySetting(ProxyType.HTTP, "", 0).toProxy())
    }
}
