package com.downloadhub.app.download

import com.downloadhub.app.data.AdvancedSettings
import com.downloadhub.core.CategoryRule
import com.downloadhub.core.ProxySetting
import com.downloadhub.core.ProxyType
import com.downloadhub.core.TorrentEncryption
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedSettingsTest {
    @Test
    fun everythingRoundTripsAndBlankIsTheDefaults() {
        val settings = AdvancedSettings(
            categoryRules = listOf(CategoryRule("Movies", listOf("mkv", "mp4"), "Movies")),
            proxy = ProxySetting(ProxyType.SOCKS, "10.0.0.1", 1080),
            uploadLimit = 2048,
            altEnabled = true,
            altDays = setOf(6, 7),
            torrentPort = 51413,
            dht = false,
            encryption = TorrentEncryption.REQUIRED,
            ipFilterEnabled = true,
            ipFilterPath = "/data/filter.dat"
        )
        assertEquals(settings, AdvancedSettings.decode(settings.encode()))
        assertEquals(AdvancedSettings(), AdvancedSettings.decode(null))
        assertEquals("the alternative limits apply while switched on", 512L * 1024, settings.downloadLimit(0))
    }
}
