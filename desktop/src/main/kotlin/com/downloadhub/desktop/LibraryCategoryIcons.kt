package com.downloadhub.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import com.downloadhub.core.LibraryCategory

/**
 * The icon shown beside each category in the sidebar.
 *
 * The category names are words, and six words in a column have to be read one at a
 * time. A shape per row is picked out pre-attentively, which is the difference
 * between glancing at the rail and reading it.
 *
 * Mapped in one place rather than at the call site, because a new category added to
 * [LibraryCategory] should either get an icon here or consciously not have one -
 * `when` without an `else` makes that a compile error rather than a silent gap.
 */
object LibraryCategoryIcons {

    fun of(category: LibraryCategory): ImageVector? = when (category) {
        LibraryCategory.ALL -> null
        LibraryCategory.COMPRESSED -> DlmIcons.Compressed
        LibraryCategory.PROGRAMS -> DlmIcons.Programs
        LibraryCategory.VIDEOS -> DlmIcons.Videos
        LibraryCategory.MUSIC -> DlmIcons.Music
        LibraryCategory.PICTURES -> DlmIcons.Pictures
        LibraryCategory.DOCUMENTS -> DlmIcons.Documents
    }
}
