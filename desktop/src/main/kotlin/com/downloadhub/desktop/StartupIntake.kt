package com.downloadhub.desktop

import com.downloadhub.core.LinkParser
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * What the app was asked to do when Windows started it.
 *
 * A download manager has to be launchable *at* something, not just from the Start Menu:
 * Windows hands it a magnet link or a .torrent path as a command-line argument when the
 * user clicks one. `fun main()` took no arguments at all, so every one of those clicks
 * started the app and then quietly did nothing - which is why "open a magnet link" and
 * "double-click a .torrent" both looked like they had been ignored.
 *
 * Deciding what an argument means is kept here, as plain functions over strings, so it
 * can be tested without a process, a registry or a file.
 */

/** One thing to put in the queue, and where it came from. */
data class QueueTarget(
    /** What gets stored as the item's URL. */
    val link: String,
    /** A name to show in the list, when the source gave us one. */
    val name: String? = null,
    /**
     * Set when [link] is a path to a .torrent file on this machine.
     *
     * A torrent is queued as a file path, not fetched over the network, and the engine
     * needs to be told which of the two it is. Confusing the two is why a picked
     * .torrent used to sit in the list doing nothing.
     */
    val torrentFile: String? = null
)

/**
 * Turns one command-line argument into something to queue, or null to ignore it.
 *
 * Ignored deliberately rather than queued and failed: Windows and the jpackage
 * launcher both pass switches of their own, and a queue full of entries named
 * "-Xmx512m" is worse than no queue.
 */
fun classifyStartupArgument(
    raw: String,
    isFile: (String) -> Boolean = { path -> File(path).isFile }
): QueueTarget? {
    val argument = raw.trim().trim('"')
    if (argument.isEmpty()) return null

    // A magnet, which is the shape a browser hands over when there is no registered
    // protocol handler: a magnet has no authority component and no file behind it.
    if (argument.startsWith("magnet:", ignoreCase = true)) {
        return QueueTarget(link = argument, name = magnetDisplayName(argument))
    }

    // An explicit file URI, which is how some shells pass a picked file.
    val path = when {
        argument.startsWith("file:", ignoreCase = true) ->
            runCatching { File(java.net.URI(argument)).path }.getOrNull()

        else -> argument
    } ?: return null

    if (!path.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true)) {
        // A remote link. Kept as-is: the queue already knows how to fetch these, and
        // which engine they need is decided by LinkParser when they are added.
        return if (argument.startsWith("http://", ignoreCase = true) ||
            argument.startsWith("https://", ignoreCase = true)
        ) {
            QueueTarget(link = argument, name = LinkParser.fileNameFrom(argument))
        } else {
            null
        }
    }

    // A local .torrent. It must actually be there: a stale shortcut or a path the
    // caller guessed at should say so rather than queue a download that cannot start.
    if (!isFile(path)) return null
    val file = File(path)
    return QueueTarget(
        link = file.absolutePath,
        name = file.name,
        torrentFile = file.absolutePath
    )
}

/**
 * The `dn` parameter of a magnet, which is the torrent's own name.
 *
 * Present on almost every real magnet and the only name a magnet carries, so without
 * it the list shows the raw link.
 */
fun magnetDisplayName(magnet: String): String? =
    magnet.split('&')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.substring(3)
        ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
        ?.replace('+', ' ')
        ?.takeIf { it.isNotBlank() }

/** Arguments that belong to the JVM or the launcher rather than to the download. */
private fun isLauncherSwitch(argument: String): Boolean =
    argument.startsWith('-') ||
        // The app's own path, which is argv[0] on some launch paths.
        argument.equals("1DownloadManager.exe", ignoreCase = true) ||
        argument.endsWith("${File.separator}1DownloadManager.exe", ignoreCase = true)

/** Everything in a command line that should end up in the queue, in order. */
fun startupTargets(
    args: Array<String>,
    isFile: (String) -> Boolean = { path -> File(path).isFile }
): List<QueueTarget> = args.mapNotNull { argument ->
    if (isLauncherSwitch(argument)) null else classifyStartupArgument(argument, isFile)
}

/**
 * How a second copy hands its arguments to the one already running.
 *
 * A file rather than a socket, because the alternative - posting to the capture
 * server - only works while browser capture is switched on, and that is a setting the
 * user can turn off. It also survives the first instance being busy: the request waits
 * on disk until it next looks, rather than being dropped on the floor.
 *
 * The links are URL-encoded, one per line, because a magnet is full of `&` and `?` and
 * a path can contain anything.
 */
object IntakeChannel {

    /** Every queued target, from every launch since the last collection. */
    fun handOff(targets: List<QueueTarget>, file: File = AppPaths.pendingIntakeFile) {
        if (targets.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            val encoded = targets.joinToString("\n") {
                listOf(it.link, it.name.orEmpty(), it.torrentFile.orEmpty())
                    .joinToString("\t", transform = ::encode)
            }
            // Appended, not overwritten: two launches in quick succession both count.
            file.appendText(encoded + "\n", StandardCharsets.UTF_8)
        }
    }

    /**
     * Takes everything handed over since last time.
     *
     * The file is emptied by renaming it first, so a hand-off arriving mid-read is
     * kept for the next pass instead of being lost.
     */
    fun take(file: File = AppPaths.pendingIntakeFile): List<QueueTarget> = runCatching {
        if (!file.isFile) return emptyList()
        val claimed = File(file.parentFile, "${file.name}.claim-${System.nanoTime()}")
        if (!file.renameTo(claimed)) return emptyList()
        val targets = runCatching {
            claimed.readText(StandardCharsets.UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split("\t")
                    QueueTarget(
                        link = decode(parts.getOrNull(0).orEmpty()),
                        name = decode(parts.getOrNull(1).orEmpty()).takeIf { it.isNotBlank() },
                        torrentFile = decode(parts.getOrNull(2).orEmpty()).takeIf { it.isNotBlank() }
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
        claimed.delete()
        targets
    }.getOrDefault(emptyList())

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
