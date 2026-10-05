package com.downloadhub.core

import java.io.File
import org.libtorrent4j.TorrentBuilder

/** qBittorrent's Torrent Creator: makes a .torrent from a file or folder on this machine. */
object TorrentCreator {

    data class Request(
        val source: File,
        /** One per line; a blank line starts the next tier, as in qBittorrent. */
        val trackersText: String = "",
        /** HTTP sources that hold the same files. One per line. */
        val webSeedsText: String = "",
        val comment: String = "",
        val private: Boolean = false
    )

    /** Announce URLs with their tier, from qBittorrent's tracker box layout. */
    fun trackerTiers(text: String): List<Pair<String, Int>> {
        var tier = 0
        var tierHasEntries = false
        val result = mutableListOf<Pair<String, Int>>()
        text.lines().map { it.trim() }.forEach { line ->
            if (line.isEmpty()) {
                if (tierHasEntries) {
                    tier++
                    tierHasEntries = false
                }
            } else {
                result += line to tier
                tierHasEntries = true
            }
        }
        return result
    }

    /** The bencoded .torrent. Slow for large sources: it hashes every byte. */
    fun create(request: Request, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ByteArray {
        require(request.source.exists()) { "Nothing to make a torrent of at ${request.source}" }
        LibtorrentNative.ensureReady()
        val builder = TorrentBuilder()
            .path(request.source)
            // v1 only: libtorrent's default hybrid v1+v2 torrent inserts padding files
            // that older clients show as junk files, and v1 is what every client reads.
            .flags(TorrentBuilder.V1_ONLY)
            .creator("1 download manager")
            .setPrivate(request.private)
            .listener(object : TorrentBuilder.Listener {
                override fun accept(filename: String): Boolean = true
                override fun progress(piece: Int, total: Int) = onProgress(piece, total)
            })
        if (request.comment.isNotBlank()) builder.comment(request.comment.trim())
        trackerTiers(request.trackersText).forEach { (url, tier) -> builder.addTracker(url, tier) }
        request.webSeedsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { builder.addUrlSeed(it) }
        return builder.generate().entry().bencode()
    }
}
