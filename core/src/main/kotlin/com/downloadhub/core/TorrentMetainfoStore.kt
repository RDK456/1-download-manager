package com.downloadhub.core

import java.io.File
import java.util.Locale

/**
 * A torrent's file list, kept beside the queue for the torrents that have no file.
 *
 * A magnet carries no file list. The list lives in the torrent's metadata, and
 * libtorrent fetches it from the swarm in a few seconds' worth of a few kilobytes
 * - which is what the pre-download dialog does before it offers you a choice of
 * files. But the metadata arrives, is used to draw the dialog, and is then
 * thrown away, because libtorrent4j exposes no way to write a magnet's metadata
 * back out as a .torrent file. So the Content tab, which reads a .torrent from
 * disk, has nothing to read for every torrent that arrived as a magnet - which is
 * every torrent found by searching, since those are all magnets.
 *
 * So the list is kept, as one small file per download, written the moment it is
 * known. Keyed by the queue item's id rather than by the info hash: the id is
 * already the thing every download is addressed by, and putting an info hash on
 * the row for this alone would mean a column, a migration and two storage
 * formats changed to save one lookup that never fails.
 *
 * Only the three things the file list needs are kept - index, path and size.
 * Piece length and count are read from the .torrent when there is one, and the
 * per-file priorities are held by the engine against live handles, so carrying
 * them here would be a second copy that could disagree with the first.
 */
object TorrentMetainfoStore {

    /** Where the file lists are kept. */
    fun directory(root: File): File = File(root, "torrents")

    private fun fileFor(root: File, id: String): File =
        File(directory(root), safeName(id) + ".files.json")

    /**
     * Writes the list, or removes what was there.
     *
     * An empty list is written as no file at all rather than as an empty one, so
     * "not known yet" and "known to have no files" cannot be confused.
     */
    fun write(root: File, id: String, meta: TorrentMetainfo?) {
        val target = fileFor(root, id)
        if (meta == null || meta.files.isEmpty()) {
            target.delete()
            return
        }
        runCatching {
            target.parentFile?.mkdirs()
            // Written to a .part and renamed, so a crash or a full disk mid-write
            // cannot leave half a file list that reads as a torrent of three files.
            val partial = File(target.parentFile, target.name + ".part")
            partial.writeText(render(meta))
            if (!partial.renameTo(target)) {
                target.writeText(partial.readText())
                partial.delete()
            }
        }
    }

    /** The list for a download, or null when none is known. */
    fun read(root: File, id: String): TorrentMetainfo? {
        val source = fileFor(root, id)
        if (!source.isFile) return null
        return parse(source.readText())
    }

    /** A queue id made safe to be a file name. */
    private fun safeName(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .take(80)
            .ifBlank { "download" }

    /**
     * Writes the JSON by hand.
     *
     * There is a JSON reader in this module and no writer, and this is four kinds
     * of value in a fixed order. Pulling in a serialiser to emit them would add a
     * dependency to a module that has one on purpose.
     */
    private fun render(meta: TorrentMetainfo): String = buildString {
        append("{\"name\":").append(quote(meta.name)).append(",\"files\":[")
        meta.files.forEachIndexed { position, file ->
            if (position > 0) append(',')
            append("{\"i\":").append(file.index)
            append(",\"p\":").append(quote(file.path))
            append(",\"s\":").append(file.size)
            append('}')
        }
        append("]}")
    }

    /** Reads what [render] wrote, tolerating a file written by an older build. */
    private fun parse(text: String): TorrentMetainfo? = runCatching {
        val root = parseJson(text)
        val files = root.array("files").mapNotNull { row ->
            val index = row.number("i").toInt()
            val path = row.string("p")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TorrentFile(
                index = index,
                path = path,
                size = row.number("s"),
                pieceLength = 0L,
                pieceCount = 0
            )
        }
        if (files.isEmpty()) return null
        TorrentMetainfo(
            name = root.string("name")?.takeIf { it.isNotBlank() } ?: files.first().name,
            files = files,
            comment = "",
            createdAtEpochMillis = 0L,
            createdBy = "",
            infoHashV1 = "",
            infoHashV2 = "",
            isSingleFile = files.size <= 1
        )
    }.getOrNull()

    /** A JSON string literal. Only the two characters below need escaping here. */
    private fun quote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        value.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").append(String.format(Locale.US, "%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
