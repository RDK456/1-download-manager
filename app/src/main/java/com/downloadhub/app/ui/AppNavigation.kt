package com.downloadhub.app.ui

/** Every screen the app can show. */
enum class AppDestination {
    DOWNLOADS,
    TORRENTS,
    /**
     * Find something to download.
     *
     * A root of its own rather than a sub-page of Downloads: it does not narrow what is
     * already downloaded, it replaces the list with a box to type in, and back from it
     * should return to the list the user came from rather than walking a stack.
     */
    SEARCH,
    /**
     * A pasted YouTube link, listed as downloadables.
     *
     * A root beside Search: found versus pasted, the two ways new things
     * arrive. It replaces the list rather than narrowing it, because a
     * playlist is not in the queue yet and no filter could show it.
     */
    YOUTUBE,
    SETTINGS,
    DOWNLOAD_SETTINGS,
    THEMES,
    /** Named queues and their start/stop schedules. */
    QUEUES,
    /** Categories, proxy, speed and BitTorrent settings, IP filter. */
    ADVANCED,
    /** RSS feeds and their auto-download rules. */
    RSS,
    /** Free books, free TV and the player, one tap from the bottom bar. */
    DISCOVER,
    BOOKS,
    MOVIES,
    MUSIC,
    PLAYER,
    TV,
    /** The whole Internet Archive, any media type. */
    ARCHIVE,
    ABOUT;

    /** The list tabs, search and YouTube are roots; everything else is a sub-page. */
    val isRoot: Boolean get() = this == DOWNLOADS || this == TORRENTS || this == SEARCH || this == YOUTUBE || this == DISCOVER

    /** Pages that have nothing to do with the download list: no filter, no add button. */
    val isDiscover: Boolean get() = this == DISCOVER || this == BOOKS || this == MOVIES || this == MUSIC || this == PLAYER || this == TV || this == ARCHIVE
}

/** Guard against unbounded growth if a sub-page ever re-navigates to itself. */
private const val MAX_BACK_STACK = 16

/**
 * Navigation state: one root tab plus a stack of sub-pages.
 *
 * Modelled as a real stack rather than a single "current page" value so that back
 * walks the chain the user actually came from - Themes returns to Settings,
 * Settings returns to the list tab - instead of closing the app from a sub-page.
 */
data class NavStack(
    val root: AppDestination = AppDestination.DOWNLOADS,
    val entries: List<AppDestination> = emptyList()
) {
    /** The screen being shown: the top of the stack, or the root tab. */
    val current: AppDestination get() = entries.lastOrNull() ?: root

    /** False on a root tab, which is where back asks before leaving the app. */
    val canGoBack: Boolean get() = entries.isNotEmpty()

    fun navigate(target: AppDestination): NavStack = when {
        // Switching tabs is a jump, not a push: the other tab's history is dropped
        // so back from Downloads never lands in Torrents' sub-pages.
        target.isRoot -> NavStack(root = target)
        target == current -> this
        else -> copy(entries = (entries + target).takeLast(MAX_BACK_STACK))
    }

    /** Returns the parent screen, or null when there is nothing to go back to. */
    fun back(): NavStack? = if (entries.isEmpty()) null else copy(entries = entries.dropLast(1))
}

private const val STACK_SEPARATOR = ">"

/** Flattens a stack to a single string for the default saved-state saver. */
fun encodeNav(stack: NavStack): String =
    (listOf(stack.root.name) + stack.entries.map { it.name }).joinToString(STACK_SEPARATOR)

/**
 * Rebuilds a stack from [key], falling back to the Downloads tab if the stored
 * value is unreadable (for example after an app update that renames a screen).
 */
fun decodeNav(key: String): NavStack = runCatching {
    val parts = key.split(STACK_SEPARATOR)
    NavStack(
        root = AppDestination.valueOf(parts.first()),
        entries = parts.drop(1).map { AppDestination.valueOf(it) }
    )
}.getOrDefault(NavStack())
