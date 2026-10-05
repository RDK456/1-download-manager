package com.downloadhub.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryRulesTest {
    private val rules = listOf(
        CategoryRule("Movies", listOf("mkv", "mp4"), "Movies"),
        CategoryRule("Books", listOf("epub", "pdf"), "D:/Library/Books"),
        CategoryRule("Isos", listOf("iso"), "")
    )

    @Test
    fun extensionsParseLeniently() {
        assertEquals(listOf("mp4", "mkv", "webm"), CategoryRules.parseExtensions("mp4, .MKV  webm,mp4"))
        assertEquals(emptyList<String>(), CategoryRules.parseExtensions(" , . "))
    }

    @Test
    fun theFirstMatchingRuleWinsAndCaseDoesNotMatter() {
        assertEquals("Movies", CategoryRules.match("Film.MKV", rules)?.name)
        assertEquals("Books", CategoryRules.match("guide.pdf", rules)?.name)
        assertNull(CategoryRules.match("song.mp3", rules))
        assertNull("a name with no extension matches nothing", CategoryRules.match("README", rules))
    }

    @Test
    fun foldersAreRelativeToTheDownloadFolderUnlessAbsolute() {
        val root = File("C:/Downloads")
        assertEquals(File(root, "Movies"), CategoryRules.folderFor(rules[0], root))
        assertEquals(File("D:/Library/Books"), CategoryRules.folderFor(rules[1], root))
        assertEquals("a blank folder uses the category's name", File(root, "Isos"), CategoryRules.folderFor(rules[2], root))
    }
}
