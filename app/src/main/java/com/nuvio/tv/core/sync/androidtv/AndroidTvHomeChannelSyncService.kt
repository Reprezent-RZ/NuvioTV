package com.nuvio.tv.core.sync.androidtv

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.catalogRowStableKey
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TV_HOME_SYNC_TAG = "TvHomeCatalogSync"
private const val TV_HOME_DEBOUNCE_MS = 500L
private const val MAX_TV_HOME_CATALOG_ITEMS = 20

@Singleton
class AndroidTvHomeChannelSyncService @Inject constructor(
    private val manager: AndroidTvHomeChannelManager,
    private val prefs: TvChannelPreferences,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val collectionsDataStore: CollectionsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogMutex = Mutex()
    private val collectionMutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start() {
        if (!manager.isSupported()) return
        scope.launch { refreshNow() }

        scope.launch {
            combine(
                addonRepository.getInstalledAddons().distinctUntilChanged(),
                prefs.enabledCatalogKeys.distinctUntilChanged()
            ) { installed, enabled -> installed.enabledAddons() to enabled }
                .debounce(TV_HOME_DEBOUNCE_MS)
                .collect { (addons, enabled) -> syncSelectedCatalogs(addons, enabled) }
        }

        scope.launch {
            collectionsDataStore.collections
                .distinctUntilChanged()
                .debounce(TV_HOME_DEBOUNCE_MS)
                .collect { collections ->
                    collectionMutex.withLock { manager.reconcileCollections(collections) }
                }
        }
    }

    suspend fun refreshNow() {
        if (!manager.isSupported()) return
        val enabled = prefs.getEnabledCatalogKeys()
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        syncSelectedCatalogs(addons, enabled)
        val collections = collectionsDataStore.collections.first()
        collectionMutex.withLock { manager.reconcileCollections(collections) }
    }

    private suspend fun syncSelectedCatalogs(addons: List<Addon>, enabledCatalogKeys: Set<String>) {
        catalogMutex.withLock {
            val available = buildMap<String, Pair<Addon, CatalogDescriptor>> {
                addons.forEach { addon ->
                    addon.catalogs.filterNot { it.isSearchOnlyTvCatalog() }.forEach { catalog ->
                        val key = catalogRowStableKey(
                            addonId = addon.id,
                            addonBaseUrl = addon.baseUrl,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        put(key, addon to catalog)
                    }
                }
            }

            enabledCatalogKeys.forEach { key ->
                val pair = available[key]
                if (pair == null) {
                    manager.removeCatalogChannel(key)
                    return@forEach
                }
                val (addon, catalog) = pair
                val result = runCatching {
                    catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skip = 0,
                        skipStep = catalog.skipStep(),
                        supportsSkip = catalog.supportsExtra("skip")
                    ).first { it !is NetworkResult.Loading }
                }.getOrElse { error ->
                    Log.w(TV_HOME_SYNC_TAG, "catalog fetch failed key=$key", error)
                    return@forEach
                }
                if (result is NetworkResult.Success) {
                    manager.reconcileCatalog(
                        catalogKey = key,
                        displayName = buildCatalogChannelName(addon, catalog),
                        items = result.data.items,
                        maxItems = MAX_TV_HOME_CATALOG_ITEMS
                    )
                }
            }
        }
    }

    private fun buildCatalogChannelName(addon: Addon, catalog: CatalogDescriptor): String {
        val catalogName = catalog.name.trim().ifBlank { catalog.id }
        val addonName = addon.displayName.trim().ifBlank { addon.name }
        return "$catalogName · $addonName"
    }

    private fun CatalogDescriptor.isSearchOnlyTvCatalog(): Boolean =
        extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired } ||
            extraRequired.any { it.equals("search", ignoreCase = true) }
}
