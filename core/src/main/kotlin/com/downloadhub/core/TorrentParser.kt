package com.downloadhub.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * What a `.torrent` file says about itself.
 *
 * Read before anything is queued so the file list can be shown and individual files left
 * out. That is the whole reason this exists: a torrent is a container, and queueing one
 * unexamined downloads everything in it, including the parts nobody wanted.
 *
 * The parser is hand-written rather than delegated to libtorrent because it has to work
 * before the native library is loaded, on a file the user just dropped on the window. It
 * also makes it testable without a session, which a parser reached through libtorrent
 * would not be.
 */
data class TorrentMetainfo(
    /** The torrent's own name; the folder name for a multi-file torrent. */
    val name: String,
    /** Files, in the order the torrent lists them. */
    val files: List<TorrentFile>,
    /** The torrent's own comment, or blank. */
    val comment: String,
    /** Creation date in epoch millis, or 0 when the torrent carries no date. */
    val createdAtEpochMillis: Long,
    /** Who made it, or blank. */
    val createdBy: String,
    /** SHA-1 of the info dictionary. Present for every v1 torrent. */
    val infoHashV1: String,
    /** SHA-256 of the info dictionary. Present only for hybrid and v2 torrents. */
    val infoHashV2: String,
    /** True when the torrent lists one file rather than a set of them. */
    val isSingleFile: Boolean
) {
    /** Sum of every file's size. */
    val totalSize: Long get() = files.sumOf { it.size }

    /** The hash to show, preferring v2 because a hybrid torrent has both. */
    val preferredInfoHash: String get() = infoHashV2.ifBlank { infoHashV1 }

    /** Piece length in bytes, for the information block. */
    val pieceLength: Long get() = files.firstOrNull()?.pieceLength ?: 0L

    /** How many pieces the torrent is cut into, or 0 when it says nothing. */
    val pieceCount: Int get() = files.firstOrNull()?.pieceCount ?: 0

    /**
     * An empty metainfo, for a magnet or an ordinary link.
     *
     * The pre-download dialog is offered for every kind of download now, and a magnet
     * genuinely has no file list until peers answer. Rather than making the caller
     * special-case it, there is a real "nothing is known" value - so the dialog can show
     * one row describing what will arrive and say why.
     */
    companion object {
        fun empty(name: String) = TorrentMetainfo(
            name = name,
            files = emptyList(),
            comment = "",
            createdAtEpochMillis = 0L,
            createdBy = "",
            infoHashV1 = "",
            infoHashV2 = "",
            isSingleFile = true
        )
    }
}

/** One file inside a torrent. */
data class TorrentFile(
    /** Zero-based position in the torrent's file list. */
    val index: Int,
    /** Path within the torrent, using `/` even on Windows. */
    val path: String,
    val size: Long,
    val pieceLength: Long = 0L,
    val pieceCount: Int = 0
) {
    /** The last path segment, which is what the file list shows. */
    val name: String get() = path.substringAfterLast('/')
}

/** Why a `.torrent` could not be read. */
class TorrentParseException(message: String) : Exception(message)

/**
 * Reads `.torrent` files.
 *
 * A `.torrent` is bencode - a tiny binary format: `i4e` is 4, `4:name` is "name", `l..e`
 * is a list and `d..e` is a dictionary. That is all of it, and it is enough to list the
 * files and hash the info dictionary.
 */
object TorrentParser {

    /**
     * Reads a torrent from disk.
     *
     * @throws TorrentParseException when the file is not a torrent. That is worth telling
     * apart from "a torrent with nothing in it": one is a bad file, the other is a
     * perfectly good single-file torrent.
     */
    fun parse(file: File): TorrentMetainfo = try {
        parse(file.readBytes())
    } catch (e: TorrentParseException) {
        throw e
    } catch (e: Exception) {
        throw TorrentParseException("Cannot read ${file.name}: ${e.message ?: "not a torrent file"}")
    }

    /** Reads a torrent from bytes already in hand. */
    fun parse(bytes: ByteArray): TorrentMetainfo {
        val reader = BencodeReader(bytes)
        val root = reader.readValue(0).asMap()
            ?: throw TorrentParseException("This file is not a torrent (it is not a dictionary)")
        val infoEntry = root["info"]
            ?: throw TorrentParseException("This file has no torrent information in it")
        val info = infoEntry.asMap()
            ?: throw TorrentParseException("The torrent information is malformed")

        val infoHashes = Hashes.of(infoEntry.raw)
        val name = info["name"]?.asText()?.trim().orEmpty().ifBlank { "Unnamed torrent" }
        val torrentPieceLength = info["piece length"]?.asLong() ?: 0L
        val totalPieces = info["pieces"]?.asText()?.length?.div(20) ?: 0

        // A v2 torrent describes a file tree rather than a flat list. Flattening both into
        // one shape is what lets a single file list cover either kind.
        val files = buildList {
            val single = info["length"]?.asLong()
            if (single != null) {
                add(
                    TorrentFile(
                        index = 0,
                        path = name,
                        size = single,
                        pieceLength = torrentPieceLength,
                        pieceCount = totalPieces
                    )
                )
            } else {
                info["files"]?.asList().orEmpty().forEachIndexed { position, entry ->
                    val fileMap = entry.asMap() ?: return@forEachIndexed
                    val entryName = fileMap["path"]?.asTextList()?.joinToString("/").orEmpty()
                    if (entryName.isBlank()) return@forEachIndexed
                    add(
                        TorrentFile(
                            index = position,
                            path = entryName,
                            size = fileMap["length"]?.asLong() ?: 0L,
                            pieceLength = fileMap["piece length"]?.asLong() ?: torrentPieceLength,
                            pieceCount = fileMap["pieces"]?.asText()?.length?.div(20) ?: totalPieces
                        )
                    )
                }
                // A v2 torrent lists a tree instead, keyed by name with "" for folders.
                // Left unflattened it looks like a torrent with no files at all, and the
                // dialog would offer to download nothing.
                info["file tree"]?.asMap()?.let { tree ->
                    addAll(flattenTree(tree, prefix = "", next = { size }))
                }
            }
        }

        return TorrentMetainfo(
            name = name,
            files = files,
            comment = info["comment"]?.asText()?.trim().orEmpty(),
            createdAtEpochMillis = (info["creation date"]?.asLong() ?: 0L).times(1000L),
            createdBy = info["created by"]?.asText()?.trim().orEmpty(),
            infoHashV1 = infoHashes.sha1,
            infoHashV2 = infoHashes.sha256,
            isSingleFile = info.containsKey("length")
        )
    }

    /**
     * Flattens a v2 `file tree` into ordinary paths.
     *
     * Each key is one path segment. A leaf carries `length`; a folder has none and is a
     * key in its own right. The empty key is the torrent's own name, so it is skipped
     * rather than producing a leading slash on every path.
     */
    private fun flattenTree(
        node: Map<String, Entry>,
        prefix: String,
        next: () -> Int
    ): List<TorrentFile> = buildList {
        node.forEach { (segment, entry) ->
            val child = entry.asMap() ?: return@forEach
            val path = if (prefix.isEmpty()) segment else "$prefix/$segment"
            if (child["length"] != null) {
                add(
                    TorrentFile(
                        index = next(),
                        path = if (segment.isEmpty()) prefix else path,
                        size = child["length"]?.asLong() ?: 0L,
                        pieceLength = child["piece length"]?.asLong() ?: 0L,
                        pieceCount = child["pieces"]?.asText()?.length?.div(20) ?: 0
                    )
                )
            } else if (segment.isNotEmpty()) {
                addAll(flattenTree(child, path, next))
            }
        }
    }

    /**
     * True when the file really is a torrent, for drag-and-drop and file picking.
     *
     * The extension is not enough on its own: what users actually drop on a download
     * manager is often a `.torrent` that turns out to be an HTML error page, and that has
     * to be refused rather than read as a torrent with no files.
     */
    fun looksLikeTorrent(file: File): Boolean {
        if (!file.isFile || file.length() < 32) return false
        return runCatching {
            file.inputStream().use { it.readHead() }.firstOrNull() == 'd'.code.toByte()
        }.getOrDefault(false)
    }

    private fun InputStream.readHead(): ByteArray {
        val buffer = ByteArray(16)
        var read = 0
        while (read < buffer.size) {
            val step = read(buffer, read, buffer.size - read)
            if (step <= 0) break
            read += step
        }
        return buffer.copyOf(read)
    }

    /** The two info hashes of one info dictionary. */
    private data class Hashes(val sha1: String, val sha256: String) {
        companion object {
            fun of(raw: ByteArray) = Hashes(
                MessageDigest.getInstance("SHA-1").digest(raw).toHex(),
                MessageDigest.getInstance("SHA-256").digest(raw).toHex()
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    /**
     * One decoded bencode value, as a window into the original bytes.
     *
     * Holding offsets rather than copies keeps the raw bytes exactly as written, which is
     * what makes the info hashes correct: a hash is over the dictionary as it appears in
     * the file, and re-encoding it would sort the keys again.
     */
    class Entry internal constructor(
        private val bytes: ByteArray,
        internal val start: Int,
        internal val end: Int,
        private val reader: BencodeReader
    ) {
        /** The value exactly as it appears in the file. */
        val raw: ByteArray get() = bytes.copyOfRange(start, end)

        private val value: Any? by lazy { reader.valueOf(start, end) }

        fun asMap(): Map<String, Entry>? = value as? Map<String, Entry>

        fun asList(): List<Entry>? = value as? List<Entry>

        fun asText(): String? = when (val decoded = value) {
            is String -> decoded
            is Long -> decoded.toString()
            else -> null
        }

        fun asTextList(): List<String> =
            (value as? List<Entry>)?.mapNotNull { it.asText() }.orEmpty()

        fun asLong(): Long? = when (val decoded = value) {
            is Long -> decoded
            is String -> decoded.toLongOrNull()
            else -> null
        }

        override fun toString() = "Entry($start..$end)"
    }

    /** Walks bencode. */
    internal class BencodeReader(private val bytes: ByteArray) {

        fun readValue(start: Int): Entry = Entry(bytes, start, endOf(start), this)

        /**
         * Where the value starting at [start] ends.
         *
         * Containers are walked rather than scanned for a closing `e`, because a `d` can
         * hold a string containing the letter `e` - `8:filename` - and a scan finds that
         * one and cuts the dictionary in half.
         */
        fun endOf(start: Int): Int = when (markerAt(start)) {
            'i' -> indexOf('e', start) + 1
            'l', 'd' -> endOfContainer(start)
            else -> {
                val colon = indexOf(':', start)
                // The digits run from `start` up to the colon, exclusive.
                val length = String(bytes, start, colon - start).toIntOrNull()
                    ?: throw TorrentParseException("The torrent file has a malformed string length")
                if (length < 0) throw TorrentParseException("The torrent file has a negative length")
                val end = colon + 1 + length
                if (end > bytes.size) throw TorrentParseException("The torrent file is truncated")
                end
            }
        }

        /** Walks a list or dictionary to its matching `e`. */
        private fun endOfContainer(start: Int): Int {
            var position = start + 1
            while (true) {
                if (position >= bytes.size) throw TorrentParseException("The torrent file is truncated")
                val marker = markerAt(position)
                if (marker == 'e') return position + 1
                // A dictionary is key then value, so every other value gets two steps.
                position = endOf(position)
                if (markerAt(start) == 'd' && markerAt(position) != 'e') {
                    position = endOf(position)
                }
            }
        }

        internal fun valueOf(start: Int, end: Int): Any? = when (markerAt(start)) {
            'i' -> parseLong(String(bytes, start + 1, end - start - 2))
            'd' -> decodeDictionary(start)
            'l' -> decodeList(start)
            else -> {
                val colon = indexOf(':', start)
                val length = String(bytes, start, colon - start).toIntOrNull() ?: 0
                String(bytes, colon + 1, minOf(length, end - colon - 1))
            }
        }

        private fun decodeDictionary(start: Int): Map<String, Entry> {
            val result = LinkedHashMap<String, Entry>()
            var position = start + 1
            while (position < bytes.size && markerAt(position) != 'e') {
                // A key is itself a bencode string, so its text starts after the colon
                // and runs for the declared length - not one byte along, which would turn
                // every key into ":inf" and make the whole torrent unreadable.
                val key = readValue(position)
                val keyColon = indexOf(':', key.start)
                val text = String(bytes, keyColon + 1, key.end - keyColon - 1)
                val value = readValue(key.end)
                result[text] = value
                position = value.end
            }
            return result
        }

        private fun decodeList(start: Int): List<Entry> {
            val items = mutableListOf<Entry>()
            var position = start + 1
            while (position < bytes.size && markerAt(position) != 'e') {
                val item = readValue(position)
                items.add(item)
                position = item.end
            }
            return items
        }

        private fun markerAt(position: Int): Char {
            if (position >= bytes.size) throw TorrentParseException("The torrent file is truncated")
            return bytes[position].toInt().toChar()
        }

        private fun indexOf(character: Char, from: Int): Int {
            for (position in from until bytes.size) {
                if (bytes[position].toInt().toChar() == character) return position
            }
            throw TorrentParseException("The torrent file is truncated")
        }
    }

    private fun parseLong(text: String): Long = text.toLongOrNull() ?: 0L
}
