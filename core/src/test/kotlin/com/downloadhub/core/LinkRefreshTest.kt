package com.downloadhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRefreshTest {
    private val old = "https://files.example.com/a/movie.mp4?token=old"

    @Test
    fun onlyRefusalsCountAsExpired() {
        assertTrue(LinkRefresh.looksExpired("Server returned HTTP 403"))
        assertTrue(LinkRefresh.looksExpired("Server returned HTTP 410"))
        assertFalse(LinkRefresh.looksExpired("Server returned HTTP 503"))
        assertFalse(LinkRefresh.looksExpired(null))
    }

    @Test
    fun theSameFileWithANewTokenIsPicked() {
        val fresh = "https://files.example.com/b/movie.mp4?token=new"
        assertEquals(fresh, LinkRefresh.pick(old, listOf("https://files.example.com/other.mp4", fresh), true))
    }

    @Test
    fun anotherHostIsRefusedWhenCookiesWouldGoWithIt() {
        val moved = "https://cdn.example.net/movie.mp4?token=new"
        assertEquals(moved, LinkRefresh.pick(old, listOf(moved), false))
        assertNull(LinkRefresh.pick(old, listOf(moved), true))
    }

    @Test
    fun doubtMeansNothing() {
        val two = listOf("https://files.example.com/1/movie.mp4", "https://files.example.com/2/movie.mp4")
        assertNull("two candidates", LinkRefresh.pick(old, two, false))
        assertNull("the old link again", LinkRefresh.pick(old, listOf(old), false))
        assertNull(
            "a script name says nothing about the file",
            LinkRefresh.pick("https://x.com/get.php?id=1", listOf("https://x.com/get.php?id=2"), false)
        )
        assertNull("no extension", LinkRefresh.pick("https://x.com/download?id=1", listOf("https://x.com/download?id=2"), false))
    }
}
