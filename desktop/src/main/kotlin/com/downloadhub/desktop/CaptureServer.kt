package com.downloadhub.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback-only HTTP endpoint the browser extension hands links to.
 *
 * This started on the JDK's own `com.sun.net.httpserver`, which is the obvious
 * choice, but that module is trimmed out of the jlink runtime the installer ships:
 * the app compiled, passed every test on a full JDK, and did nothing once packaged.
 * The Compose plugin has no supported way to add a module back, so the handful of
 * lines it takes to speak enough HTTP/1.1 directly are cheaper than a permanent
 * dependency on a module that may vanish again.
 *
 * The surface is deliberately tiny: two routes, loopback only, and a shared secret
 * on every mutating request. See [CaptureRules] for the checks.
 */
class CaptureServer(
    private val token: String,
    private val onQueue: (CaptureRequest) -> Unit,
    private val onMessage: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val served = AtomicLong(0)

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    /**
     * Binds and starts serving.
     *
     * A busy port is not fatal: the app picks another one, so a second copy or a
     * leftover process cannot break downloads.
     */
    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Boolean {
        if (running.get()) return true
        val socket = tryBind(preferredPort) ?: tryBind(0)
        if (socket == null) {
            onMessage("Could not start browser capture")
            return false
        }
        return try {
            serverSocket = socket
            boundPort = socket.localPort
            running.set(true)
            acceptThread = Thread({ acceptLoop(socket) }, "dlm-capture-accept").apply {
                isDaemon = true
                start()
            }
            onMessage("Browser capture is listening on port $boundPort")
            true
        } catch (error: Exception) {
            runCatching { socket.close() }
            serverSocket = null
            running.set(false)
            onMessage("Could not start browser capture: ${error.message}")
            false
        }
    }

    private fun tryBind(port: Int): ServerSocket? = runCatching {
        // Loopback only. Binding the wildcard address would expose this to the network.
        ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
    }.getOrNull()

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        boundPort = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                // Closed during stop(); that is the normal way out.
                return
            } catch (_: Exception) {
                continue
            }
            // One short-lived thread per request keeps a slow client from blocking
            // the accept loop, and the extension only ever sends tiny requests.
            Thread({
                runCatching { handle(client) }
                runCatching { client.close() }
            }, "dlm-capture-$served").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))

        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respond(client, 400, """{"ok":false,"error":"malformed request"}""")
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        if (path == "/ping") {
            respond(client, 200, """{"ok":true}""")
            return
        }

        if (path != "/queue") {
            respond(client, 404, """{"ok":false,"error":"not found"}""")
            return
        }

        if (method != "POST") {
            respond(client, 405, """{"ok":false,"error":"use POST"}""")
            return
        }

        if (!CaptureRules.isAuthorised(headers["host"], headers["x-dlm-token"], token)) {
            // 403 is deliberate: a rejected token is a configuration problem the user
            // can fix, not a transient error worth retrying.
            respond(client, 403, """{"ok":false,"error":"unauthorised"}""")
            return
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY_BYTES) {
            respond(client, 400, """{"ok":false,"error":"bad body"}""")
            return
        }
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            if (n <= 0) break
            read += n
        }
        val request = parse(String(body, 0, read))
        if (request == null) {
            respond(client, 400, """{"ok":false,"error":"unsupported link"}""")
            return
        }
        served.incrementAndGet()
        onQueue(request)
        respond(client, 200, """{"ok":true}""")
    }

    private fun parse(body: String): CaptureRequest? {
        val json = runCatching { DesktopJson.format.decodeFromString<CapturePayload>(body) }
            .getOrNull() ?: return null
        val link = CaptureRules.normaliseLink(json.url) ?: return null
        return CaptureRequest(
            url = link,
            referer = json.referer?.take(1024),
            fileName = json.fileName?.take(256)
        )
    }

    private fun respond(client: Socket, status: Int, body: String) {
        runCatching {
            val bytes = body.toByteArray()
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                // Loopback only, but do not let a page read the reply.
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = client.getOutputStream()
            out.write(head.toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    companion object {
        const val DEFAULT_PORT = 38621
        private const val MAX_BODY_BYTES = 16 * 1024

        /** A fresh shared secret per install. */
        fun newToken(): String {
            val random = java.security.SecureRandom()
            // No look-alike characters, and nothing needing escaping in a header.
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            return (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}

/** What the extension sends. */
@kotlinx.serialization.Serializable
data class CapturePayload(
    val url: String? = null,
    val referer: String? = null,
    val fileName: String? = null
)

/** A validated link ready to be queued. */
data class CaptureRequest(
    val url: String,
    val referer: String? = null,
    val fileName: String? = null
)
