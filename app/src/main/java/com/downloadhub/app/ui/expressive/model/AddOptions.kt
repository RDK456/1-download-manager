package com.downloadhub.app.ui.expressive.model

import com.downloadhub.app.data.AppQueue

/** The main queue's id; a download goes there unless another is chosen. */
const val MAIN_QUEUE_ID = "main"

/**
 * How a download is added beyond the link itself: held back (paused) or put in a named
 * queue. The default is the main queue, started at once.
 */
data class AddOptions(val startPaused: Boolean = false, val queueId: String? = null) {
    val isDefault: Boolean get() = !startPaused && (queueId == null || queueId == MAIN_QUEUE_ID)
}

/** The choices behind the split button's arrow, in order. */
sealed interface AddChoice {
    val options: AddOptions

    data object Paused : AddChoice {
        override val options = AddOptions(startPaused = true)
    }

    data class ToQueue(val queue: AppQueue) : AddChoice {
        override val options = AddOptions(queueId = queue.id)
    }
}

/** "Add paused", then one entry per queue other than the main one. */
fun addChoices(queues: List<AppQueue>): List<AddChoice> =
    listOf(AddChoice.Paused) + queues.filter { it.id != MAIN_QUEUE_ID }.map { AddChoice.ToQueue(it) }

/** What the choice says on the menu. */
fun AddChoice.label(): String = when (this) {
    AddChoice.Paused -> "Add paused"
    is AddChoice.ToQueue -> "Add to ${queue.name}"
}

/** What the main button says, so what it is about to do is never a surprise. */
fun primaryAddLabel(count: Int, options: AddOptions, queues: List<AppQueue>): String {
    val queue = options.queueId?.takeIf { it != MAIN_QUEUE_ID }?.let { id -> queues.firstOrNull { it.id == id }?.name }
    val what = if (count > 1) "$count" else ""
    return when {
        options.startPaused -> if (count > 1) "Add $what paused" else "Add paused"
        queue != null -> if (count > 1) "Add $what to $queue" else "Add to $queue"
        else -> if (count > 1) "Add $what to queue" else "Add to queue"
    }
}
