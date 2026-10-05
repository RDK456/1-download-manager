package com.downloadhub.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickAddTest {
    private val root = File("D:/downloads")

    @Test
    fun `a file is filed by the user's rule first, then by its kind`() {
        val rules = listOf(CategoryRule("Movies", listOf("mkv"), "Films"))
        assertEquals(SaveCategory("Movies", File(root, "Films")), SaveCategories.forFile("a.mkv", root, rules))
        assertEquals("Programs", SaveCategories.forFile("setup.msi", root, rules).name)
        assertEquals(File(root, "Programs"), SaveCategories.forFile("setup.msi", root, rules).folder)
    }

    @Test
    fun `the list has the user's categories then the built-in ones, without repeats`() {
        val names = SaveCategories.all(root, listOf(CategoryRule("Programs", listOf("exe"), "Apps"))).map { it.name }
        assertEquals("Programs", names.first())
        assertEquals(1, names.count { it == "Programs" })
        assertEquals(true, "Other" in names)
    }

    @Test
    fun `the size comes from the range total, or the length when the range was ignored`() {
        assertEquals(132_527_959L, HttpProbe.totalFrom(206, "bytes 0-0/132527959", 1))
        assertEquals(5_000L, HttpProbe.totalFrom(200, null, 5_000))
        assertNull(HttpProbe.totalFrom(206, "bytes 0-0/*", 1))
        assertNull(HttpProbe.totalFrom(200, null, -1))
    }
}
