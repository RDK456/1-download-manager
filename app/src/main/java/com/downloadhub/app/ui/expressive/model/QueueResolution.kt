package com.downloadhub.app.ui.expressive.model

/**
 * The queue an add should really use. [id] is null for the main queue. [fellBack] is true when
 * the one asked for no longer exists (it was deleted while a sheet was open), so the caller can
 * say so rather than leave a download pointing at a queue nobody can see.
 */
class QueueResolution(val id: String?, val fellBack: Boolean)

fun resolveQueueId(requested: String?, existing: Collection<String>): QueueResolution = when {
    requested == null || requested == MAIN_QUEUE_ID -> QueueResolution(null, false)
    requested in existing -> QueueResolution(requested, false)
    else -> QueueResolution(null, true)
}
