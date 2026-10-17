package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.data.model.DownloadStatus

/**
 * Which shape a download's leading badge takes. Kept free of Compose and of the
 * experimental shape library so the mapping can be unit tested; `StateShape` turns a kind
 * into a real polygon.
 *
 * The shape is the first thing the eye reads in a long list, so each state gets its own
 * silhouette instead of relying on colour alone.
 */
enum class StateShapeKind {
    /** Transferring. The only kind that turns. */
    COOKIE,

    /** Queued, resolving, or waiting for the network or space. At rest. */
    CIRCLE,

    /** Paused by the user. */
    SQUIRCLE,

    /** Finished. */
    BURST,

    /** Failed, or needs attention (for example a torrent with no seeds). */
    PENTAGON
}

/**
 * [waiting] is true when the row would be running but something outside it holds it:
 * no connection, or not enough space. Those rows look queued, not failed, because nothing
 * is wrong with the download itself.
 */
fun shapeKindFor(status: DownloadStatus, waiting: Boolean = false, needsAttention: Boolean = false): StateShapeKind = when {
    status == DownloadStatus.FAILED || needsAttention -> StateShapeKind.PENTAGON
    status == DownloadStatus.COMPLETED -> StateShapeKind.BURST
    status == DownloadStatus.PAUSED -> StateShapeKind.SQUIRCLE
    waiting -> StateShapeKind.CIRCLE
    status == DownloadStatus.RUNNING -> StateShapeKind.COOKIE
    else -> StateShapeKind.CIRCLE
}

/** Which colour family a badge uses, so a state also reads from the colour, not the outline alone. */
enum class BadgeTone { ACTIVE, REST, DONE, ERROR }

val StateShapeKind.tone: BadgeTone
    get() = when (this) {
        StateShapeKind.COOKIE -> BadgeTone.ACTIVE
        StateShapeKind.CIRCLE, StateShapeKind.SQUIRCLE -> BadgeTone.REST
        StateShapeKind.BURST -> BadgeTone.DONE
        StateShapeKind.PENTAGON -> BadgeTone.ERROR
    }

/** Only a transferring row turns. Capped by the list so a long queue stays calm. */
val StateShapeKind.spins: Boolean get() = this == StateShapeKind.COOKIE
