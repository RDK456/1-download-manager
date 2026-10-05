package com.downloadhub.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLConnection
import java.util.Base64

/**
 * What one download sends besides the URL: extra headers, cookies and a login.
 *
 * Some servers only hand a file to the browser session that asked for it, or only to a
 * signed-in user. AB Download Manager lets each download carry these, and so does this.
 * Applied after the engine's own headers, so a header typed here wins - a site that
 * insists on a particular User-Agent can be given one.
 */
data class HttpRequestOptions(
    val headers: Map<String, String> = emptyMap(),
    /** As a browser sends it: `name=value; other=value`. */
    val cookies: String = "",
    val username: String = "",
    val password: String = ""
) {
    val isEmpty: Boolean
        get() = headers.isEmpty() && cookies.isBlank() && username.isEmpty()

    fun applyTo(connection: URLConnection) {
        if (username.isNotEmpty()) {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $token")
        }
        if (cookies.isNotBlank()) connection.setRequestProperty("Cookie", cookies.trim())
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    }

    companion object {
        /**
         * `Name: value` lines to a map. Lines without a colon, with a blank name, or with a
         * name a header cannot have are dropped rather than sent broken.
         */
        fun parseHeaders(text: String): Map<String, String> = text.lines()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) null
                else name to value
            }
            .toMap(LinkedHashMap())

        fun formatHeaders(headers: Map<String, String>): String =
            headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }
    }
}

enum class ProxyType(val label: String) {
    NONE("No proxy"),
    SYSTEM("Use the Windows proxy"),
    HTTP("HTTP proxy"),
    SOCKS("SOCKS5 proxy")
}

/** The app-wide proxy for HTTP downloads. */
data class ProxySetting(
    val type: ProxyType = ProxyType.SYSTEM,
    val host: String = "",
    val port: Int = 0
) {
    /**
     * The proxy to open connections through. Null means "whatever the system's proxy
     * selector says", which is how the Windows setting is followed.
     */
    fun toProxy(): Proxy? = when (type) {
        ProxyType.NONE -> Proxy.NO_PROXY
        ProxyType.SYSTEM -> null
        ProxyType.HTTP, ProxyType.SOCKS -> {
            if (host.isBlank() || port !in 1..65535) {
                Proxy.NO_PROXY
            } else {
                Proxy(
                    if (type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                    InetSocketAddress.createUnresolved(host.trim(), port)
                )
            }
        }
    }
}
