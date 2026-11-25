package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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

    // Same shape, for [seasonPoster]. Kept separate from the banner cache so a title that has one
    // and not the other does not poison the lookup for the other.
    private val posterCache = mutableMapOf<String, String?>()

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
        when (val stored = AnimeArtworkDiskCache.lookup("backdrop|$cacheKey")) {
            is AnimeArtworkDiskCache.Lookup.Found -> {
                cacheMutex.withLock { cache[cacheKey] = stored.url }
                return stored.url
            }
            AnimeArtworkDiskCache.Lookup.KnownNone -> {
                cacheMutex.withLock { cache[cacheKey] = null }
                return null
            }
            AnimeArtworkDiskCache.Lookup.Unknown -> Unit
        }

        // Kitsu first: same key art as AniList in most cases but at 3360×800 vs ~1900×400,
        // which survives the hero's vertical crop much better.
        val backdrop = firstFound(
            { ids.kitsu?.let { fetchKitsuCover(it) } },
            { ids.anilist?.let { fetchAniListBanner(it) } },
        )
        return remember(backdrop, "backdrop|$cacheKey") { url -> cache[cacheKey] = url }
    }

    /**
     * Keeps [result] in memory and on disk when it is an answer (found, or none from every provider
     * asked); a failed lookup is kept nowhere, so it is tried again later. Returns the url, if any.
     */
    private suspend fun remember(result: ArtResult, diskKey: String, inMemory: (String?) -> Unit): String? =
        when (result) {
            is ArtResult.Found -> {
                cacheMutex.withLock { inMemory(result.url) }
                AnimeArtworkDiskCache.put(diskKey, result.url)
                result.url
            }
            ArtResult.None -> {
                cacheMutex.withLock { inMemory(null) }
                AnimeArtworkDiskCache.putNone(diskKey)
                null
            }
            ArtResult.Failed -> null
        }

    /** The first provider that found art; none when every provider asked answered without; failed otherwise. */
    private suspend fun firstFound(vararg providers: suspend () -> ArtResult?): ArtResult {
        var failed = false
        for (provider in providers) {
            when (val result = provider() ?: continue) {
                is ArtResult.Found -> return result
                ArtResult.None -> Unit
                ArtResult.Failed -> failed = true
            }
        }
        return if (failed) ArtResult.Failed else ArtResult.None
    }

    private sealed interface ArtResult {
        data class Found(val url: String) : ArtResult
        /** The provider answered: it has no such picture. */
        data object None : ArtResult
        /** No answer (timeout, error, rate limit): says nothing about the title. */
        data object Failed : ArtResult
    }

    private fun String?.asArt(): ArtResult = this?.takeIf { it.isNotBlank() }?.let { ArtResult.Found(it) } ?: ArtResult.None

    /**
     * Entry-specific **portrait poster** for [nativeId], straight from Kitsu/AniList.
     *
     * Unlike [seasonBackdrop] this does not skip franchise-anchor entries: the point here is not
     * per-season accuracy but having a *second* poster that is certain to be a live, public CDN
     * URL. Discord Rich Presence needs that — it fetches artwork from its own servers via an
     * image proxy, and when the poster it was handed cannot be fetched the proxy silently serves
     * the fallback instead. For anime that fallback used to be the episode still, so a user who
     * asked for posters saw thumbnails and neither the app nor they could tell why. Common causes
     * are addon payloads still carrying `media.kitsu.io` URLs (that host now answers 404 for every
     * image; the live API returns `media.kitsu.app`) and self-hosted poster services.
     *
     * Preference: Kitsu `posterImage` (original, then large) → AniList `coverImage`
     * (extraLarge, then large). Both public and keyless, same as the banner lookup.
     */
    suspend fun seasonPoster(nativeId: String): String? {
        val ids = animeArtworkLookupIds(nativeId) ?: return null
        val cacheKey = nativeId.nativeAnimeBase().lowercase()
        cacheMutex.withLock { if (posterCache.containsKey(cacheKey)) return posterCache[cacheKey] }
        when (val stored = AnimeArtworkDiskCache.lookup("poster|$cacheKey")) {
            is AnimeArtworkDiskCache.Lookup.Found -> {
                cacheMutex.withLock { posterCache[cacheKey] = stored.url }
                return stored.url
            }
            AnimeArtworkDiskCache.Lookup.KnownNone -> {
                cacheMutex.withLock { posterCache[cacheKey] = null }
                return null
            }
            AnimeArtworkDiskCache.Lookup.Unknown -> Unit
        }

        val poster = firstFound(
            { ids.kitsu?.let { fetchKitsuPoster(it) } },
            { ids.anilist?.let { fetchAniListCover(it) } },
        )
        return remember(poster, "poster|$cacheKey") { url -> posterCache[cacheKey] = url }
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

    private suspend fun fetchAniListBanner(anilistId: Int): ArtResult =
        aniListMedia(anilistId, fields = "bannerImage") { media -> media?.bannerImage.asArt() }

    /**
     * One AniList `Media` query through [AniListGate]. A 404 is an answer (no such entry); a 429 starts the gate's
     * cooldown; anything else that is not a 2xx, or no answer at all, is [ArtResult.Failed].
     */
    private suspend fun aniListMedia(
        anilistId: Int,
        fields: String,
        pick: (AniListMedia?) -> ArtResult,
    ): ArtResult = AniListGate.run {
        runCatchingNonCancellable {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                val body = """{"query":"query(${'$'}id:Int){Media(id:${'$'}id,type:ANIME){$fields}}","variables":{"id":$anilistId}}"""
                val response = httpRequestRaw(
                    method = "POST",
                    url = "https://graphql.anilist.co",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                    body = body,
                    callTimeoutMs = FETCH_TIMEOUT_MS,
                )
                when (response.status) {
                    in 200..299 -> pick(json.decodeFromString<AniListResponse>(response.body).data?.media)
                    404 -> ArtResult.None
                    429 -> {
                        val retryAfter = response.headers.entries
                            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                            ?.value?.trim()?.toLongOrNull()
                        AniListGate.coolDown(retryAfter)
                        ArtResult.Failed
                    }
                    else -> ArtResult.Failed
                }
            }
        }
    } ?: ArtResult.Failed

    /**
     * AniList's public API allows 30 requests a minute while degraded (90 normally) and answers 429 past it. Home
     * asked it about every Kitsu-less anime at once at launch: 23 calls in 4 s, 16 refused (2026-11-25). Requests now
     * go one at a time, [SpacingMs] apart, and after a 429 none are sent until its Retry-After (or a minute) has
     * passed; lookups in that time count as failed, so they are tried again later rather than remembered as "none".
     */
    private object AniListGate {
        private const val SpacingMs = 2_000L
        private const val DefaultCooldownMs = 60_000L
        private val mutex = Mutex()
        private var nextAllowedAtMs = 0L
        @kotlin.concurrent.Volatile private var cooldownUntilMs = 0L

        private fun now() = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()

        suspend fun <T> run(block: suspend () -> T): T? {
            if (now() < cooldownUntilMs) return null
            mutex.withLock {
                val waitMs = nextAllowedAtMs - now()
                if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                nextAllowedAtMs = now() + SpacingMs
            }
            if (now() < cooldownUntilMs) return null
            return block()
        }

        fun coolDown(retryAfterSeconds: Long?) {
            val ms = retryAfterSeconds?.takeIf { it in 1..600 }?.times(1_000) ?: DefaultCooldownMs
            cooldownUntilMs = maxOf(cooldownUntilMs, now() + ms)
            log.i { "AniList rate limit: no requests for ${ms / 1_000} s" }
        }
    }

    private suspend fun fetchKitsuCover(kitsuId: Int): ArtResult {
        val attributes = fetchKitsuAttributes(kitsuId) ?: return ArtResult.Failed
        val cover = attributes.coverImage
        return (cover?.original ?: cover?.large).asArt()
    }

    // One Kitsu request per entry fills both the cover and the poster (optimization audit B4: the two lookups asked
    // for the same URL separately, 2,576 Kitsu calls in a day), shared by concurrent callers. A failed request is not
    // kept, so it can be tried again; an entry with no art is.
    private val kitsuEntries = mutableMapOf<Int, kotlinx.coroutines.Deferred<KitsuAttributes?>>()
    private val kitsuScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private suspend fun fetchKitsuAttributes(kitsuId: Int): KitsuAttributes? {
        val pending = cacheMutex.withLock {
            kitsuEntries.getOrPut(kitsuId) {
                kitsuScope.async {
                    val attributes = runCatchingNonCancellable {
                        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                            val response = httpRequestRaw(
                                method = "GET",
                                url = "https://kitsu.io/api/edge/anime/$kitsuId",
                                headers = mapOf("Accept" to "application/vnd.api+json"),
                                body = "",
                                callTimeoutMs = FETCH_TIMEOUT_MS,
                            )
                            // No such entry is an answer (no art), not a failure to retry.
                            if (response.status == 404) return@withTimeoutOrNull KitsuAttributes()
                            if (response.status !in 200..299 || response.body.isBlank()) return@withTimeoutOrNull null
                            json.decodeFromString<KitsuResponse>(response.body).data?.attributes ?: KitsuAttributes()
                        }
                    }
                    if (attributes == null) cacheMutex.withLock { kitsuEntries.remove(kitsuId) }
                    attributes
                }
            }
        }
        return pending.await()
    }

    private suspend fun fetchAniListCover(anilistId: Int): ArtResult =
        aniListMedia(anilistId, fields = "coverImage{extraLarge large}") { media ->
            (media?.coverImage?.extraLarge ?: media?.coverImage?.large).asArt()
        }

    private suspend fun fetchKitsuPoster(kitsuId: Int): ArtResult {
        val attributes = fetchKitsuAttributes(kitsuId) ?: return ArtResult.Failed
        val poster = attributes.posterImage
        return (poster?.original ?: poster?.large).asArt()
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
private data class AniListMedia(
    val bannerImage: String? = null,
    val coverImage: AniListCoverImage? = null,
)

@Serializable
private data class AniListCoverImage(val extraLarge: String? = null, val large: String? = null)

@Serializable
private data class KitsuResponse(val data: KitsuData? = null)

@Serializable
private data class KitsuData(val attributes: KitsuAttributes? = null)

@Serializable
private data class KitsuAttributes(
    val coverImage: KitsuCoverImage? = null,
    val posterImage: KitsuPosterImage? = null,
)

@Serializable
private data class KitsuPosterImage(val original: String? = null, val large: String? = null)

@Serializable
private data class KitsuCoverImage(val original: String? = null, val large: String? = null)
