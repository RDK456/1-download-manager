package com.downloadhub.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
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

    val themeMode: Flow<ThemeMode> = context.downloadHubDataStore.data.map { preferences ->
        runCatching { ThemeMode.valueOf(preferences[themeKey].orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    val destinationTreeUri: Flow<String?> = context.downloadHubDataStore.data.map { preferences ->
        preferences[destinationTreeKey]
    }

    suspend fun currentDestinationTreeUri(): String? = destinationTreeUri.first()

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
