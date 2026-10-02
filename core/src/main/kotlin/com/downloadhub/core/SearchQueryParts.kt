package com.downloadhub.core

import java.util.Locale

/**
 * A search query taken apart into what identifies the thing and what is wanted of it.
 *
 * "Dune 2021 2160p x265 DTS-HD 5.1 Zendaya" is two different questions wearing one
 * sentence. *Dune 2021* says which film. *2160p x265 DTS-HD 5.1* says what sort of
 * copy of it. *Zendaya* is a person in it, and no filename in any index carries that
 * - the name of a file is the name of a release, not its cast.
 *
 * Sending all of it to an index asks the wrong question. Every index ANDs its tokens
 * or phrases them, so a query carrying a cast member and a codec comes back with
 * nothing - and the film is right there under "Dune". So the two are separated, the
 * title is what goes to the index, and the rest is applied here, to what came back.
 *
 * Quality terms are then a *preference* rather than a *requirement*. Asking for 2160p
 * and being shown 1080p is a reasonable thing to do - the person can see it, and it
 * ranks below the 2160p - while refusing to show it would mean a search for
 * "Dune 2160p" returns nothing on a machine that could not fetch 4K anyway, which is
 * a worse answer than one that is not quite what was asked for.
 */
data class QueryParts(
    /** What the thing is. This is what an index is asked. */
    val title: String,
    /** What sort of copy is wanted. Applied to the answers, never asked about. */
    val quality: List<String>,
    /** True when nothing was left of the title and the whole query is a specification. */
    val titleIsEmpty: Boolean = false
) {
    /** The query as it should be put to an index. */
    val toAsk: String get() = if (titleIsEmpty) quality.joinToString(" ") else title
}

/**
 * Splits [query] into the part that identifies something and the part that describes
 * the copy wanted.
 *
 * A word is technical when it is something a release is *labelled* rather than
 * *named*. Those two are kept apart deliberately, because a list built by guessing at
 * technical English swallows real titles: `blade` names a film, `runner` is a word in
 * one, and `The Raid 2` has a number in it that identifies the sequel.
 */
fun splitQuery(query: String): QueryParts {
    // Whitespace first, and only then punctuation.
    //
    // The order is the whole trick. `DTS-HD.5.1` is one thing a person wrote, and the
    // audio specification inside it is `5.1` - a number and a decimal point. Split on
    // punctuation straight away and that arrives as `5` and `1`, two digits that
    // identify nothing, and the only way to reassemble them is a rule that swallows
    // every short number. That rule eats "The Raid 2", which is a film.
    //
    // So a channel expression is recognised while the token is still whole, and only
    // what is left over is broken into words.
    val tokens = query.trim().split(' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return QueryParts("", emptyList(), titleIsEmpty = true)

    val technical = mutableListOf<String>()
    val identifying = mutableListOf<String>()
    for (token in tokens) {
        val lower = token.lowercase(Locale.US)
        if (lower.isQualityTerm() || lower in TECHNICAL_WORDS) {
            // Kept whole. A hyphenated or dotted release tag is one label and half of
            // it means nothing: `dts` and `hd` are in the word list so that `dts-hd` is
            // recognised, but holding the token together is what lets a later check see
            // that a result carries the audio the query asked for.
            technical += token
            continue
        }
        token.split(SPLIT_ON).map { it.trim() }.filter { it.isNotEmpty() }.forEach { word ->
            val w = word.lowercase(Locale.US)
            if (w.isQualityTerm() || w in TECHNICAL_WORDS) technical += word else identifying += word
        }
    }

    // A query that is nothing but specifications - "1080p", "dts 5.1" - has no title
    // to ask about, and pretending otherwise would send the codecs to the index and
    // throw the result away for being unrelated to them.
    if (identifying.isEmpty()) {
        return QueryParts("", technical, titleIsEmpty = true)
    }
    return QueryParts(identifying.joinToString(" "), technical)
}

/**
 * Whether this word describes a release rather than naming one.
 *
 * Three ways to be one, because the words arrive in three shapes. Whole words -
 * `bluray`, `repack`, `proper`, `subs`. Numbered things - `1080p`, `2160p`, `x265`,
 * `h264`, `4k`. And audio channels, which are not words at all: `5.1`, `7.1`, `2.0`,
 * `dd5.1`.
 */
internal fun String.isQualityTerm(): Boolean {
    val value = lowercase(Locale.US)
    if (value in TECHNICAL_WORDS) return true
    if (value.matches(RESOLUTION)) return true
    if (value.matches(CODEC)) return true
    if (value.matches(CHANNELS)) return true
    // Joined forms, because a query is written the way a person writes it and the
    // punctuation is judged before it is split. "web-dl" is two words once split, "web"
    // and "dl", and "dl" on its own is not a label - so a term is technical if any part
    // of it is.
    return value.contains('-') && value.split('-').any { it.isQualityTerm() }
}

/** 480p, 576p, 720p, 1080p, 2160p, and the bare 4k/8k some releases use instead. */
private val RESOLUTION = Regex("""(?:[248]?k|480p|540p|576p|720p|900p|1080p|1440p|2160p|4320p)""")

/** x264, x265, h264, h265, hevc, avc, xvid, divx, 10bit, 8bit. */
private val CODEC = Regex("""(?:x|h|he|av)x?26[45]|hevc|avc|xvid|divx|10bits?|8bits?""")

/** 2.0, 5.1, 7.1, 6.1, and the prefixed forms some releases use. */
private val CHANNELS = Regex("""(?:\d\.\d|dd\d\.\d|ac3\d\.\d|eac3\d\.\d|atmos)""")

/**
 * Anything that is not a letter or a digit ends a word.
 *
 * Its own copy of the rule rather than the one in the matcher, because that one is
 * private to its file and this is a separate question.
 */
private val SPLIT_ON = Regex("[^\\p{L}\\p{N}]+")

/**
 * Words that label a release.
 *
 * `hd` is in here because it is the half of `dts-hd` and `true-hd` that arrives alone
 * once the punctuation is split, and because a great many releases carry it alone.
 * `proper`, `repack` and `internal` are here because they are how a release says it is
 * the one worth having, and a person typing them means them.
 */
private val TECHNICAL_WORDS = setOf(
    // containers and sources
    "bluray", "blu", "brrip", "dvd", "dvdrip", "dvd5", "dvd9", "webrip", "webdl",
    "web", "hdtv", "pdtv", "hdrip", "remux", "cam", "camrip", "screener", "ts",
    "hc", "korsub", "vhs", "uhdtv",
    // audio
    "dts", "dtshd", "truehd", "atmos", "ac3", "eac3", "aac", "flac", "opus", "mp3",
    "dd", "ddp", "ddplus", "lpcm", "pcm", "master", "hd", "hq", "clean", "telugu",
    "dubbed", "dual", "multi", "subs", "subbed", "subpack",
    // picture and extras
    "hdr", "hdr10", "dv", "sdr", "hlg", "remastered", "imax", "proper", "repack",
    "internal", "limited", "extended", "unrated", "uncut", "extras",
    // streaming
    "amzn", "nf", "hmax", "hulu", "disney", "atvp", "pcok", "dsnp", "max"
)
