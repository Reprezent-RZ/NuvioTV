package com.nuvio.tv.core.sync.androidtv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionFolder
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TV_HOME_TAG = "TvHomeCatalogSync"
private const val COLLECTION_PROVIDER_PREFIX = "nuvio_collection:"

@Singleton
class AndroidTvHomeChannelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TvChannelPreferences
) {
    fun isSupported(): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    } catch (_: Exception) {
        false
    }

    suspend fun ensureCatalogChannel(catalogKey: String, displayName: String): Long? =
        withContext(Dispatchers.IO) {
            if (!isSupported()) return@withContext null
            runCatching {
                val stored = prefs.getCatalogChannelId(catalogKey)
                if (stored != null && channelExists(stored)) {
                    updateChannelName(stored, displayName)
                    return@runCatching stored
                }
                if (stored != null) prefs.clearCatalogChannelId(catalogKey)

                val providerId = catalogProviderId(catalogKey)
                val orphan = findChannelByProviderId(providerId)
                if (orphan != null) {
                    prefs.setCatalogChannelId(catalogKey, orphan)
                    updateChannelName(orphan, displayName)
                    writeChannelLogo(orphan)
                    return@runCatching orphan
                }

                val channel = Channel.Builder()
                    .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                    .setDisplayName(displayName)
                    .setInternalProviderId(providerId)
                    .setAppLinkIntentUri(buildAppLinkUri())
                    .build()
                val inserted = context.contentResolver.insert(
                    TvContractCompat.Channels.CONTENT_URI,
                    channel.toContentValues()
                ) ?: return@runCatching null
                val id = ContentUris.parseId(inserted)
                prefs.setCatalogChannelId(catalogKey, id)
                writeChannelLogo(id)
                TvContractCompat.requestChannelBrowsable(context, id)
                id
            }.onFailure { Log.w(TV_HOME_TAG, "ensureCatalogChannel failed for $catalogKey", it) }
                .getOrNull()
        }

    suspend fun reconcileCatalog(
        catalogKey: String,
        displayName: String,
        items: List<MetaPreview>,
        maxItems: Int = 20
    ) = withContext(Dispatchers.IO) {
        if (!isSupported() || !prefs.isCatalogEnabled(catalogKey)) return@withContext
        runCatching {
            val channelId = ensureCatalogChannel(catalogKey, displayName) ?: return@runCatching
            val desired = items.distinctBy(::catalogProgramKey).take(maxItems.coerceIn(1, 50))
            val existing = queryExistingPrograms(channelId)
            val desiredKeys = desired.mapTo(mutableSetOf(), ::catalogProgramKey)
            existing.forEach { (key, rowIds) ->
                if (key !in desiredKeys) rowIds.forEach { deleteProgram(it) }
            }
            desired.forEachIndexed { index, item ->
                val key = catalogProgramKey(item)
                val values = buildCatalogProgramValues(item, channelId, index, key)
                upsertProgram(existing[key], values)
            }
        }.onFailure { Log.w(TV_HOME_TAG, "reconcileCatalog failed for $catalogKey", it) }
    }

    suspend fun removeCatalogChannel(catalogKey: String) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val channelId = prefs.getCatalogChannelId(catalogKey)
                ?: findChannelByProviderId(catalogProviderId(catalogKey))
            if (channelId != null) {
                context.contentResolver.delete(TvContractCompat.buildChannelUri(channelId), null, null)
            }
            prefs.clearCatalogChannelId(catalogKey)
        }.onFailure { Log.w(TV_HOME_TAG, "removeCatalogChannel failed for $catalogKey", it) }
    }

    suspend fun reconcileCollections(collections: List<Collection>) = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext
        runCatching {
            val usable = collections
                .map { collection -> collection.copy(folders = collection.folders.distinctBy { it.id }) }
                .filter { it.folders.isNotEmpty() }
            val desiredProviderIds = usable.mapTo(mutableSetOf()) { collectionProviderId(it.id) }
            removeStaleCollectionChannels(desiredProviderIds)

            usable.forEach { collection ->
                val name = collection.title.trim().ifBlank { "Nuvio Collections" }
                val channelId = ensureCollectionChannel(collection.id, name) ?: return@forEach
                val existing = queryExistingPrograms(channelId)
                val desiredKeys = collection.folders.mapTo(mutableSetOf()) {
                    collectionFolderProgramKey(collection.id, it.id)
                }
                existing.forEach { (key, rowIds) ->
                    if (key !in desiredKeys) rowIds.forEach { deleteProgram(it) }
                }
                collection.folders.forEachIndexed { index, folder ->
                    val key = collectionFolderProgramKey(collection.id, folder.id)
                    val values = buildCollectionFolderProgramValues(collection, folder, channelId, index, key)
                    upsertProgram(existing[key], values)
                }
            }
        }.onFailure { Log.w(TV_HOME_TAG, "reconcileCollections failed", it) }
    }

    private suspend fun ensureCollectionChannel(collectionId: String, displayName: String): Long? =
        withContext(Dispatchers.IO) {
            val providerId = collectionProviderId(collectionId)
            val existing = findChannelByProviderId(providerId)
            if (existing != null) {
                updateChannelName(existing, displayName)
                writeChannelLogo(existing)
                return@withContext existing
            }
            val channel = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(displayName)
                .setInternalProviderId(providerId)
                .setAppLinkIntentUri(buildAppLinkUri())
                .build()
            val inserted = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
            ) ?: return@withContext null
            val id = ContentUris.parseId(inserted)
            writeChannelLogo(id)
            TvContractCompat.requestChannelBrowsable(context, id)
            id
        }

    private fun removeStaleCollectionChannels(desiredProviderIds: Set<String>) {
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(TvContractCompat.Channels._ID)
            val providerIndex = cursor.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            if (idIndex < 0 || providerIndex < 0) return@use
            while (cursor.moveToNext()) {
                val providerId = cursor.getString(providerIndex) ?: continue
                if (providerId.startsWith(COLLECTION_PROVIDER_PREFIX) && providerId !in desiredProviderIds) {
                    context.contentResolver.delete(
                        TvContractCompat.buildChannelUri(cursor.getLong(idIndex)), null, null
                    )
                }
            }
        }
    }

    private fun queryExistingPrograms(channelId: Long): Map<String, List<Long>> {
        val result = mutableMapOf<String, MutableList<Long>>()
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            arrayOf(
                TvContractCompat.PreviewPrograms._ID,
                TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID
            ),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms._ID)
            val channelIndex = cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            val keyIndex = cursor.getColumnIndexOrThrow(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (cursor.moveToNext()) {
                if (cursor.getLong(channelIndex) != channelId) continue
                val key = cursor.getString(keyIndex) ?: continue
                result.getOrPut(key) { mutableListOf() }.add(cursor.getLong(idIndex))
            }
        }
        return result
    }

    private fun upsertProgram(rowIds: List<Long>?, values: ContentValues) {
        if (!rowIds.isNullOrEmpty()) {
            context.contentResolver.update(
                TvContractCompat.buildPreviewProgramUri(rowIds.first()), values, null, null
            )
            rowIds.drop(1).forEach(::deleteProgram)
        } else {
            context.contentResolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values)
        }
    }

    private fun deleteProgram(rowId: Long) {
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(rowId), null, null)
    }

    private fun buildCatalogProgramValues(
        item: MetaPreview,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("nuvio://meta?type=${Uri.encode(item.apiType)}&id=${Uri.encode(item.id)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
        val programType = if (item.apiType.equals("series", true) || item.apiType.equals("tv", true)) {
            TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
        } else {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(item.name)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)
        item.description?.takeIf(String::isNotBlank)?.let(builder::setDescription)
        val (imageUri, aspectRatio) = when {
            !item.background.isNullOrBlank() -> item.background to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !item.landscapePoster.isNullOrBlank() -> item.landscapePoster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            !item.poster.isNullOrBlank() -> item.poster to TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            else -> null to null
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio!!) }
        item.logo?.takeIf(String::isNotBlank)?.let { builder.setLogoUri(Uri.parse(it)) }
        return builder.build().toContentValues().also { values ->
            values.put("last_engagement_time_utc_millis", System.currentTimeMillis() - sortOrder)
            if (imageUri == null) values.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
            if (item.logo.isNullOrBlank()) values.putNull(TvContractCompat.PreviewPrograms.COLUMN_LOGO_URI)
        }
    }

    private fun buildCollectionFolderProgramValues(
        collection: Collection,
        folder: CollectionFolder,
        channelId: Long,
        sortOrder: Int,
        key: String
    ): ContentValues {
        val intentUri = Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("nuvio://folder/${Uri.encode(collection.id)}/${Uri.encode(folder.id)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(folder.title)
            .setDescription(collection.title)
            .setIntentUri(intentUri)
            .setInternalProviderId(key)
            .setWeight(Int.MAX_VALUE - sortOrder)
        val imageUri = folder.coverImageUrl?.takeIf(String::isNotBlank)
            ?: folder.focusGifUrl?.takeIf { folder.focusGifEnabled && it.isNotBlank() }
        val aspectRatio = when (folder.tileShape) {
            PosterShape.POSTER -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            PosterShape.LANDSCAPE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            PosterShape.SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
        }
        imageUri?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(aspectRatio) }
        return builder.build().toContentValues().also { values ->
            values.put("last_engagement_time_utc_millis", System.currentTimeMillis() - sortOrder)
            if (imageUri == null) values.putNull(TvContractCompat.PreviewPrograms.COLUMN_POSTER_ART_URI)
        }
    }

    private fun channelExists(channelId: Long): Boolean =
        context.contentResolver.query(
            TvContractCompat.buildChannelUri(channelId),
            arrayOf(TvContractCompat.Channels._ID), null, null, null
        )?.use { it.moveToFirst() } == true

    private fun findChannelByProviderId(providerId: String): Long? =
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(TvContractCompat.Channels._ID)
            val providerIndex = cursor.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            if (idIndex < 0 || providerIndex < 0) return@use null
            while (cursor.moveToNext()) {
                if (cursor.getString(providerIndex) == providerId) return@use cursor.getLong(idIndex)
            }
            null
        }

    private fun updateChannelName(channelId: Long, displayName: String) {
        val values = ContentValues().apply {
            put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, displayName)
        }
        context.contentResolver.update(TvContractCompat.buildChannelUri(channelId), values, null, null)
    }

    private fun buildAppLinkUri(): Uri = Uri.parse(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.toUri(Intent.URI_INTENT_SCHEME)
    )

    private fun catalogProgramKey(item: MetaPreview): String = "${item.apiType}:${item.id}"
    private fun catalogProviderId(catalogKey: String): String = "nuvio_catalog:${sha256(catalogKey)}"
    private fun collectionProviderId(collectionId: String): String = "$COLLECTION_PROVIDER_PREFIX${sha256(collectionId)}"
    private fun collectionFolderProgramKey(collectionId: String, folderId: String): String =
        "collection_folder:${sha256("$collectionId|$folderId")}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun writeChannelLogo(channelId: Long) {
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher) ?: return
            context.contentResolver.openOutputStream(TvContractCompat.buildChannelLogoUri(channelId))?.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }.onFailure { Log.w(TV_HOME_TAG, "writeChannelLogo failed", it) }
    }
}
