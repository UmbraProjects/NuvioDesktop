package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Season-specific wide artwork for native anime ids (`kitsu:`/`mal:`/`anilist:`/`anidb:`).
 *
 * TMDB/TVDB treat a whole anime franchise as one show id, so every per-season kitsu catalog
 * entry (SAO, SAO II, Alicization…) gets the same franchise backdrop. AniList and Kitsu carry
 * a wide banner *per entry* — i.e. per season — keyed by the ids the anime-list mapping
 * already resolves. Preference: Kitsu cover (higher resolution, 3360×800) → AniList banner.
 * Both APIs are public and keyless.
 */
internal object AnimeArtworkService {
    private val log = Logger.withTag("AnimeArtwork")
    private val json = Json { ignoreUnknownKeys = true }
    private const val FETCH_TIMEOUT_MS = 4_000L

    // Base id ("kitsu:8174") → resolved banner URL, null = looked up and none available.
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, String?>()

    /**
     * Season-specific backdrop URL for [nativeId] (any native anime id shape — episode and
     * season segments are ignored), or null when the id isn't anime-native, the franchise
     * art already matches the entry ([usesFranchiseArt]), or neither provider has a banner.
     */
    suspend fun seasonBackdrop(nativeId: String): String? {
        if (usesFranchiseArt(nativeId)) return null
        val ids = animeArtworkLookupIds(nativeId) ?: return null
        val cacheKey = nativeId.nativeAnimeBase().lowercase()
        cacheMutex.withLock { if (cache.containsKey(cacheKey)) return cache[cacheKey] }

        // Kitsu first: same key art as AniList in most cases but at 3360×800 vs ~1900×400,
        // which survives the hero's vertical crop much better.
        val backdrop = ids.kitsu?.let { fetchKitsuCover(it) }
            ?: ids.anilist?.let { fetchAniListBanner(it) }
        cacheMutex.withLock { cache[cacheKey] = backdrop }
        return backdrop
    }

    /**
     * True when the TMDB/addon franchise art already *is* this entry's art: the franchise
     * anchor (mapped season 1 with no cour offset) or a movie with its own TMDB movie id.
     * Those keep the fast TMDB pipeline — per-season art is only worth a (slower) Kitsu
     * lookup for the entries the franchise backdrop would misrepresent (seasons 2+,
     * specials, later cours).
     */
    internal fun usesFranchiseArt(nativeId: String): Boolean {
        if (!nativeId.hasAnimeNamespacePrefix()) return false
        val entry = mappingEntryFor(nativeId) ?: return false
        if (entry.tmdbMovieIds.isNotEmpty()) return true
        val season = entry.tvdbSeason ?: entry.tmdbSeason ?: return false
        val offset = entry.tvdbEpisodeOffset ?: entry.tmdbEpisodeOffset ?: 0
        return season == 1 && offset == 0 && (entry.tvdbId != null || entry.tmdbTvId != null)
    }

    internal data class ArtworkLookupIds(val anilist: Int?, val kitsu: Int?)

    /**
     * AniList/Kitsu entry ids for [nativeId], filling the missing namespace via the
     * anime-list mapping (a `kitsu:` id gains its AniList sibling and vice versa).
     */
    internal fun animeArtworkLookupIds(nativeId: String): ArtworkLookupIds? {
        val parsed = parseNativeAnimeIds(nativeId) ?: return null
        val entry = AnimeIdMappingRepository.entryForNativeIds(
            anidb = parsed.anidb,
            anilist = parsed.anilist,
            kitsu = parsed.kitsu,
            mal = parsed.mal,
        )
        val resolvedAnilist = parsed.anilist ?: entry?.anilistId
        val resolvedKitsu = parsed.kitsu ?: entry?.kitsuId
        if (resolvedAnilist == null && resolvedKitsu == null) return null
        return ArtworkLookupIds(anilist = resolvedAnilist, kitsu = resolvedKitsu)
    }

    private fun mappingEntryFor(nativeId: String): AnimeIdMapping? {
        val parsed = parseNativeAnimeIds(nativeId) ?: return null
        return AnimeIdMappingRepository.entryForNativeIds(
            anidb = parsed.anidb,
            anilist = parsed.anilist,
            kitsu = parsed.kitsu,
            mal = parsed.mal,
        )
    }

    private data class ParsedNativeIds(
        val kitsu: Int? = null,
        val mal: Int? = null,
        val anilist: Int? = null,
        val anidb: Int? = null,
    )

    private fun parseNativeAnimeIds(nativeId: String): ParsedNativeIds? {
        if (!nativeId.hasAnimeNamespacePrefix()) return null
        val parts = nativeId.split(':')
        val value = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return when (parts[0].lowercase()) {
            "kitsu" -> ParsedNativeIds(kitsu = value)
            "mal", "myanimelist" -> ParsedNativeIds(mal = value)
            "al", "anilist" -> ParsedNativeIds(anilist = value)
            "anidb" -> ParsedNativeIds(anidb = value)
            else -> null
        }
    }

    private suspend fun fetchAniListBanner(anilistId: Int): String? =
        runCatchingNonCancellable {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                val body = """{"query":"query(${'$'}id:Int){Media(id:${'$'}id,type:ANIME){bannerImage}}","variables":{"id":$anilistId}}"""
                val response = httpRequestRaw(
                    method = "POST",
                    url = "https://graphql.anilist.co",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                    body = body,
                )
                if (response.status !in 200..299) return@withTimeoutOrNull null
                json.decodeFromString<AniListResponse>(response.body)
                    .data?.media?.bannerImage?.takeIf { it.isNotBlank() }
            }
        }

    private suspend fun fetchKitsuCover(kitsuId: Int): String? =
        runCatchingNonCancellable {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                val payload = httpGetTextWithHeaders(
                    url = "https://kitsu.io/api/edge/anime/$kitsuId",
                    headers = mapOf("Accept" to "application/vnd.api+json"),
                )
                val cover = json.decodeFromString<KitsuResponse>(payload).data?.attributes?.coverImage
                (cover?.original ?: cover?.large)?.takeIf { it.isNotBlank() }
            }
        }

    private inline fun <T> runCatchingNonCancellable(block: () -> T?): T? =
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.d { "Anime artwork lookup failed: ${error.message}" }
            null
        }
}

/** True for URLs served by the per-season anime artwork providers (AniList/Kitsu CDNs). */
internal fun String?.isAnimeSeasonArtUrl(): Boolean =
    this != null && (contains("anilistcdn") || contains("media.kitsu."))

@Serializable
private data class AniListResponse(val data: AniListData? = null)

@Serializable
private data class AniListData(@SerialName("Media") val media: AniListMedia? = null)

@Serializable
private data class AniListMedia(val bannerImage: String? = null)

@Serializable
private data class KitsuResponse(val data: KitsuData? = null)

@Serializable
private data class KitsuData(val attributes: KitsuAttributes? = null)

@Serializable
private data class KitsuAttributes(val coverImage: KitsuCoverImage? = null)

@Serializable
private data class KitsuCoverImage(val original: String? = null, val large: String? = null)
