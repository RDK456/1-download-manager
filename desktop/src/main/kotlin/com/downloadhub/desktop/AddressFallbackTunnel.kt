package com.downloadhub.desktop

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes every HTTPS connection the app opens try each of a server's addresses.
 *
 * Java's HttpURLConnection connects only to the first address DNS returns. When that one
 * is unreachable from the user's network - one of GitHub's four download servers is, on
 * some networks - the download times out even though the other three answer at once.
 * Browsers, curl and OkHttp move on to the next address. This gives the JDK the same
 * behaviour without changing any of the places that open connections.
 *
 * It is a CONNECT proxy on loopback, set as the default [ProxySelector] for https. Java
 * hands an HTTP proxy the hostname rather than an address, so the tunnel resolves it,
 * tries the addresses in turn, then relays the encrypted bytes untouched: TLS and its
 * certificate checks still run in the JDK, end to end with the server.
 *
 * A proxy the system is configured with still wins; loopback and plain http go direct.
 */
object AddressFallbackTunnel {
    @Volatile
    private var installed: FallbackTunnel? = null

    @Synchronized
    fun install() {
        if (installed != null) return
        val tunnel = runCatching { FallbackTunnel().also { it.start() } }.getOrNull() ?: return
        installed = tunnel
        val previous = ProxySelector.getDefault()
        val viaTunnel = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), tunnel.port))
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                val chosen = runCatching { previous?.select(uri) }.getOrNull().orEmpty()
                if (chosen.any { it.type() != Proxy.Type.DIRECT }) return chosen
                if (!uri.scheme.equals("https", ignoreCase = true) || isLocal(uri.host)) return listOf(Proxy.NO_PROXY)
                return listOf(viaTunnel)
            }

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                previous?.connectFailed(uri, sa, ioe)
            }
        })
    }

    private fun isLocal(host: String?): Boolean {
        val h = host?.trim('[', ']')?.lowercase() ?: return true
        return h == "localhost" || h.startsWith("127.") || h == "::1"
    }
}

/** The tunnel itself: accepts CONNECT on loopback and relays to the first address that answers. */
internal class FallbackTunnel {
    private val server = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    fun start() {
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                Thread({ serve(client) }, "address-fallback-conn").apply { isDaemon = true }.start()
            }
        }, "address-fallback-tunnel").apply { isDaemon = true }.start()
    }

    fun close() = runCatching { server.close() }

    private fun serve(client: Socket) {
        client.use {
            client.soTimeout = HEAD_TIMEOUT_MILLIS
            val input = client.getInputStream()
            val out = client.getOutputStream()
            val requestLine = readHead(input)?.lineSequence()?.firstOrNull().orEmpty().split(' ')
            if (requestLine.size < 2 || !requestLine[0].equals("CONNECT", ignoreCase = true)) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            val target = requestLine[1]
            val host = target.substringBeforeLast(':').trim('[', ']')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 443
            val upstream = runCatching { connectFirst(InetAddress.getAllByName(host).toList(), port) }.getOrNull()
            if (upstream == null) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            upstream.use {
                client.soTimeout = 0
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                out.flush()
                val up = Thread({
                    runCatching { input.copyTo(upstream.getOutputStream(), BUFFER) }
                    runCatching { upstream.shutdownOutput() }
                }, "address-fallback-up").apply { isDaemon = true; start() }
                runCatching { upstream.getInputStream().copyTo(out, BUFFER) }
                runCatching { client.shutdownOutput() }
                up.join(1_000)
            }
        }
    }

    /** The request head, up to the blank line, read a byte at a time so nothing after it is consumed. */
    private fun readHead(input: InputStream): String? {
        val bytes = StringBuilder()
        while (bytes.length < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            bytes.append(b.toChar())
            if (bytes.endsWith("\r\n\r\n")) return bytes.toString()
        }
        return null
    }

    companion object {
        private const val HEAD_TIMEOUT_MILLIS = 15_000
        private const val MAX_HEAD = 8 * 1024
        private const val BUFFER = 64 * 1024
        private const val PER_ADDRESS_MILLIS = 4_000
        private const val REMEMBER_FAILURE_MILLIS = 10 * 60_000L

        /** When each address last failed; it is tried last until this has run out. */
        private val failedAt = ConcurrentHashMap<InetAddress, Long>()

        /** Addresses in the order to try: those that have not failed recently first, DNS order kept otherwise. */
        internal fun trialOrder(addresses: List<InetAddress>, now: Long = System.currentTimeMillis()): List<InetAddress> {
            val (recent, fresh) = addresses.partition { (failedAt[it] ?: 0L) > now - REMEMBER_FAILURE_MILLIS }
            return fresh + recent
        }

        /** A connected socket to the first address that answers, or null when none does. */
        internal fun connectFirst(addresses: List<InetAddress>, port: Int): Socket? {
            for (address in trialOrder(addresses)) {
                val socket = Socket()
                try {
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(address, port), PER_ADDRESS_MILLIS)
                    failedAt.remove(address)
                    return socket
                } catch (e: IOException) {
                    failedAt[address] = System.currentTimeMillis()
                    runCatching { socket.close() }
                }
            }
            return null
        }
    }
}
