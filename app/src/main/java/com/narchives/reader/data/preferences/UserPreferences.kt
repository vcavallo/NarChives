package com.narchives.reader.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "narchives_prefs")

class UserPreferences(private val context: Context) {

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://purplepag.es",
            "wss://relay.snort.social",
            "wss://nostr.wine",
            "wss://nostr.mom",
            "wss://relay.nostr.net",
        )

        val DEFAULT_BLOSSOM_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://blossom.nostr.build",
            "https://cdn.satellite.earth",
        )

        private val THEME_KEY = stringPreferencesKey("theme")
        private val READER_FONT_SIZE_KEY = intPreferencesKey("reader_font_size")
        private val READER_FONT_KEY = stringPreferencesKey("reader_font")
        private val WACZ_CACHE_LIMIT_MB_KEY = intPreferencesKey("wacz_cache_limit_mb")
        private val AUTO_FETCH_PROFILES_KEY = booleanPreferencesKey("auto_fetch_profiles")
    }

    val theme: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[THEME_KEY] ?: "system"
    }

    val readerFontSize: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[READER_FONT_SIZE_KEY] ?: 18
    }

    val readerFont: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[READER_FONT_KEY] ?: "serif"
    }

    val waczCacheLimitMb: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[WACZ_CACHE_LIMIT_MB_KEY] ?: 500
    }

    val autoFetchProfiles: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_FETCH_PROFILES_KEY] ?: true
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[THEME_KEY] = theme }
    }

    suspend fun setReaderFontSize(size: Int) {
        context.dataStore.edit { it[READER_FONT_SIZE_KEY] = size }
    }

    suspend fun setReaderFont(font: String) {
        context.dataStore.edit { it[READER_FONT_KEY] = font }
    }

    suspend fun setWaczCacheLimitMb(limitMb: Int) {
        context.dataStore.edit { it[WACZ_CACHE_LIMIT_MB_KEY] = limitMb }
    }
}
