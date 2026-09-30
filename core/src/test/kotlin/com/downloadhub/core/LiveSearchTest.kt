package com.downloadhub.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The parsers against the live sites.
 *
 * Not part of the suite: a test that depends on a third party being up fails on the day it
 * is down, for a reason that has nothing to do with the code, and a suite that fails for
 * reasons nobody can act on is one people stop reading. Ignored by default and run by hand
 * with `-Ddlm.liveSearch=true`, which is what it is for: checking a parser against what a
 * site actually returns today, rather than against a response captured when the parser was
 * written.
 */
@Ignore("needs the network; run with -Ddlm.liveSearch=true")
class LiveSearchTest {

    private val enabled = System.getProperty("dlm.liveSearch") == "true"

    @Test
    fun `eztv answers a real show and the parser reads it`() = runBlocking {
        if (!enabled) return@runBlocking
        val json = fetchText("https://eztvx.to/api/get-torrents?limit=5")
        val results = parseEztvFeed(json)
        println("EZTV: ${results.size} results")
        results.take(3).forEach { println("  ${it.seeders} seeds  ${it.sizeBytes} bytes  ${it.name}") }
        assertTrue("EZTV returned nothing", results.isNotEmpty())
        assertTrue("no seeders read", results.any { it.seeders > 0 })
        assertTrue("no sizes read", results.any { it.sizeBytes > 0 })
        assertTrue("no magnet built", results.all { it.magnet.contains("urn:btih:") })
    }

    @Test
    fun `nyaa answers a real search and the parser reads it`() = runBlocking {
        if (!enabled) return@runBlocking
        val xml = fetchText("https://nyaa.si/?page=rss&q=naruto&c=0_0&f=0")
        val results = parseNyaaFeed(xml)
        println("Nyaa: ${results.size} results")
        results.take(3).forEach { println("  ${it.seeders} seeds  ${it.sizeBytes} bytes  ${it.name}") }
        assertTrue("Nyaa returned nothing", results.isNotEmpty())
        assertTrue("no hashes read", results.all { it.infoHash.length == 40 })
        assertTrue("no sizes read", results.any { it.sizeBytes > 0 })
    }

    @Test
    fun `a whole search merges real sources without throwing`() = runBlocking {
        if (!enabled) return@runBlocking
        val outcome = searchSources(defaultSearchSources(), "one piece", SearchGroup.ANIME)
        println("Search: ${outcome.results.size} results, ${outcome.failures.size} sources failed")
        outcome.failures.forEach { println("  failed: ${it.label} - ${it.reason}") }
        outcome.results.take(5).forEach { println("  ${it.source}: ${it.name}") }
        // Not "must find something" - what is on a public feed today is not this test's to
        // promise. What it must do is not throw, and must report a source it could not reach
        // rather than returning nothing and looking like a search with no results.
        assertTrue(outcome.results.isNotEmpty() || outcome.failures.isNotEmpty())
    }
}
