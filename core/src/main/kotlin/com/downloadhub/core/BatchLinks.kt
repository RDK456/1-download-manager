package com.downloadhub.core

/**
 * Many downloads from one paste, the way IDM's batch download works: a list of links
 * (one per line, or separated by spaces), and numbered ranges inside a link -
 * `https://site/img[001-120].jpg` is 120 links, `part[a-e].zip` is five. Ranges count down
 * too, and a link can hold more than one.
 *
 * Only http and https links: a magnet or a torrent wants its own look before it is queued.
 */
object BatchLinks {
    /** More than this from one paste is almost certainly a typo in a range. */
    const val MAX = 1000

    private val range = Regex("""\[(\d+)-(\d+)]|\[([a-zA-Z])-([a-zA-Z])]""")

    fun expand(text: String): List<String> =
        text.split(Regex("""\s+"""))
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .asSequence()
            .flatMap { expandRanges(it) }
            .distinct()
            .take(MAX)
            .toList()

    private fun expandRanges(link: String): Sequence<String> {
        val match = range.find(link) ?: return sequenceOf(link)
        val (fromDigits, toDigits, fromLetter, toLetter) = match.destructured
        val values: Sequence<String> = if (fromDigits.isNotEmpty()) {
            // "001" keeps three digits all the way; "1" does not pad.
            val width = if (fromDigits.length > 1 && fromDigits.startsWith('0')) fromDigits.length else 0
            val from = fromDigits.toLongOrNull() ?: return sequenceOf(link)
            val to = toDigits.toLongOrNull() ?: return sequenceOf(link)
            val step = if (to >= from) 1L else -1L
            generateSequence(from) { (it + step).takeIf { next -> if (step > 0) next <= to else next >= to } }
                .map { it.toString().padStart(width, '0') }
        } else {
            val step = if (toLetter[0] >= fromLetter[0]) 1 else -1
            generateSequence(fromLetter[0]) { (it + step).takeIf { next -> if (step > 0) next <= toLetter[0] else next >= toLetter[0] } }
                .map { it.toString() }
        }
        val before = link.substring(0, match.range.first)
        val after = link.substring(match.range.last + 1)
        // The rest of the link may hold another range; it is expanded per value, lazily,
        // so a mistyped [1-99999999] stops at MAX instead of running out of memory.
        return values.flatMap { expandRanges("$before$it$after") }
    }
}
