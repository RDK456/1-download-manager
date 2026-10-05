package com.downloadhub.app.data

import com.downloadhub.core.CategoryRule
import com.downloadhub.core.CategoryRules
import com.downloadhub.core.ProxySetting
import com.downloadhub.core.ProxyType
import com.downloadhub.core.QueueRules
import com.downloadhub.core.QueueSchedule
import com.downloadhub.core.TorrentEncryption
import com.downloadhub.core.TorrentSessionSettings
import java.time.LocalDateTime

/**
 * The desktop's newer settings on the phone: your own categories, the proxy, qBittorrent's
 * speed and BitTorrent pages, and the IP filter. Kept as one value under one DataStore key.
 */
data class AdvancedSettings(
    val categoryRules: List<CategoryRule> = emptyList(),
    val proxy: ProxySetting = ProxySetting(ProxyType.SYSTEM),
    val uploadLimit: Long = 0,
    val altDownloadLimit: Long = 512L * 1024,
    val altUploadLimit: Long = 128L * 1024,
    val altEnabled: Boolean = false,
    val altScheduleEnabled: Boolean = false,
    val altDays: Set<Int> = (1..7).toSet(),
    val altStart: Int = 8 * 60,
    val altStop: Int = 20 * 60,
    val torrentPort: Int = 0,
    val dht: Boolean = true,
    val lsd: Boolean = true,
    val portForwarding: Boolean = true,
    val encryption: TorrentEncryption = TorrentEncryption.ALLOWED,
    val maxConnections: Int = 0,
    val anonymous: Boolean = false,
    val ipFilterEnabled: Boolean = false,
    /** A copy of the chosen blocklist in the app's own files. */
    val ipFilterPath: String = ""
) {
    fun altActive(now: LocalDateTime = LocalDateTime.now()): Boolean =
        altEnabled || QueueRules.isWithin(QueueSchedule(altScheduleEnabled, altDays, altStart, altStop), now)

    fun downloadLimit(normal: Long): Long = if (altActive()) altDownloadLimit else normal

    fun torrentSession(normalDownload: Long): TorrentSessionSettings = TorrentSessionSettings(
        downloadLimitBytesPerSecond = downloadLimit(normalDownload),
        uploadLimitBytesPerSecond = if (altActive()) altUploadLimit else uploadLimit,
        listenPort = torrentPort,
        dht = dht,
        localPeerDiscovery = lsd,
        portForwarding = portForwarding,
        encryption = encryption,
        maxConnections = maxConnections,
        anonymousMode = anonymous
    )

    fun encode(): String = buildList {
        add("proxyType=${proxy.type.name}")
        add("proxyHost=${proxy.host}")
        add("proxyPort=${proxy.port}")
        add("upload=$uploadLimit")
        add("altDown=$altDownloadLimit")
        add("altUp=$altUploadLimit")
        add("altOn=$altEnabled")
        add("altSchedule=$altScheduleEnabled")
        add("altDays=${altDays.sorted().joinToString(",")}")
        add("altStart=$altStart")
        add("altStop=$altStop")
        add("port=$torrentPort")
        add("dht=$dht")
        add("lsd=$lsd")
        add("forward=$portForwarding")
        add("encryption=${encryption.name}")
        add("maxConnections=$maxConnections")
        add("anonymous=$anonymous")
        add("ipFilter=$ipFilterEnabled")
        add("ipFilterPath=$ipFilterPath")
        categoryRules.forEach { rule ->
            add(
                "rule=" + listOf(rule.name, rule.extensions.joinToString(","), rule.folder)
                    .joinToString("|") { it.replace("|", "/").replace("\n", " ") }
            )
        }
    }.joinToString("\n")

    companion object {
        fun decode(text: String?): AdvancedSettings {
            if (text.isNullOrBlank()) return AdvancedSettings()
            val lines = text.lines().mapNotNull { line ->
                line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
            }
            val values = lines.filter { it.first != "rule" }.toMap()
            fun long(key: String, default: Long) = values[key]?.toLongOrNull() ?: default
            fun int(key: String, default: Int) = values[key]?.toIntOrNull() ?: default
            fun bool(key: String, default: Boolean) = values[key]?.toBooleanStrictOrNull() ?: default
            val d = AdvancedSettings()
            return AdvancedSettings(
                categoryRules = lines.filter { it.first == "rule" }.mapNotNull { (_, value) ->
                    val parts = value.split('|')
                    if (parts.size < 3) return@mapNotNull null
                    CategoryRule(parts[0], CategoryRules.parseExtensions(parts[1]), parts[2])
                        .takeIf { it.extensions.isNotEmpty() }
                },
                proxy = ProxySetting(
                    ProxyType.entries.firstOrNull { it.name == values["proxyType"] } ?: ProxyType.SYSTEM,
                    values["proxyHost"].orEmpty(),
                    int("proxyPort", 0)
                ),
                uploadLimit = long("upload", d.uploadLimit),
                altDownloadLimit = long("altDown", d.altDownloadLimit),
                altUploadLimit = long("altUp", d.altUploadLimit),
                altEnabled = bool("altOn", d.altEnabled),
                altScheduleEnabled = bool("altSchedule", d.altScheduleEnabled),
                altDays = values["altDays"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.altDays,
                altStart = int("altStart", d.altStart),
                altStop = int("altStop", d.altStop),
                torrentPort = int("port", d.torrentPort),
                dht = bool("dht", d.dht),
                lsd = bool("lsd", d.lsd),
                portForwarding = bool("forward", d.portForwarding),
                encryption = TorrentEncryption.entries.firstOrNull { it.name == values["encryption"] } ?: d.encryption,
                maxConnections = int("maxConnections", d.maxConnections),
                anonymous = bool("anonymous", d.anonymous),
                ipFilterEnabled = bool("ipFilter", d.ipFilterEnabled),
                ipFilterPath = values["ipFilterPath"].orEmpty()
            )
        }
    }
}
