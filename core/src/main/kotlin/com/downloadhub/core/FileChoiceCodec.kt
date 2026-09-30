package com.downloadhub.core

/**
 * How a torrent's per-file choices are stored in one text column.
 *
 * A download's settings live in a database row, so the file selection and the file
 * priorities need a text form. Two small encodings rather than JSON, because neither shape
 * is anything but numbers and punctuation, and a hand-rolled pair of parsers is four lines
 * each where JSON is a dependency, a schema and a failure mode to reason about at three in
 * the morning.
 *
 * Selected files: `0,1,2`. Priorities: `0:4,3:0` - index, colon, ordinal.
 *
 * Both are lenient on the way in and strict on the way out. Reading a value written by an
 * older build, or a column that was null, or a hand-edited row, must not throw: the
 * alternative is a database that cannot be opened, and a user who cannot open their app
 * cannot be told why.
 */
object FileChoiceCodec {

    private const val SEPARATOR = ","
    private const val PAIR_SEPARATOR = ":"

    /**
     * The selected file indices, smallest first.
     *
     * Sorted because the order the user ticked things in is not information - and because a
     * stable order means re-saving an unchanged selection produces an unchanged string, so
     * the row is not rewritten for nothing.
     */
    fun encodeSelected(indices: Collection<Int>): String =
        indices.filter { it >= 0 }.distinct().sorted().joinToString(SEPARATOR)

    /** The selected file indices, or empty when the column is null, empty or unusable. */
    fun decodeSelected(text: String?): Set<Int> {
        if (text.isNullOrBlank()) return emptySet()
        return text.split(SEPARATOR)
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .toSet()
    }

    /**
     * The per-file priorities, by index.
     *
     * An index whose ordinal is no longer a priority is dropped rather than defaulted,
     * because a default is indistinguishable from a value somebody chose - and "Normal" for
     * every file is the one reading that must never come out of a parse.
     */
    fun encodePriorities(priorities: Map<Int, FilePriority>): String =
        priorities.entries
            .filter { it.key >= 0 }
            .sortedBy { it.key }
            .joinToString(SEPARATOR) { "${it.key}$PAIR_SEPARATOR${it.value.ordinal}" }

    /** The per-file priorities, or empty when the column is null, empty or unusable. */
    fun decodePriorities(text: String?): Map<Int, FilePriority> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Int, FilePriority>()
        for (pair in text.split(SEPARATOR)) {
            val bits = pair.trim().split(PAIR_SEPARATOR)
            if (bits.size != 2) continue
            val index = bits[0].trim().toIntOrNull() ?: continue
            val ordinal = bits[1].trim().toIntOrNull() ?: continue
            if (index < 0) continue
            // An ordinal this build does not know about means the file was written by a
            // newer one, or edited by hand. Either way the honest reading is "not set",
            // not "Normal", because Normal is a choice the user may have made elsewhere.
            val priority = FilePriority.entries.firstOrNull { it.ordinal == ordinal } ?: continue
            out[index] = priority
        }
        return out
    }
}
