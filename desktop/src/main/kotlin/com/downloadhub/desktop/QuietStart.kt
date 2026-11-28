package com.downloadhub.desktop

import com.downloadhub.core.DownloadStatus

/**
 * A start straight to the tray loads no window toolkit until something needs it.
 *
 * Compose and its graphics library are most of what the app reads and runs at launch, and at
 * sign-in nobody is looking. Downloads, browser capture, queues and the tray work without
 * them; the first reason to show or report something brings them in.
 */
object QuietStart {
    /** How long the housekeeping of a quiet start waits, so sign-in has finished with the disk. */
    const val HOUSEKEEPING_DELAY_MILLIS = 90_000L

    private val showFile get() = java.io.File(AppPaths.home, "show.request")

    /** Left by a second copy the user started, for the copy that owns the app. */
    fun askToShow(file: java.io.File = showFile) {
        runCatching { file.writeText("show") }
    }

    fun takeShowRequest(file: java.io.File = showFile): Boolean = file.isFile && file.delete()

    /**
     * True once the window layer has work to do: the user asked for the window, Windows handed
     * over a link, or a download is moving (finish notices and "when done" live in that layer).
     */
    fun needsUi(opened: Boolean, hasIntake: Boolean, statuses: List<DownloadStatus>): Boolean =
        opened || hasIntake || statuses.any { it == DownloadStatus.RUNNING || it == DownloadStatus.RESOLVING }
}
