package com.downloadhub.core

import java.io.File

/**
 * A category of the user's own: files with these extensions go to this folder.
 *
 * AB Download Manager lets each category name its file types and a save folder; this is
 * that. [folder] is either absolute, or relative to the download folder.
 */
data class CategoryRule(
    val name: String,
    val extensions: List<String>,
    val folder: String
)

object CategoryRules {
    /** "mp4, .MKV  webm" to [mp4, mkv, webm]: commas, spaces and dots all tolerated. */
    fun parseExtensions(text: String): List<String> = text
        .split(',', ' ', ';', '\n', '\t')
        .map { it.trim().trimStart('.').lowercase() }
        .filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } }
        .distinct()

    fun formatExtensions(extensions: List<String>): String = extensions.joinToString(", ")

    /** The first rule naming this file's extension, or null. */
    fun match(fileName: String, rules: List<CategoryRule>): CategoryRule? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty() || extension == fileName.lowercase()) return null
        return rules.firstOrNull { rule -> extension in rule.extensions }
    }

    /** Where a rule's files go: its folder as given if absolute, under [root] otherwise. */
    fun folderFor(rule: CategoryRule, root: File): File {
        val folder = File(rule.folder.trim())
        return when {
            rule.folder.isBlank() -> File(root, rule.name.trim().ifBlank { "Other" })
            folder.isAbsolute -> folder
            else -> File(root, rule.folder.trim())
        }
    }
}
