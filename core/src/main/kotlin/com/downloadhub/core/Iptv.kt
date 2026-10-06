package com.downloadhub.core

/**
 * One free channel from iptv-org's public lists.
 *
 * [quality] ("1080p") and the flags ("Geo-blocked", "Not 24/7") come out of the name,
 * where the list keeps them, so the name reads as the channel's name.
 */
data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val groups: List<String> = emptyList(),
    val quality: String = "",
    val flags: List<String> = emptyList()
) {
    val geoBlocked: Boolean get() = flags.any { it.equals("Geo-blocked", ignoreCase = true) }
}

/**
 * Free, legally broadcast TV channels from the iptv-org project, which lists only streams
 * their broadcasters make publicly available. Browsed by category or by country.
 */
object IptvSource {
    const val BASE = "https://iptv-org.github.io/iptv"

    /** iptv-org's categories, as (id, label). */
    val categories: List<Pair<String, String>> = listOf(
        "news" to "News", "music" to "Music", "kids" to "Kids", "documentary" to "Documentary",
        "entertainment" to "Entertainment", "movies" to "Movies", "sports" to "Sports",
        "education" to "Education", "science" to "Science", "comedy" to "Comedy", "cooking" to "Cooking",
        "travel" to "Travel", "weather" to "Weather", "business" to "Business", "culture" to "Culture",
        "animation" to "Animation", "classic" to "Classic", "lifestyle" to "Lifestyle", "religious" to "Religious",
        "general" to "General"
    )

    /** A short list of countries, as (ISO code, name); iptv-org has a list per country. */
    val countries: List<Pair<String, String>> = listOf(
        "in" to "India", "us" to "United States", "uk" to "United Kingdom", "ca" to "Canada", "au" to "Australia",
        "de" to "Germany", "fr" to "France", "es" to "Spain", "it" to "Italy", "br" to "Brazil", "mx" to "Mexico",
        "jp" to "Japan", "kr" to "South Korea", "ae" to "United Arab Emirates", "sa" to "Saudi Arabia",
        "pk" to "Pakistan", "bd" to "Bangladesh", "id" to "Indonesia", "ph" to "Philippines", "ng" to "Nigeria",
        "za" to "South Africa", "tr" to "Turkey", "ru" to "Russia", "nl" to "Netherlands", "se" to "Sweden"
    )

    fun categoryUrl(id: String) = "$BASE/categories/$id.m3u"
    fun countryUrl(code: String) = "$BASE/countries/$code.m3u"

    suspend fun channels(listUrl: String): List<IptvChannel> = parseM3u(fetchText(listUrl))

    private val attribute = Regex("""([\w-]+)="([^"]*)"""")
    private val trailingTag = Regex("""\s*(\(([^()]*)\)|\[([^\[\]]*)])\s*$""")

    fun parseM3u(text: String): List<IptvChannel> {
        val channels = mutableListOf<IptvChannel>()
        var pending: String? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pending = line
                line.isEmpty() || line.startsWith("#") -> Unit // #EXTM3U, #EXTVLCOPT and the like
                pending != null -> {
                    channels += channelFrom(pending!!, line)
                    pending = null
                }
            }
        }
        return channels.filter { it.name.isNotBlank() && it.url.startsWith("http") }.distinctBy { it.url }
    }

    private fun channelFrom(info: String, url: String): IptvChannel {
        val attributes = attribute.findAll(info.substringBefore(",")).associate { it.groupValues[1] to it.groupValues[2] }
        var name = info.substringAfter(",", "").trim()
        var quality = ""
        val flags = mutableListOf<String>()
        while (true) {
            val match = trailingTag.find(name) ?: break
            val round = match.groupValues[2]
            val square = match.groupValues[3]
            if (round.matches(Regex("""\d{3,4}[pi]"""))) quality = round else if (square.isNotEmpty()) flags += square else break
            name = name.removeRange(match.range).trim()
        }
        return IptvChannel(
            name = name,
            url = url,
            logo = attributes["tvg-logo"]?.takeIf { it.startsWith("http") },
            groups = attributes["group-title"].orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() },
            quality = quality,
            flags = flags.reversed()
        )
    }
}
