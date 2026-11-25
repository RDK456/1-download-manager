package com.downloadhub.desktop

import com.downloadhub.core.DownloadItem
import com.downloadhub.core.DownloadStatus
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A small aria2-compatible JSON-RPC, for scripts and the `dm.ps1` command line.
 *
 * Off by default. It listens on this computer only, every call needs the secret as aria2
 * sends it (`"token:<secret>"` as the first parameter), and anything a web page could send
 * is refused: a request with an Origin header, or one addressed to another host name.
 */
class RpcServer(
    private val secret: () -> String,
    private val list: () -> List<DownloadItem>,
    /** Queues a link and returns the new id, or null when it was refused. */
    private val add: (String) -> String?,
    private val pause: (String) -> Unit,
    private val resume: (String) -> Unit,
    private val remove: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null

    @Synchronized
    fun start(port: Int = PORT): Boolean {
        if (serverSocket != null) return true
        val socket = runCatching { ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")) }.getOrNull()
            ?: return false
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    continue
                }
                Thread({ client.use { runCatching { serve(it) } } }, "dlm-rpc").apply { isDaemon = true }.start()
            }
        }, "dlm-rpc-accept").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = client.getInputStream().buffered()
        val request = line(input).split(' ')
        val headers = mutableMapOf<String, String>()
        for (index in 0 until MAX_HEADERS) {
            val text = line(input)
            if (text.isEmpty()) break
            val colon = text.indexOf(':')
            if (colon > 0) headers[text.substring(0, colon).trim().lowercase()] = text.substring(colon + 1).trim()
        }
        val host = headers["host"].orEmpty().substringBeforeLast(':')
        if (request.size < 2 || request[0] != "POST" || request[1] != "/jsonrpc" ||
            "origin" in headers || (host != "127.0.0.1" && host != "localhost")
        ) {
            respond(client, 403, "{}")
            return
        }
        val length = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_BODY)
        val body = String(input.readNBytes(length), Charsets.UTF_8)
        respond(client, 200, call(body))
    }

    private fun line(input: InputStream): String {
        val out = StringBuilder()
        while (out.length < MAX_LINE) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private class RpcError(message: String) : Exception(message)

    /** One JSON-RPC request in, one response out. */
    fun call(body: String): String {
        var id: JsonElement = JsonNull
        return try {
            val request = DesktopJson.format.parseToJsonElement(body) as? JsonObject ?: throw RpcError("Bad request")
            id = request["id"] ?: JsonNull
            val method = (request["method"] as? JsonPrimitive)?.contentOrNull ?: throw RpcError("Bad request")
            val params = (request["params"] as? JsonArray)?.toList().orEmpty()
            val given = (params.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
            val wanted = secret()
            if (wanted.isEmpty() || !MessageDigest.isEqual(given.toByteArray(), "token:$wanted".toByteArray())) {
                throw RpcError("Unauthorized")
            }
            val result = run(method.removePrefix("aria2."), params.drop(1))
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString()
        } catch (error: Exception) {
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", 1)
                    put("message", if (error is RpcError) error.message else "Bad request")
                })
            }.toString()
        }
    }

    private fun run(method: String, args: List<JsonElement>): JsonElement {
        fun gid(): String {
            val value = (args.firstOrNull() as? JsonPrimitive)?.contentOrNull
            if (value == null || list().none { it.id == value }) throw RpcError("No such download")
            return value
        }
        val ok = JsonPrimitive("OK")
        return when (method) {
            "getVersion" -> buildJsonObject { put("version", APP_VERSION) }
            "addUri" -> {
                val uri = ((args.firstOrNull() as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull?.trim()
                    ?: throw RpcError("No link given")
                // Links only: a bare path would let a caller queue files from this machine.
                if (listOf("http://", "https://", "ftp://", "magnet:").none { uri.startsWith(it, ignoreCase = true) }) {
                    throw RpcError("Only http, https, ftp and magnet links")
                }
                JsonPrimitive(add(uri) ?: throw RpcError("The link was not added"))
            }
            "tellStatus" -> gid().let { wanted -> status(list().first { it.id == wanted }) }
            "tellActive" -> statuses { it == DownloadStatus.RUNNING }
            "tellWaiting" -> statuses {
                it == DownloadStatus.QUEUED || it == DownloadStatus.RESOLVING || it == DownloadStatus.PAUSED
            }
            "tellStopped" -> statuses { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED }
            "getGlobalStat" -> buildJsonObject {
                val all = list()
                put("downloadSpeed", all.filter { it.status == DownloadStatus.RUNNING }.sumOf { it.speedBytesPerSecond }.toString())
                put("numActive", all.count { it.status == DownloadStatus.RUNNING }.toString())
            }
            "pause" -> gid().also(pause).let { JsonPrimitive(it) }
            "unpause" -> gid().also(resume).let { JsonPrimitive(it) }
            "pauseAll" -> ok.also { list().filter { it.status != DownloadStatus.COMPLETED }.forEach { pause(it.id) } }
            "unpauseAll" -> ok.also { list().filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.id) } }
            // Takes it off the list; the files on disk stay.
            "remove", "removeDownloadResult" -> gid().also(remove).let { JsonPrimitive(it) }
            else -> throw RpcError("Method not found")
        }
    }

    private fun statuses(match: (DownloadStatus) -> Boolean) = JsonArray(list().filter { match(it.status) }.map(::status))

    /** aria2 sends every number as a string. */
    private fun status(item: DownloadItem) = buildJsonObject {
        put("gid", item.id)
        put("status", when (item.status) {
            DownloadStatus.RUNNING -> "active"
            DownloadStatus.PAUSED -> "paused"
            DownloadStatus.COMPLETED -> "complete"
            DownloadStatus.FAILED -> "error"
            else -> "waiting"
        })
        put("totalLength", item.totalBytes.toString())
        put("completedLength", item.bytesDownloaded.toString())
        put("downloadSpeed", item.speedBytesPerSecond.toString())
        put("name", item.fileName)
        item.errorMessage?.let { put("errorMessage", it) }
    }

    companion object {
        const val PORT = 6800
        private const val MAX_BODY = 64 * 1024
        private const val MAX_LINE = 4096
        private const val MAX_HEADERS = 50
    }
}
