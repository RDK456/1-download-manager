package com.downloadhub.app.ui.expressive.model

/**
 * Deletes that have been asked for but not yet carried out, so "Delete" can offer Undo.
 *
 * A row put here disappears from the list at once; the files go only when its window runs
 * out. If the app is closed inside the window nothing was removed, which is the safe way
 * to fail. Pure and clock-injected so the timing is unit tested.
 */
class PendingDeletes(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val windowMillis: Long = WINDOW_MS
) {
    private val dueAt = LinkedHashMap<String, Long>()

    fun add(ids: Collection<String>) {
        val due = now() + windowMillis
        ids.forEach { dueAt[it] = due }
    }

    /** Takes the ids back. Returns the ones that were really pending (late undos do nothing). */
    fun undo(ids: Collection<String>): List<String> = ids.filter { dueAt.remove(it) != null }

    /** Removes and returns every id whose window has run out. */
    fun takeDue(): List<String> {
        val t = now()
        val due = dueAt.filterValues { it <= t }.keys.toList()
        due.forEach { dueAt.remove(it) }
        return due
    }

    /** Everything still waiting, to hide from the list. */
    fun ids(): Set<String> = dueAt.keys.toSet()

    companion object {
        /**
         * Longer than the "Deleted" message stays on screen (ten seconds for a message with a
         * button), so Undo is never offered after the files are already gone.
         */
        const val WINDOW_MS = 12_000L
    }
}
