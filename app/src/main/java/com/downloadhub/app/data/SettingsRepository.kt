package com.downloadhub.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.downloadhub.app.data.model.AppTheme
import com.downloadhub.app.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downloadHubDataStore by preferencesDataStore(name = "download_hub_settings")

/** User-tunable download behaviour, surfaced on the Downloads settings page. */
data class DownloadSettings(
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val speedLimitBytesPerSecond: Long = UNLIMITED,
    val wifiOnly: Boolean = false,
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val autoRemoveCompleted: Boolean = false,
    /** Parallel connections one file is split over, when its server takes ranges. */
    val connectionsPerDownload: Int = DEFAULT_CONNECTIONS,
    /** Links shared or opened from a browser, or copied, go straight into the queue. */
    val autoQueueIncoming: Boolean = true
) {
    val isSpeedLimited: Boolean get() = speedLimitBytesPerSecond > 0

    companion object {
        const val UNLIMITED = 0L
        const val DEFAULT_MAX_CONCURRENT = 3
        const val MIN_MAX_CONCURRENT = 1
        const val MAX_MAX_CONCURRENT = 8
        const val DEFAULT_MAX_RETRIES = 2
        const val MAX_RETRIES_LIMIT = 5
        const val DEFAULT_CONNECTIONS = 4
        const val MAX_CONNECTIONS = 16

        /** Speed limit presets in bytes per second; 0 means unlimited. */
        val SPEED_PRESETS = listOf(
            UNLIMITED to "Unlimited",
            128L * 1024 to "128 KB/s",
            512L * 1024 to "512 KB/s",
            1024L * 1024 to "1 MB/s",
            2L * 1024 * 1024 to "2 MB/s",
            5L * 1024 * 1024 to "5 MB/s"
        )
    }
}

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val themeSchemeKey = stringPreferencesKey("theme_scheme")
    private val destinationTreeKey = stringPreferencesKey("destination_tree_uri")
    private val skippedVersionKey = stringPreferencesKey("skipped_app_version")
    private val lastUpdateCheckKey = longPreferencesKey("last_app_update_check")
    private val lastYtDlpCheckKey = longPreferencesKey("last_ytdlp_check")
    private val autoUpdateCheckKey = booleanPreferencesKey("auto_app_update_check")
    private val pendingInstallKey = stringPreferencesKey("pending_install_version")
    private val maxConcurrentKey = intPreferencesKey("max_concurrent_downloads")
    private val speedLimitKey = longPreferencesKey("speed_limit_bps")
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val autoQueueKey = booleanPreferencesKey("auto_queue_incoming")
    private val maxRetriesKey = intPreferencesKey("max_retries")
    private val autoRemoveKey = booleanPreferencesKey("auto_remove_completed")
    private val connectionsKey = intPreferencesKey("connections_per_download")
    private val queuesKey = stringPreferencesKey("queues")
    private val advancedKey = stringPreferencesKey("advanced")
    private val rssFeedsKey = stringPreferencesKey("rss_feeds")
    private val rssRulesKey = stringPreferencesKey("rss_rules")
    private val lastQueueCheckKey = longPreferencesKey("last_queue_check")
    private val queuePausedKey = androidx.datastore.preferences.core.stringSetPreferencesKey("queue_paused_ids")

    val themeMode: Flow<ThemeMode> = context.downloadHubDataStore.data.map { preferences ->
        runCatching { ThemeMode.valueOf(preferences[themeKey].orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val appTheme: Flow<AppTheme> = context.downloadHubDataStore.data.map { preferences ->
        AppTheme.fromValue(preferences[themeSchemeKey])
    }

    val destinationTreeUri: Flow<String?> = context.downloadHubDataStore.data.map { preferences ->
        preferences[destinationTreeKey]
    }

    /** Release version the user chose to skip, if any. */
    val skippedVersion: Flow<String?> = context.downloadHubDataStore.data.map { it[skippedVersionKey] }

    val lastUpdateCheck: Flow<Long> = context.downloadHubDataStore.data.map { it[lastUpdateCheckKey] ?: 0L }

    val autoCheckUpdates: Flow<Boolean> = context.downloadHubDataStore.data.map { preferences ->
        preferences[autoUpdateCheckKey] ?: true
    }

    val downloadSettings: Flow<DownloadSettings> = context.downloadHubDataStore.data.map { preferences ->
        DownloadSettings(
            maxConcurrent = (preferences[maxConcurrentKey]
                ?: DownloadSettings.DEFAULT_MAX_CONCURRENT).coerceIn(
                DownloadSettings.MIN_MAX_CONCURRENT,
                DownloadSettings.MAX_MAX_CONCURRENT
            ),
            speedLimitBytesPerSecond = (preferences[speedLimitKey] ?: DownloadSettings.UNLIMITED)
                .coerceAtLeast(0L),
            wifiOnly = preferences[wifiOnlyKey] ?: false,
            maxRetries = (preferences[maxRetriesKey] ?: DownloadSettings.DEFAULT_MAX_RETRIES)
                .coerceIn(0, DownloadSettings.MAX_RETRIES_LIMIT),
            autoRemoveCompleted = preferences[autoRemoveKey] ?: false,
            connectionsPerDownload = (preferences[connectionsKey] ?: DownloadSettings.DEFAULT_CONNECTIONS)
                .coerceIn(1, DownloadSettings.MAX_CONNECTIONS),
            autoQueueIncoming = preferences[autoQueueKey] ?: true
        )
    }

    // --- categories, proxy, speed and BitTorrent settings, IP filter --------------

    val advanced: Flow<AdvancedSettings> = context.downloadHubDataStore.data.map { AdvancedSettings.decode(it[advancedKey]) }

    suspend fun currentAdvanced(): AdvancedSettings = advanced.first()

    suspend fun saveAdvanced(settings: AdvancedSettings) {
        context.downloadHubDataStore.edit { it[advancedKey] = settings.encode() }
    }

    // --- RSS feeds and auto-download rules ------------------------------------------

    val rssFeedsFlow: Flow<List<com.downloadhub.app.download.RssFeed>> =
        context.downloadHubDataStore.data.map { com.downloadhub.app.download.RssCodec.decodeFeeds(it[rssFeedsKey]) }
    val rssRulesFlow: Flow<List<com.downloadhub.core.RssRule>> =
        context.downloadHubDataStore.data.map { com.downloadhub.app.download.RssCodec.decodeRules(it[rssRulesKey]) }

    suspend fun rssFeeds() = rssFeedsFlow.first()
    suspend fun rssRules() = rssRulesFlow.first()

    suspend fun saveRssFeeds(feeds: List<com.downloadhub.app.download.RssFeed>) {
        context.downloadHubDataStore.edit { it[rssFeedsKey] = com.downloadhub.app.download.RssCodec.encodeFeeds(feeds) }
    }

    suspend fun saveRssRules(rules: List<com.downloadhub.core.RssRule>) {
        context.downloadHubDataStore.edit { it[rssRulesKey] = com.downloadhub.app.download.RssCodec.encodeRules(rules) }
    }

    // --- named queues ------------------------------------------------------------

    val queues: Flow<List<AppQueue>> = context.downloadHubDataStore.data.map { QueueCodec.decode(it[queuesKey]) }

    suspend fun currentQueues(): List<AppQueue> = queues.first()

    suspend fun saveQueues(list: List<AppQueue>) {
        context.downloadHubDataStore.edit { it[queuesKey] = QueueCodec.encode(list) }
    }

    /** When the queue schedules were last checked, so the next check knows what passed. */
    suspend fun lastQueueCheck(): Long = context.downloadHubDataStore.data.first()[lastQueueCheckKey] ?: 0L

    suspend fun setLastQueueCheck(at: Long) {
        context.downloadHubDataStore.edit { it[lastQueueCheckKey] = at }
    }

    /** Downloads a queue stop paused, so the queue's next start resumes them. */
    suspend fun queuePausedIds(): Set<String> = context.downloadHubDataStore.data.first()[queuePausedKey].orEmpty()

    suspend fun setQueuePausedIds(ids: Set<String>) {
        context.downloadHubDataStore.edit { it[queuePausedKey] = ids }
    }

    suspend fun setConnectionsPerDownload(value: Int) {
        context.downloadHubDataStore.edit { it[connectionsKey] = value.coerceIn(1, DownloadSettings.MAX_CONNECTIONS) }
    }

    suspend fun currentDownloadSettings(): DownloadSettings = downloadSettings.first()

    suspend fun setMaxConcurrent(value: Int) {
        context.downloadHubDataStore.edit { preferences ->
            preferences[maxConcurrentKey] = value.coerceIn(
                DownloadSettings.MIN_MAX_CONCURRENT,
                DownloadSettings.MAX_MAX_CONCURRENT
            )
        }
    }

    suspend fun setSpeedLimit(bytesPerSecond: Long) {
        context.downloadHubDataStore.edit { it[speedLimitKey] = bytesPerSecond.coerceAtLeast(0L) }
    }

    suspend fun setAutoQueueIncoming(enabled: Boolean) {
        context.downloadHubDataStore.edit { it[autoQueueKey] = enabled }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.downloadHubDataStore.edit { it[wifiOnlyKey] = enabled }
    }

    suspend fun setMaxRetries(value: Int) {
        context.downloadHubDataStore.edit { preferences ->
            preferences[maxRetriesKey] = value.coerceIn(0, DownloadSettings.MAX_RETRIES_LIMIT)
        }
    }

    suspend fun setAutoRemoveCompleted(enabled: Boolean) {
        context.downloadHubDataStore.edit { it[autoRemoveKey] = enabled }
    }

    suspend fun currentDestinationTreeUri(): String? = destinationTreeUri.first()

    suspend fun currentSkippedVersion(): String? = skippedVersion.first()

    suspend fun setSkippedVersion(version: String?) {
        context.downloadHubDataStore.edit { preferences ->
            if (version.isNullOrBlank()) {
                preferences.remove(skippedVersionKey)
            } else {
                preferences[skippedVersionKey] = version
            }
        }
    }

    suspend fun markUpdateCheck(now: Long = System.currentTimeMillis()) {
        context.downloadHubDataStore.edit { it[lastUpdateCheckKey] = now }
    }

    suspend fun lastUpdateCheckOnce(): Long = lastUpdateCheck.first()

    /**
     * When yt-dlp was last checked. yt-dlp releases often, so the automatic pass is
     * throttled to once a day rather than hitting the feed on every launch.
     */
    suspend fun lastYtDlpCheck(): Long =
        context.downloadHubDataStore.data.map { it[lastYtDlpCheckKey] ?: 0L }.first()

    suspend fun markYtDlpCheck(now: Long = System.currentTimeMillis()) {
        context.downloadHubDataStore.edit { it[lastYtDlpCheckKey] = now }
    }

    /** Version of an update APK already downloaded but maybe not yet installed. */
    suspend fun pendingInstallVersion(): String? = context.downloadHubDataStore.data
        .map { it[pendingInstallKey] }
        .first()

    suspend fun setPendingInstallVersion(version: String) {
        context.downloadHubDataStore.edit { it[pendingInstallKey] = version }
    }

    suspend fun clearPendingInstallVersion() {
        context.downloadHubDataStore.edit { it.remove(pendingInstallKey) }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.downloadHubDataStore.edit { it[autoUpdateCheckKey] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.downloadHubDataStore.edit { preferences ->
            preferences[themeKey] = mode.name
        }
    }

    suspend fun setAppTheme(theme: AppTheme) {
        context.downloadHubDataStore.edit { preferences ->
            preferences[themeSchemeKey] = theme.name
        }
    }

    suspend fun setDestinationTreeUri(uri: String?) {
        context.downloadHubDataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(destinationTreeKey)
            } else {
                preferences[destinationTreeKey] = uri
            }
        }
    }
}
