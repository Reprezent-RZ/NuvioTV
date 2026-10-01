package com.nuvio.tv.data.webshare

import android.util.Log
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Optional overlay for the Reprezent WebShare SQL catalog.
 *
 * The regular episode metadata still comes from the user's selected meta addon
 * (Cinemeta, AIO, TMDB, ...). This service only asks the installed WS catalog
 * which S/E pairs exist on WebShare and which of those are NEW.
 *
 * Returned map semantics:
 *   key present + false = available on WS
 *   key present + true  = NEW on WS
 *   key absent           = no WS badge
 */
@Singleton
class WsEpisodeStatusService @Inject constructor(
    private val addonRepository: AddonRepository,
    @param:Named("addonPermissive") private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "WsEpisodeStatus"
        private const val ADDON_ID_PREFIX = "sk.reprezent22.webshare.sql.catalog"
        private const val ADDON_WAIT_MS = 1_500L
    }

    suspend fun fetchEpisodeFlags(contentIds: List<String>): Map<Pair<Int, Int>, Boolean> {
        val addons = withTimeoutOrNull(ADDON_WAIT_MS) {
            addonRepository.getInstalledAddons().first { it.isNotEmpty() }
        }.orEmpty()

        val wsAddon = addons.firstOrNull { addon ->
            addon.enabled && addon.id.startsWith(ADDON_ID_PREFIX)
        } ?: return emptyMap()

        val candidates = contentIds
            .mapNotNull(::normalizeContentId)
            .distinct()

        if (candidates.isEmpty()) return emptyMap()

        return withContext(Dispatchers.IO) {
            for (contentId in candidates) {
                val result = try {
                    fetchFromAddon(wsAddon.baseUrl, contentId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "WS badge lookup failed for $contentId: ${error.message}")
                    FetchResult(matchedMedia = false, flags = emptyMap())
                }

                if (result.matchedMedia) {
                    return@withContext result.flags
                }
            }
            emptyMap()
        }
    }

    private fun fetchFromAddon(baseUrl: String, contentId: String): FetchResult {
        val encodedId = URLEncoder.encode(
            contentId,
            StandardCharsets.UTF_8.toString()
        ).replace("+", "%20")

        val url = "${baseUrl.trimEnd('/')}/ws-status/series/$encodedId.json"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return FetchResult(matchedMedia = false, flags = emptyMap())
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return FetchResult(matchedMedia = false, flags = emptyMap())
            }

            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) {
                return FetchResult(matchedMedia = false, flags = emptyMap())
            }

            val mediaId = if (json.has("mediaId") && !json.isNull("mediaId")) {
                json.optLong("mediaId").takeIf { it > 0L }
            } else {
                null
            }

            if (mediaId == null) {
                return FetchResult(matchedMedia = false, flags = emptyMap())
            }

            val flags = linkedMapOf<Pair<Int, Int>, Boolean>()
            val episodes = json.optJSONArray("episodes")
                ?: return FetchResult(matchedMedia = true, flags = emptyMap())

            for (index in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(index) ?: continue
                val season = episode.optInt("season", 0)
                val number = episode.optInt("episode", 0)
                if (season <= 0 || number <= 0) continue

                val isNew = episode.optBoolean("new", false) ||
                    episode.optString("status").equals("new", ignoreCase = true)

                val key = season to number
                flags[key] = flags[key] == true || isNew
            }

            return FetchResult(matchedMedia = true, flags = flags)
        }
    }

    private fun normalizeContentId(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.matches(Regex("^tt\\d+$", RegexOption.IGNORE_CASE))) {
            return value.lowercase()
        }

        val tmdbMatch = Regex("^tmdb:(\\d+)$", RegexOption.IGNORE_CASE).matchEntire(value)
        if (tmdbMatch != null) {
            return "tmdb:${tmdbMatch.groupValues[1]}"
        }

        return null
    }

    private data class FetchResult(
        val matchedMedia: Boolean,
        val flags: Map<Pair<Int, Int>, Boolean>
    )
}
