package com.downloadhub.app.ui.expressive.model

/**
 * A file name cut in two for display: the [head] may be shortened with an ellipsis, the
 * [tail] never is. The tail holds the end of the name and the extension, which is the part
 * that tells two long, similar names apart.
 */
data class NameParts(val head: String, val tail: String) {
    val full: String get() = head + tail
}

/**
 * Splits [name] so the last [tailChars] characters of the base name, plus the extension,
 * stay visible. A name too short to be worth cutting comes back whole in the tail.
 *
 * The cut never lands between the halves of a surrogate pair, so an emoji at the cut point
 * is kept whole rather than shown as a broken glyph.
 */
fun splitFileName(name: String, tailChars: Int = 6): NameParts {
    val dot = name.lastIndexOf('.')
    val hasExtension = dot > 0 && name.length - dot in 2..6 && name.substring(dot + 1).all { it.isLetterOrDigit() }
    val base = if (hasExtension) name.substring(0, dot) else name
    val extension = if (hasExtension) name.substring(dot) else ""
    if (base.length <= tailChars * 2) return NameParts("", name)
    var cut = base.length - tailChars
    if (cut in 1 until base.length && base[cut].isLowSurrogate() && base[cut - 1].isHighSurrogate()) cut--
    return NameParts(base.substring(0, cut), base.substring(cut) + extension)
}
