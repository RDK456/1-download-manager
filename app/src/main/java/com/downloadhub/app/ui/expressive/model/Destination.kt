package com.downloadhub.app.ui.expressive.model

/**
 * True when the chosen folder is on a volume other than the phone's own storage, which in
 * practice means a memory card or USB drive. Those are often FAT32 and cannot hold a file
 * over 4 GB. A folder picked through the system picker is a tree address such as
 * `content://com.android.externalstorage.documents/tree/primary%3ADownload`; the part
 * before the colon names the volume, and `primary` is the phone itself.
 */
fun isRemovableTree(treeUri: String?): Boolean {
    if (treeUri.isNullOrBlank()) return false
    val marker = "/tree/"
    val tail = treeUri.substringAfter(marker, missingDelimiterValue = "")
    if (tail.isEmpty()) return false
    val decoded = java.net.URLDecoder.decode(tail, "UTF-8")
    val volume = decoded.substringBefore(':')
    return treeUri.contains("externalstorage") && volume.isNotEmpty() && !volume.equals("primary", ignoreCase = true) && !volume.equals("home", ignoreCase = true)
}
