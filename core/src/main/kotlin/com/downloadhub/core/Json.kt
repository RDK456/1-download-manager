package com.downloadhub.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// --- a very small JSON reader ------------------------------------------------------
//
// The search sources return JSON and :core has no JSON library on purpose: it is a plain
// JVM module whose only dependency beyond libtorrent4j is coroutines, and a JSON parser is
// not worth a second dependency for four feeds that use four shapes between them. This
// reads the subset JSON actually is - objects, arrays, strings, numbers, booleans and null -
// and throws on anything else rather than guessing.

sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data object Bool : JsonValue
    data object Null : JsonValue
}

/** Thrown when a feed is not the JSON it was expected to be. */
class JsonFormatException(message: String) : IOException(message)

/** Parses [text] as JSON. */
fun parseJson(text: String): JsonValue {
    val reader = JsonReader(text)
    val value = reader.readValue()
    reader.skipWhitespace()
    if (!reader.atEnd()) throw JsonFormatException("trailing content at ${reader.position}")
    return value
}

private class JsonReader(private val text: String) {
    var position = 0

    fun atEnd(): Boolean = position >= text.length

    fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    fun readValue(): JsonValue {
        skipWhitespace()
        if (atEnd()) throw JsonFormatException("unexpected end of input")
        return when (val c = text[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't', 'f' -> readBoolean()
            'n' -> readNull()
            else ->
                if (c == '-' || c.isDigit()) readNumber()
                else throw JsonFormatException("unexpected '$c' at $position")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { position++; return JsonValue.Obj(fields) }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            fields[key] = readValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> Unit
                '}' -> return JsonValue.Obj(fields)
                else -> throw JsonFormatException("expected , or } but found '$c'")
            }
        }
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { position++; return JsonValue.Arr(items) }
        while (true) {
            items += readValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> Unit
                ']' -> return JsonValue.Arr(items)
                else -> throw JsonFormatException("expected , or ] but found '$c'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            when (val c = next()) {
                '"' -> return out.toString()
                '\\' -> when (val escape = next()) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('')
                    'u' -> {
                        val hex = text.substring(position, position + 4)
                        position += 4
                        out.append(hex.toInt(16).toChar())
                    }
                    else -> out.append(escape)
                }
                else -> out.append(c)
            }
        }
    }

    private fun readNumber(): JsonValue.Num {
        val start = position
        if (peek() == '-') position++
        while (position < text.length && (text[position].isDigit() || text[position] in ".eE+-")) position++
        val slice = text.substring(start, position)
        return JsonValue.Num(slice.toDoubleOrNull() ?: throw JsonFormatException("bad number '$slice'"))
    }

    private fun readBoolean(): JsonValue.Bool {
        if (text.startsWith("true", position)) { position += 4; return JsonValue.Bool }
        if (text.startsWith("false", position)) { position += 5; return JsonValue.Bool }
        throw JsonFormatException("bad literal at $position")
    }

    private fun readNull(): JsonValue {
        if (!text.startsWith("null", position)) throw JsonFormatException("bad literal at $position")
        position += 4
        return JsonValue.Null
    }

    private fun peek(): Char =
        if (atEnd()) throw JsonFormatException("unexpected end of input") else text[position]

    private fun next(): Char =
        if (atEnd()) throw JsonFormatException("unexpected end of input") else text[position++]

    private fun expect(c: Char) {
        val actual = next()
        if (actual != c) throw JsonFormatException("expected '$c' but found '$actual' at ${position - 1}")
    }
}

/**
 * Steps one level along [path].
 *
 * A step is an object key, or an array index when the step is a number - feeds nest arrays
 * inside objects constantly (`data.movies[0].torrents[1].size_bytes`), and a reader that
 * cannot index an array can only reach the outermost object of any row.
 */
private fun JsonValue.step(key: String): JsonValue? = when (this) {
    is JsonValue.Obj -> fields[key]
    is JsonValue.Arr -> key.toIntOrNull()?.let { items.getOrNull(it) }
    else -> null
}

/** The string at this path, or null when any step of it is missing or the wrong shape. */
fun JsonValue.string(vararg path: String): String? {
    var current: JsonValue = this
    for (key in path) {
        current = current.step(key) ?: return null
    }
    return (current as? JsonValue.Str)?.value
}

/** The number at this path, or zero. Sources are inconsistent about numbers-as-strings. */
fun JsonValue.number(vararg path: String): Long {
    var current: JsonValue = this
    for (key in path) {
        current = current.step(key) ?: return 0L
    }
    return when (current) {
        is JsonValue.Num -> current.value.toLong()
        // YTS sends sizes as numbers and EZTV sends them as numbers, but other feeds send
        // both as strings, and a size of zero because it arrived as "1.4 GB" is worse than
        // no result.
        is JsonValue.Str -> current.value.trim().toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }
}

/** The array at this path, or nothing. */
fun JsonValue.array(vararg path: String): List<JsonValue> {
    var current: JsonValue = this
    for (key in path) {
        current = current.step(key) ?: return emptyList()
    }
    return (current as? JsonValue.Arr)?.items ?: emptyList()
}

// --- RSS ----------------------------------------------------------------------------
//
// Nyaa and SubsPlease both answer with RSS and both put what matters in their own
// namespace, so the tag names are read literally rather than through a namespace-aware
// parser: a feed with a slightly different prefix would otherwise parse to nothing at all
// and look like a site with no results.

/** The first `<name>...</name>` in [fragment], with CDATA unwrapped and entities resolved. */
internal fun rssTag(fragment: String, name: String): String {
    val open = fragment.indexOf("<$name>")
    if (open < 0) return ""
    val from = open + name.length + 2
    val close = fragment.indexOf("</$name>", from)
    if (close < 0) return ""
    var value = fragment.substring(from, close).trim()
    if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
        value = value.substring(9, value.length - 3).trim()
    }
    return unescapeXmlEntities(value)
}

/** The `<item>...</item>` blocks of an RSS document, in document order. */
internal fun rssItems(xml: String): List<String> =
    xml.split("<item>").drop(1).mapNotNull { block ->
        block.substringBefore("</item>").takeIf { it.isNotEmpty() }
    }

/** `&amp;` and friends, which appear in nearly every feed title. */
internal fun unescapeXmlEntities(value: String): String {
    if ('&' !in value) return value
    var out = value
    for ((entity, replacement) in XML_ENTITIES) out = out.replace(entity, replacement)
    return out
}

private val XML_ENTITIES = listOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&#39;" to "'",
    "&amp;" to "&"
)

/** A feed's date as epoch millis, or zero. RSS uses RFC 1123 with a three-letter zone. */
internal fun parseRssDate(value: String): Long {
    if (value.isBlank()) return 0L
    for (format in RSS_DATE_FORMATS) {
        runCatching {
            val parsed = java.text.SimpleDateFormat(format, Locale.US)
                .apply { isLenient = true; timeZone = java.util.TimeZone.getTimeZone("GMT") }
                .parse(value.trim())
            if (parsed != null) return parsed.time
        }
    }
    return 0L
}

private val RSS_DATE_FORMATS = listOf(
    "EEE, dd MMM yyyy HH:mm:ss zzz",
    "EEE, dd MMM yyyy HH:mm:ss Z",
    "dd MMM yyyy HH:mm:ss zzz",
    "yyyy-MM-dd'T'HH:mm:ssXXX"
)

/** `1.4 GB`, `700 MiB`, `1024` - the human sizes feeds use. */
internal fun parseSize(value: String): Long {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return 0L
    trimmed.toDoubleOrNull()?.let { return it.toLong() }
    val match = SIZE_PATTERN.matchEntire(trimmed) ?: return 0L
    val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
    val unit = match.groupValues[2].lowercase(Locale.US)
    val multiplier = SIZE_UNITS[unit] ?: return 0L
    return (number * multiplier).toLong()
}

private val SIZE_PATTERN = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?i?B)""", RegexOption.IGNORE_CASE)

private val SIZE_UNITS = mapOf(
    "b" to 1L,
    "kb" to 1_000L, "mb" to 1_000_000L, "gb" to 1_000_000_000L, "tb" to 1_000_000_000_000L,
    "kib" to 1_024L, "mib" to 1_048_576L, "gib" to 1_073_741_824L, "tib" to 1_099_511_627_776L
)

// --- fetching -----------------------------------------------------------------------

/**
 * Fetches [url] as text, or throws.
 *
 * A source that cannot be reached throws rather than returning nothing, because the two are
 * different answers: "this site has nothing for that query" and "this site is down" have to
 * be reported differently or a dead source looks like an empty one.
 */
internal suspend fun fetchText(
    url: String,
    userAgent: String = SEARCH_USER_AGENT,
    connectTimeoutMillis: Int = 12_000,
    readTimeoutMillis: Int = 20_000
): String = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = connectTimeoutMillis
        readTimeout = readTimeoutMillis
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "application/json, application/rss+xml, text/xml, */*")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        instanceFollowRedirects = true
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("HTTP $code")
        connection.inputStream.use { readAll(it) }
    } finally {
        connection.disconnect()
    }
}

private fun readAll(stream: InputStream): String =
    stream.bufferedReader(Charsets.UTF_8).readText()

/**
 * A browser's user agent.
 *
 * Several of these feeds answer a bare request with a 403 or an interstitial, so the app
 * has to look like something that reads feeds rather than like a script.
 */
internal const val SEARCH_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Safari/537.36"
