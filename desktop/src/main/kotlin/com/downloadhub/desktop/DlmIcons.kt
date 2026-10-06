package com.downloadhub.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.AppWindow
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.FileArchive
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Music
import com.composables.icons.lucide.Pause
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Youtube

/**
 * The app's named icons, all from Lucide so every glyph shares one stroke and style.
 *
 * These used to be hand-copied Material paths, kept to avoid the 36 MB
 * `material-icons-extended` jar. Lucide covers the whole app in about 5 MB, so the
 * names stay (every caller keeps working) and the drawings come from one set.
 */
object DlmIcons {
    val Pause: ImageVector get() = Lucide.Pause
    val Stop: ImageVector get() = Lucide.Square
    val Folder: ImageVector get() = Lucide.Folder
    val FolderOpen: ImageVector get() = Lucide.FolderOpen
    val ArrowUpward: ImageVector get() = Lucide.ArrowUp
    val ArrowDownward: ImageVector get() = Lucide.ArrowDown
    val Compressed: ImageVector get() = Lucide.FileArchive
    val Programs: ImageVector get() = Lucide.AppWindow
    val Videos: ImageVector get() = Lucide.Film
    val Music: ImageVector get() = Lucide.Music
    val Pictures: ImageVector get() = Lucide.Image
    val Documents: ImageVector get() = Lucide.FileText
    val YouTube: ImageVector get() = Lucide.Youtube
}
