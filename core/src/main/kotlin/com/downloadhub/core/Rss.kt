package com.downloadhub.core

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** One article in a feed, reduced to what downloading it needs. */
data class RssItem(
    val title: String,
    /** A magnet or a .torrent URL - whatever the feed offers to download. */
    val link: String,
    /** Stable identity, so an article is acted on once however often the feed is read. */
    val guid: String,
    val published: String = ""
)

/**
 * A qBittorrent auto-download rule. [mustContain] and [mustNotContain] are words that must
 * all appear / none appear, or regular expressions when [useRegex] is set. Matching ignores
 * case, as qBittorrent's does.
 */
data class RssRule(
    val name: String,
    val mustContain: String,
    val mustNotContain: String = "",
    val useRegex: Boolean = false,
    val enabled: Boolean = true
) {
    fun matches(title: String): Boolean {
        if (!enabled || mustContain.isBlank()) return false
        return if (useRegex) {
            val must = runCatching { Regex(mustContain, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
            val mustNot = mustNotContain.takeIf { it.isNotBlank() }
                ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
            must.containsMatchIn(title) && (mustNot == null || !mustNot.containsMatchIn(title))
        } else {
            val words = mustContain.split(' ').filter { it.isNotBlank() }
            val banned = mustNotContain.split(' ').filter { it.isNotBlank() }
            words.all { title.contains(it, ignoreCase = true) } && banned.none { title.contains(it, ignoreCase = true) }
        }
    }
}

/** RSS 2.0 and Atom, as torrent sites publish them. Articles with nothing to download are dropped. */
object RssParser {
    fun parse(xml: String): List<RssItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Feeds come from the internet: no external entities, no DTD fetching.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim())))
        val rssItems = document.getElementsByTagNameNS("*", "item")
        val atomEntries = document.getElementsByTagNameNS("*", "entry")
        val nodes = if (rssItems.length > 0) rssItems else atomEntries
        return (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as? Element ?: return@mapNotNull null
            val title = text(element, "title").ifBlank { return@mapNotNull null }
            val link = downloadLink(element) ?: return@mapNotNull null
            RssItem(
                title = title,
                link = link,
                guid = text(element, "guid").ifBlank { text(element, "id") }.ifBlank { link },
                published = text(element, "pubDate").ifBlank { text(element, "updated") }
            )
        }
    }

    /** The first child element with this local name, in any namespace. */
    private fun child(element: Element, name: String): Element? {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if ((node.localName ?: node.nodeName) == name) return node
        }
        return null
    }

    private fun text(element: Element, name: String): String = child(element, name)?.textContent?.trim().orEmpty()

    /**
     * Where to download from, best first: a magnet in a torrent namespace (EZTV), a
     * .torrent enclosure, a link that is a magnet or a .torrent, then an info hash (Nyaa).
     */
    private fun downloadLink(element: Element): String? {
        text(element, "magnetURI").takeIf { it.startsWith("magnet:") }?.let { return it }
        child(element, "enclosure")?.getAttribute("url")?.takeIf { it.isNotBlank() }?.let { return it }
        val link = child(element, "link")?.let { it.getAttribute("href").ifBlank { it.textContent.trim() } }.orEmpty()
        if (link.startsWith("magnet:") || link.substringBefore('?').endsWith(".torrent", ignoreCase = true)) return link
        text(element, "infoHash").takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }?.let { hash ->
            return "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(text(element, "title"), "UTF-8")
        }
        return link.takeIf { it.startsWith("http") }
    }
}
