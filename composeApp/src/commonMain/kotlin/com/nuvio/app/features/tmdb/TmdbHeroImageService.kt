package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches high-quality hero backdrops and logos directly from the TMDB images endpoint,
 * bypassing the addon meta pipeline which often returns lower-resolution CDN mirrors.
 *
 * Uses [TmdbService.ensureTmdbId] for IMDB→TMDB resolution (already cached there) and
 * maintains its own image cache so re-navigating to the same item never re-fetches.
 *
 * Requires a TMDB API key in [TmdbSettingsRepository]. Returns null if none is set.
 */
object TmdbHeroImageService {

    data class HeroImages(
        val backdrop: String?,
        val logo: String?,
    )

    private val log = Logger.withTag("TmdbHeroImageService")
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = linkedMapOf<String, HeroImages>()
    private val mutex = Mutex()

    /** Returns null if TMDB is not configured or the item has no resolvable TMDB ID. */
    suspend fun fetch(type: String, id: String): HeroImages? {
        val apiKey = TmdbSettingsRepository.snapshot().apiKey.trim()
            .takeIf(String::isNotBlank) ?: return null

        val cacheKey = "$type:$id"
        mutex.withLock { cache[cacheKey] }?.let { return it }

        val tmdbId = TmdbService.ensureTmdbId(id, type) ?: return null
        val tmdbType = TmdbService.normalizeMediaType(type)
            .let { if (it == "movie") "movie" else "tv" }

        val images = fetchImages(tmdbId = tmdbId, tmdbType = tmdbType, apiKey = apiKey)
            ?: return null

        val normalizedLanguage = TmdbSettingsRepository.snapshot().language
            .trim().takeIf(String::isNotBlank) ?: "en"

        val backdropPath = images.backdrops
            .filter { !it.filePath.isNullOrBlank() }
            .maxByOrNull { it.voteAverage ?: 0.0 }
            ?.filePath
        val logoPath = images.logos
            .filter { !it.filePath.isNullOrBlank() }
            .selectBestLogo(normalizedLanguage)
            ?.filePath

        val result = HeroImages(
            backdrop = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            logo = logoPath?.let { "https://image.tmdb.org/t/p/original$it" },
        )

        mutex.withLock { cache[cacheKey] = result }
        return result
    }

    fun clearCache() {
        cache.clear()
    }

    private suspend fun fetchImages(
        tmdbId: String,
        tmdbType: String,
        apiKey: String,
    ): TmdbHeroImagesResponse? {
        val url = buildTmdbUrl(
            endpoint = "$tmdbType/$tmdbId/images",
            apiKey = apiKey,
            query = mapOf("include_image_language" to "en,null"),
        )
        return runCatching {
            json.decodeFromString<TmdbHeroImagesResponse>(httpGetText(url))
        }.onFailure { err ->
            log.w { "TMDB images fetch failed for $tmdbType/$tmdbId: ${err.message}" }
        }.getOrNull()
    }

    // Prefer English, then language-neutral (null), then anything.
    private fun List<TmdbHeroImage>.selectBestLogo(normalizedLanguage: String): TmdbHeroImage? {
        val langCode = normalizedLanguage.substringBefore("-")
        return sortedWith(
            compareByDescending<TmdbHeroImage> { it.iso6391 == langCode }
                .thenByDescending { it.iso6391 == "en" }
                .thenByDescending { it.iso6391 == null },
        ).firstOrNull()
    }
}

@Serializable
private data class TmdbHeroImagesResponse(
    val backdrops: List<TmdbHeroImage> = emptyList(),
    val logos: List<TmdbHeroImage> = emptyList(),
)

@Serializable
private data class TmdbHeroImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
)
