package com.downloadhub.app.ui.expressive.model

/** What to rebuild of the torrent sheet after the app was closed in the background. */
sealed interface PreviewRestore {
    object None : PreviewRestore
    class Magnet(val link: String) : PreviewRestore
    class TorrentFile(val path: String) : PreviewRestore
}

/**
 * A .torrent file comes back only if the copy the app made of it is still there; a magnet comes
 * back only if its link is fit to keep (see [linkSafeToSave]) and then asks the swarm for its
 * file list again, since that list was never saved.
 */
fun previewRestorePlan(link: String?, file: String?, fileExists: Boolean): PreviewRestore = when {
    file != null -> if (fileExists) PreviewRestore.TorrentFile(file) else PreviewRestore.None
    link != null && link.startsWith("magnet:", ignoreCase = true) && linkSafeToSave(link) -> PreviewRestore.Magnet(link)
    else -> PreviewRestore.None
}
