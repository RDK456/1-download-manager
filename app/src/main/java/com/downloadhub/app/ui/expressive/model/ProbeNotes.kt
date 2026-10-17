package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.ui.formatBytes

/** What a quick look at a link found, before it is queued. All fields are best effort. */
data class Probe(
    val code: Int,
    val contentLength: Long,
    val contentType: String?,
    val acceptsRanges: Boolean,
    val finalUrl: String?
)

enum class NoteLevel { INFO, WARN, ERROR }

/** One finding shown in the add sheet. Never blocks adding: the user knows their server. */
data class AddNote(val level: NoteLevel, val title: String, val body: String)

private const val GB4 = 4_294_967_295L
private const val RESERVE = 100_000_000L
private const val RESUME_WORTH_MENTIONING = 100_000_000L

private val PAGE_EXTENSIONS = setOf("html", "htm", "php", "asp", "aspx", "jsp")
private val LOGIN_WORDS = listOf("login", "signin", "sign-in", "sign_in", "auth", "sso", "accounts")

/**
 * Turns a probe into the notes worth showing. [probe] is null when it could not be taken
 * (no connection, a timeout), which is itself worth one line. [freeBytes] below zero means
 * unknown. [removableTarget] is true when the folder is on a memory card, which may be
 * FAT32 and unable to hold a file over 4 GB.
 */
fun interpretProbe(probe: Probe?, originalUrl: String, freeBytes: Long, removableTarget: Boolean): List<AddNote> {
    if (probe == null) {
        return listOf(AddNote(NoteLevel.INFO, "Could not check this link", "You can still add it. It is tried when the connection allows."))
    }
    val notes = mutableListOf<AddNote>()
    when (probe.code) {
        404 -> notes += AddNote(NoteLevel.ERROR, "File not found (404)", "The server says nothing is at this address. Check the link.")
        410 -> notes += AddNote(NoteLevel.ERROR, "Link expired (410)", "This link has been taken down. Get a new one from the page it came from.")
        401, 403 -> notes += AddNote(NoteLevel.WARN, "The server may refuse this (${probe.code})", "It might need you to sign in. You can still try, and add headers, cookies or a login below.")
    }
    val extension = originalUrl.substringBefore('?').substringBefore('#').substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
    val isHtml = probe.contentType?.lowercase()?.startsWith("text/html") == true
    // A page where the address promised a file (setup.exe answered with HTML) is almost always
    // a redirect to a sign-in or consent page. A bare address answered with HTML is just a page.
    val promisedAFile = extension.isNotEmpty() && extension !in PAGE_EXTENSIONS
    if (isHtml && (promisedAFile || extension.isEmpty())) {
        val toLogin = probe.finalUrl?.lowercase()?.let { u -> LOGIN_WORDS.any { it in u } } == true
        notes += if (toLogin) {
            AddNote(NoteLevel.WARN, "This link needs you to sign in", "It leads to a sign-in page, not a file. Open it in your browser first, or add your cookies below.")
        } else {
            AddNote(NoteLevel.WARN, "This looks like a web page", "It is a page, not a file. Use \"Check page for media\" to find downloads on it.")
        }
    }
    if (probe.contentLength > 0 && freeBytes >= 0 && probe.contentLength + RESERVE > freeBytes) {
        notes += AddNote(
            NoteLevel.ERROR,
            "Not enough space",
            "It needs ${formatBytes(probe.contentLength)} and ${formatBytes(freeBytes)} is free. Free some space or choose another folder."
        )
    }
    if (probe.contentLength > GB4 && removableTarget) {
        notes += AddNote(
            NoteLevel.WARN,
            "May be too big for this folder",
            "${formatBytes(probe.contentLength)} is over 4 GB, and memory cards are often formatted so they cannot hold a file that large. Choose internal storage to be safe."
        )
    }
    if (!probe.acceptsRanges && probe.contentLength >= RESUME_WORTH_MENTIONING && probe.code in 200..299) {
        notes += AddNote(NoteLevel.INFO, "Cannot be resumed", "If this is interrupted it starts again from the beginning.")
    }
    return notes
}
