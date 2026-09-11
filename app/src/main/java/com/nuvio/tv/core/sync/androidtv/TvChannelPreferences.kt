package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvChannelDataStore by preferencesDataStore(
    name = "tv_channel_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class TvChannelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val continueWatchingChannelIdKey = longPreferencesKey("continue_watching_channel_id")
    private val enabledCatalogKeysKey = stringSetPreferencesKey("enabled_tv_catalog_keys")

    suspend fun getChannelId(): Long? =
        context.tvChannelDataStore.data.map { it[continueWatchingChannelIdKey] }.first()

    suspend fun setChannelId(id: Long) {
        context.tvChannelDataStore.edit { it[continueWatchingChannelIdKey] = id }
    }

    suspend fun clearChannelId() {
        context.tvChannelDataStore.edit { it.remove(continueWatchingChannelIdKey) }
    }

    suspend fun getCatalogChannelId(catalogKey: String): Long? =
        context.tvChannelDataStore.data.map { prefs -> prefs[catalogChannelIdKey(catalogKey)] }.first()

    suspend fun setCatalogChannelId(catalogKey: String, channelId: Long) {
        context.tvChannelDataStore.edit { prefs -> prefs[catalogChannelIdKey(catalogKey)] = channelId }
    }

    suspend fun clearCatalogChannelId(catalogKey: String) {
        context.tvChannelDataStore.edit { prefs -> prefs.remove(catalogChannelIdKey(catalogKey)) }
    }

    val enabledCatalogKeys: Flow<Set<String>> =
        context.tvChannelDataStore.data.map { prefs -> prefs[enabledCatalogKeysKey].orEmpty() }

    suspend fun getEnabledCatalogKeys(): Set<String> = enabledCatalogKeys.first()

    suspend fun isCatalogEnabled(catalogKey: String): Boolean = catalogKey in getEnabledCatalogKeys()

    suspend fun setCatalogEnabled(catalogKey: String, enabled: Boolean) {
        context.tvChannelDataStore.edit { prefs ->
            val current = prefs[enabledCatalogKeysKey].orEmpty().toMutableSet()
            if (enabled) current.add(catalogKey) else current.remove(catalogKey)
            if (current.isEmpty()) prefs.remove(enabledCatalogKeysKey)
            else prefs[enabledCatalogKeysKey] = current
        }
    }

    private fun catalogChannelIdKey(catalogKey: String) =
        longPreferencesKey("catalog_channel_id_${sha256(catalogKey)}")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
