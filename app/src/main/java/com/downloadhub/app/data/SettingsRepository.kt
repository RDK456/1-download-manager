package com.downloadhub.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.downloadhub.app.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downloadHubDataStore by preferencesDataStore(name = "download_hub_settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val destinationTreeKey = stringPreferencesKey("destination_tree_uri")
    private val skippedVersionKey = stringPreferencesKey("skipped_app_version")
    private val lastUpdateCheckKey = longPreferencesKey("last_app_update_check")
    private val autoUpdateCheckKey = booleanPreferencesKey("auto_app_update_check")
    private val pendingInstallKey = stringPreferencesKey("pending_install_version")

    val themeMode: Flow<ThemeMode> = context.downloadHubDataStore.data.map { preferences ->
        runCatching { ThemeMode.valueOf(preferences[themeKey].orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM)
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
