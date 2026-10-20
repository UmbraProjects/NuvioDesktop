package com.nuvio.app.features.tmdb

import co.touchlab.kermit.Logger
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaPerson
import com.nuvio.app.features.details.MetaTrailer
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.quarantineMismatchedImdbTmdbIdentity
import com.nuvio.app.features.details.MoreLikeThisSource
import com.nuvio.app.features.details.PersonDetail
import com.nuvio.app.features.home.HeroAward
import com.nuvio.app.features.home.HeroDiscoveryAwards
import com.nuvio.app.features.home.HeroDiscoveryBadgeTarget
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.cacheKey
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.posterservice.CustomPosterKeys
import com.nuvio.app.features.posterservice.CustomPosterSettings
import com.nuvio.app.features.posterservice.CustomPosterScreen
import com.nuvio.app.features.posterservice.CustomPosterSettingsRepository
import com.nuvio.app.features.posterservice.cacheToken
import com.nuvio.app.features.posterservice.customPosterTemplateNeedsImdbId
import com.nuvio.app.features.posterservice.resolveCustomPosterIds
import com.nuvio.app.features.posterservice.withCustomPosters
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.preferPreciseReleaseDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlin.math.ceil
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object TmdbMetadataService {
    private val log = Logger.withTag("TmdbMetadata")
    private val json = Json { ignoreUnknownKeys = true }
    private val titleCompareYearRegex = Regex("""\b(19|20)\d{2}\b""")

    private val enrichmentCache = mutableMapOf<String, TmdbEnrichment>()
    /**
     * Episode enrichment keyed **per season**, as `"<tmdbId>:<season>:<language>"`.
     *
     * Previously keyed on the whole requested season *set*, which meant asking for [1,2,3] and then
     * [1,2,3,4] shared nothing and refetched the first three. Measured in a real log: **1928 season
     * requests for 584 distinct seasons — 69% redundant**, one show refetching the same season
     * twelve times. Per-season keys make a set request pay only for the seasons it has not seen.
     */
    private val episodeCache = mutableMapOf<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()

    /**
     * One lock per season being fetched, so concurrent callers wanting overlapping sets do not each
     * issue the same request. The cache alone cannot help them: it is only populated once a fetch
     * finishes, and a details screen opens several of these at once.
     */
    private val episodeFetchLocks = mutableMapOf<String, Mutex>()
    private val episodeCacheMutex = Mutex()

    private suspend fun episodeFetchLock(key: String): Mutex =
        episodeCacheMutex.withLock { episodeFetchLocks.getOrPut(key) { Mutex() } }
    private val moreLikeThisCache = mutableMapOf<String, List<MetaPreview>>()
    private val collectionCache = mutableMapOf<String, Pair<String?, List<MetaPreview>>>()
    private val trailerCache = mutableMapOf<String, List<MetaTrailer>>()

    /**
     * One lock per trailer lookup.
     *
     * The cache key was already correct; what it lacked was single-flight. A show's trailers are
     * wanted by the hero, the details screen and the trailer surface at roughly the same moment,
     * and each miss fans out over **every season** — so three concurrent callers on a 50-season
     * show issue 150 requests for the same answer. Measured: 548 season/videos calls for 313
     * distinct seasons, one show fetched seven times over.
     */
    private val trailerFetchLocks = mutableMapOf<String, Mutex>()

    /**
     * How many seasons of trailers a caller actually needs.
     *
     * The trailer lookup fans out over **every** season a show has — 51 requests on a 51-season
     * show. That is defensible on a details page, which is one title the user chose to open, and
     * indefensible on Home, which enriches a screenful of them at once. Measured in one browsing
     * session: 278 `season/videos` calls across 69 shows.
     *
     * [SingleSeason] asks for the show-level videos plus one season — the one the user is watching,
     * or the first when that is unknown, which is where a show's trailer usually lives anyway.
     */
    sealed interface TrailerScope {
        data object AllSeasons : TrailerScope

        data class SingleSeason(val preferred: Int?) : TrailerScope

        /** Part of both cache keys, so a Home fetch can never satisfy a details-page request. */
        val cacheTag: String
            get() = when (this) {
                AllSeasons -> "all"
                is SingleSeason -> "s${preferred ?: 1}"
            }
    }

    /**
     * How much of a title [enrichMeta] asks TMDB for.
     *
     * [Card] is for the background lookups behind rows, focused-hero prewarm, Search/Library
     * backdrops and Continue Watching: they read artwork, text, genres, rating, runtime, country
     * and language, and nothing else. Measured over a 4-minute session (2026-10-14), ~1,080 of
     * those lookups each also paid for credits, recommendations and trailers - about 2,300
     * requests no screen displayed. A Card record is cached under its own key, so it can never
     * stand in for the hero summary or the details page, which keep [Full].
     */
    enum class EnrichmentDepth { Full, Card }
    private val personCache = mutableMapOf<String, PersonDetail>()
    private val entityBrowseCache = mutableMapOf<String, TmdbEntityBrowseData>()
    private val entityHeaderCache = mutableMapOf<String, TmdbEntityHeader>()
    private val entityRailCache = mutableMapOf<String, TmdbEntityRailPageResult>()
    private val badgeRailCache = mutableMapOf<String, TmdbEntityRailPageResult>()
    private val entityPosterIdSemaphore = Semaphore(4)

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
    ): PersonDetail? = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext null
        val language = normalizeTmdbLanguage(settings.language)
        val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}:$language"
        personCache[cacheKey]?.let { return@withContext it }

        try {
            val (person, credits) = coroutineScope {
                val personDeferred = async {
                    fetch<TmdbPersonResponse>(
                        endpoint = "person/$personId",
                        query = mapOf("language" to language),
                    )
                }
                val creditsDeferred = async {
                    fetch<TmdbPersonCombinedCreditsResponse>(
                        endpoint = "person/$personId/combined_credits",
                        query = mapOf("language" to language),
                    )
                }
                personDeferred.await() to creditsDeferred.await()
            }

            if (person == null) return@withContext null

            val biography = if (person.biography.isNullOrBlank() && language != "en") {
                runCatching {
                    fetch<TmdbPersonResponse>(
                        endpoint = "person/$personId",
                        query = mapOf("language" to "en"),
                    )?.biography
                }.getOrNull()
            } else {
                person.biography
            }?.takeIf { it.isNotBlank() }

            val preferCrew = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

            val (castMovieCredits, crewMovieCredits, castTvCredits, crewTvCredits) = coroutineScope {
                val castMovieDeferred = async { mapPersonMovieCreditsFromCast(credits?.cast.orEmpty()) }
                val crewMovieDeferred = async { mapPersonMovieCreditsFromCrew(credits?.crew.orEmpty()) }
                val castTvDeferred = async { mapPersonTvCreditsFromCast(credits?.cast.orEmpty()) }
                val crewTvDeferred = async { mapPersonTvCreditsFromCrew(credits?.crew.orEmpty()) }
                PersonCreditBuckets(
                    castMovieCredits = castMovieDeferred.await(),
                    crewMovieCredits = crewMovieDeferred.await(),
                    castTvCredits = castTvDeferred.await(),
                    crewTvCredits = crewTvDeferred.await(),
                )
            }

            val detail = PersonDetail(
                tmdbId = person.id ?: personId,
                name = person.name ?: runBlocking { getString(Res.string.generic_unknown) },
                biography = biography,
                birthday = person.birthday?.takeIf { it.isNotBlank() },
                deathday = person.deathday?.takeIf { it.isNotBlank() },
                placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                profilePhoto = buildImageUrl(person.profilePath, "w500"),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                movieCredits = selectPreferredCredits(
                    preferCrew = preferCrew,
                    castCredits = castMovieCredits,
                    crewCredits = crewMovieCredits,
                ),
                tvCredits = selectPreferredCredits(
                    preferCrew = preferCrew,
                    castCredits = castTvCredits,
                    crewCredits = crewTvCredits,
                ),
            )
            personCache[cacheKey] = detail
            detail
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch person detail for $personId" }
            null
        }
    }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val department = knownForDepartment?.trim()?.lowercase() ?: return false
        return department.isNotBlank() && department != "acting" && department != "actors"
    }

    private data class PersonCreditBuckets(
        val castMovieCredits: List<MetaPreview>,
        val crewMovieCredits: List<MetaPreview>,
        val castTvCredits: List<MetaPreview>,
        val crewTvCredits: List<MetaPreview>,
    )

    private fun selectPreferredCredits(
        preferCrew: Boolean,
        castCredits: List<MetaPreview>,
        crewCredits: List<MetaPreview>,
    ): List<MetaPreview> = when {
        preferCrew && crewCredits.isNotEmpty() -> crewCredits
        castCredits.isNotEmpty() -> castCredits
        else -> crewCredits
    }

    private fun mapPersonMovieCreditsFromCast(
        cast: List<TmdbPersonCreditCast>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "movie",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    rawReleaseDate = credit.releaseDate,
                    popularity = credit.popularity,
                )
            }
    }

    private fun mapPersonMovieCreditsFromCrew(
        crew: List<TmdbPersonCreditCrew>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "movie",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.releaseDate?.take(4),
                    rawReleaseDate = credit.releaseDate,
                    popularity = credit.popularity,
                )
            }
    }

    private fun mapPersonTvCreditsFromCast(
        cast: List<TmdbPersonCreditCast>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "series",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    rawReleaseDate = credit.firstAirDate,
                    popularity = credit.popularity,
                )
            }
    }

    private fun mapPersonTvCreditsFromCrew(
        crew: List<TmdbPersonCreditCrew>,
    ): List<MetaPreview> {
        val seen = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && (it.posterPath != null || it.backdropPath != null) }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seen.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val poster = buildImageUrl(credit.posterPath, "w500")
                    ?: buildImageUrl(credit.backdropPath, "w780")
                    ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = "series",
                    name = title,
                    poster = poster,
                    banner = buildImageUrl(credit.backdropPath, "w780"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = credit.firstAirDate?.take(4),
                    rawReleaseDate = credit.firstAirDate,
                    popularity = credit.popularity,
                )
            }
    }

    suspend fun fetchEntityBrowse(
        entityKind: TmdbEntityKind,
        entityId: Int,
        sourceType: String,
        fallbackName: String? = null,
    ): TmdbEntityBrowseData? = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext null
        val language = normalizeTmdbLanguage(settings.language)
        val normalizedSourceType = normalizeEntitySourceType(sourceType)
        val cacheKey = "${entityKind.routeValue}:$entityId:$normalizedSourceType:$language:" +
            entityPosterCacheKey(CustomPosterSettingsRepository.snapshot(CustomPosterScreen.Details))
        entityBrowseCache[cacheKey]?.let { return@withContext it }

        val (header, rails) = coroutineScope {
            val headerDeferred = async {
                fetchEntityHeader(
                    entityKind = entityKind,
                    entityId = entityId,
                    fallbackName = fallbackName,
                    language = language,
                )
            }
            val railDeferreds = buildEntityMediaOrder(entityKind, normalizedSourceType)
                .flatMap { mediaType ->
                    TmdbEntityRailType.entries.map { railType ->
                        async {
                            val pageResult = fetchEntityRailPage(
                                entityKind = entityKind,
                                entityId = entityId,
                                mediaType = mediaType,
                                railType = railType,
                                language = language,
                                page = 1,
                            )
                            if (pageResult.items.isEmpty()) {
                                null
                            } else {
                                TmdbEntityRail(
                                    mediaType = mediaType,
                                    railType = railType,
                                    items = pageResult.items,
                                    currentPage = 1,
                                    hasMore = pageResult.hasMore,
                                )
                            }
                        }
                    }
                }
            headerDeferred.await() to railDeferreds.awaitAll().filterNotNull()
        }

        if (header == null && rails.isEmpty()) return@withContext null

        val data = TmdbEntityBrowseData(
            header = header ?: TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = fallbackName?.takeIf { it.isNotBlank() } ?: runBlocking { getString(Res.string.generic_unknown) },
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null,
            ),
            rails = rails,
        )
        entityBrowseCache[cacheKey] = data
        data
    }

    suspend fun fetchEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int,
    ): TmdbEntityRailPageResult {
        if (entityKind == TmdbEntityKind.NETWORK && mediaType == TmdbEntityMediaType.MOVIE) {
            return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        val posterSettings = CustomPosterSettingsRepository.snapshot(CustomPosterScreen.Details)
        val cacheKey = "${entityKind.routeValue}:$entityId:${mediaType.value}:${railType.value}:$language:" +
            "${entityPosterCacheKey(posterSettings)}:page:$page"
        entityRailCache[cacheKey]?.let { return it }

        val voteCountFloor = if (railType == TmdbEntityRailType.TOP_RATED) ENTITY_TOP_RATED_VOTE_FLOOR else null

        val result = try {
            val sortBy = when (railType) {
                TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
                TmdbEntityRailType.RECENT -> when (mediaType) {
                    TmdbEntityMediaType.MOVIE -> "primary_release_date.desc"
                    TmdbEntityMediaType.TV -> "first_air_date.desc"
                }
                else -> "popularity.desc"
            }

            val queryParams = buildMap {
                put("language", language)
                put("page", page.toString())
                put("sort_by", sortBy)
                when (mediaType) {
                    TmdbEntityMediaType.MOVIE -> {
                        put("with_companies", entityId.toString())
                    }
                    TmdbEntityMediaType.TV -> {
                        if (entityKind == TmdbEntityKind.COMPANY) put("with_companies", entityId.toString())
                        if (entityKind == TmdbEntityKind.NETWORK) put("with_networks", entityId.toString())
                    }
                }
                if (voteCountFloor != null) put("vote_count.gte", voteCountFloor.toString())
            }

            val endpoint = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> "discover/movie"
                TmdbEntityMediaType.TV -> "discover/tv"
            }

            val response = fetch<TmdbDiscoverResponse>(endpoint = endpoint, query = queryParams)
            val results = response?.results.orEmpty()
            val totalPages = response?.totalPages ?: page

            val mappedItems = coroutineScope {
                results
                    .asSequence()
                    .filter { it.id > 0 }
                    .take(ENTITY_RAIL_MAX_ITEMS)
                    .map { item ->
                        async {
                            entityPosterIdSemaphore.withPermit {
                                mapEntityDiscoverResult(item, mediaType, posterSettings)
                            }
                        }
                    }
                    .toList()
                    .awaitAll()
                    .filterNotNull()
            }

            TmdbEntityRailPageResult(
                items = mappedItems,
                hasMore = page < totalPages && mappedItems.isNotEmpty(),
            )
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch entity rail ${railType.value}/${mediaType.value} for $entityId" }
            TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        if (result.items.isNotEmpty()) {
            entityRailCache[cacheKey] = result
        }
        return result
    }

    suspend fun fetchNextEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        rail: TmdbEntityRail,
    ): TmdbEntityRailPageResult {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey || !rail.hasMore) {
            return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }
        return fetchEntityRailPage(
            entityKind = entityKind,
            entityId = entityId,
            mediaType = rail.mediaType,
            railType = rail.railType,
            language = normalizeTmdbLanguage(settings.language),
            page = rail.currentPage + 1,
        )
    }

    // ─── Hero badge browse ───

    /**
     * The rails behind a clickable hero discovery badge — the same shape as [fetchEntityBrowse]
     * so both share one screen. Which rails exist depends on the target: the award lists get one
     * "Winners"/"Nominees" rail per media type, discover-backed targets get the usual
     * Popular / Top Rated / Recent trio, and the single-list targets (trending, new at home, now
     * playing, upcoming) get one [TmdbEntityRailType.FEATURED] rail. Empty rails are dropped.
     */
    suspend fun fetchBadgeBrowse(
        target: HeroDiscoveryBadgeTarget,
        sourceType: String,
    ): List<TmdbEntityRail>? = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext null
        val language = normalizeTmdbLanguage(settings.language)
        val mediaOrder = badgeMediaOrder(target, normalizeEntitySourceType(sourceType))
        val rails = coroutineScope {
            mediaOrder.flatMap { mediaType ->
                badgeRailTypes(target).map { railType ->
                    async {
                        val pageResult = fetchBadgeRailPage(
                            target = target,
                            mediaType = mediaType,
                            railType = railType,
                            language = language,
                            page = 1,
                        )
                        if (pageResult.items.isEmpty()) {
                            null
                        } else {
                            TmdbEntityRail(
                                mediaType = mediaType,
                                railType = railType,
                                items = pageResult.items,
                                currentPage = 1,
                                hasMore = pageResult.hasMore,
                            )
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        rails
    }

    suspend fun fetchNextBadgeRailPage(
        target: HeroDiscoveryBadgeTarget,
        rail: TmdbEntityRail,
    ): TmdbEntityRailPageResult {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey || !rail.hasMore) {
            return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }
        return fetchBadgeRailPage(
            target = target,
            mediaType = rail.mediaType,
            railType = rail.railType,
            language = normalizeTmdbLanguage(settings.language),
            page = rail.currentPage + 1,
        )
    }

    private fun badgeMediaOrder(target: HeroDiscoveryBadgeTarget, sourceType: String): List<TmdbEntityMediaType> {
        val both = when (sourceType) {
            "movie" -> listOf(TmdbEntityMediaType.MOVIE, TmdbEntityMediaType.TV)
            else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
        }
        val movieOnly = listOf(TmdbEntityMediaType.MOVIE)
        return when (target) {
            is HeroDiscoveryBadgeTarget.AwardList -> when (target.award) {
                HeroAward.BEST_PICTURE -> movieOnly
                HeroAward.EMMY -> listOf(TmdbEntityMediaType.TV)
                HeroAward.GOLDEN_GLOBE -> both
            }
            is HeroDiscoveryBadgeTarget.Keyword -> if (target.movieOnly) movieOnly else both
            is HeroDiscoveryBadgeTarget.Language -> both
            HeroDiscoveryBadgeTarget.Trending -> both
            HeroDiscoveryBadgeTarget.NewAtHome,
            HeroDiscoveryBadgeTarget.NowPlaying,
            HeroDiscoveryBadgeTarget.Upcoming,
            HeroDiscoveryBadgeTarget.ShortFilm -> movieOnly
            is HeroDiscoveryBadgeTarget.Company, is HeroDiscoveryBadgeTarget.Director -> emptyList()
        }
    }

    private fun badgeRailTypes(target: HeroDiscoveryBadgeTarget): List<TmdbEntityRailType> = when (target) {
        is HeroDiscoveryBadgeTarget.AwardList ->
            listOf(if (target.won) TmdbEntityRailType.WINNERS else TmdbEntityRailType.NOMINEES)
        is HeroDiscoveryBadgeTarget.Keyword,
        is HeroDiscoveryBadgeTarget.Language,
        HeroDiscoveryBadgeTarget.ShortFilm ->
            listOf(TmdbEntityRailType.POPULAR, TmdbEntityRailType.TOP_RATED, TmdbEntityRailType.RECENT)
        HeroDiscoveryBadgeTarget.Trending,
        HeroDiscoveryBadgeTarget.NewAtHome,
        HeroDiscoveryBadgeTarget.NowPlaying,
        HeroDiscoveryBadgeTarget.Upcoming -> listOf(TmdbEntityRailType.FEATURED)
        is HeroDiscoveryBadgeTarget.Company, is HeroDiscoveryBadgeTarget.Director -> emptyList()
    }

    private suspend fun fetchBadgeRailPage(
        target: HeroDiscoveryBadgeTarget,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int,
    ): TmdbEntityRailPageResult {
        val posterSettings = CustomPosterSettingsRepository.snapshot(CustomPosterScreen.Details)
        val cacheKey = "${target.cacheKey()}:${mediaType.value}:${railType.value}:$language:" +
            "${entityPosterCacheKey(posterSettings)}:page:$page"
        badgeRailCache[cacheKey]?.let { return it }

        val result = try {
            when (target) {
                is HeroDiscoveryBadgeTarget.AwardList -> fetchAwardListPage(target, mediaType, language, page, posterSettings)
                HeroDiscoveryBadgeTarget.Trending -> fetchTrendingRail(mediaType, posterSettings)
                else -> fetchBadgeDiscoverPage(target, mediaType, railType, language, page, posterSettings)
            }
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch badge rail ${railType.value}/${mediaType.value} for ${target.cacheKey()}" }
            TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }

        if (result.items.isNotEmpty()) {
            badgeRailCache[cacheKey] = result
        }
        return result
    }

    /**
     * The hardcoded award sets, hydrated one `/movie/{id}` or `/tv/{id}` at a time under the same
     * permit the poster lookups use. Paged over the id list so opening "Emmy Winners" costs one
     * page of requests, not the whole set.
     */
    private suspend fun fetchAwardListPage(
        target: HeroDiscoveryBadgeTarget.AwardList,
        mediaType: TmdbEntityMediaType,
        language: String,
        page: Int,
        posterSettings: CustomPosterSettings,
    ): TmdbEntityRailPageResult {
        val ids = HeroDiscoveryAwards.browseIds(
            award = target.award,
            won = target.won,
            tv = mediaType == TmdbEntityMediaType.TV,
        )
        val from = (page - 1) * ENTITY_RAIL_MAX_ITEMS
        if (from >= ids.size) return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        val pageIds = ids.subList(from, minOf(from + ENTITY_RAIL_MAX_ITEMS, ids.size))
        val endpointPrefix = if (mediaType == TmdbEntityMediaType.TV) "tv" else "movie"
        val items = coroutineScope {
            pageIds.map { id ->
                async {
                    entityPosterIdSemaphore.withPermit {
                        fetch<TmdbDiscoverResult>(
                            endpoint = "$endpointPrefix/$id",
                            query = mapOf("language" to language),
                        )?.let { mapEntityDiscoverResult(it, mediaType, posterSettings) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return TmdbEntityRailPageResult(
            items = items,
            hasMore = from + ENTITY_RAIL_MAX_ITEMS < ids.size,
        )
    }

    private suspend fun fetchTrendingRail(
        mediaType: TmdbEntityMediaType,
        posterSettings: CustomPosterSettings,
    ): TmdbEntityRailPageResult {
        val results = TmdbService.fetchTrending(mediaType.value)
        val items = coroutineScope {
            results
                .asSequence()
                .filter { it.id > 0 }
                .take(ENTITY_RAIL_MAX_ITEMS)
                .map { result ->
                    async {
                        entityPosterIdSemaphore.withPermit {
                            mapEntitySearchResult(result, mediaType, posterSettings)
                        }
                    }
                }
                .toList()
                .awaitAll()
                .filterNotNull()
        }
        return TmdbEntityRailPageResult(items = items, hasMore = false)
    }

    private suspend fun fetchBadgeDiscoverPage(
        target: HeroDiscoveryBadgeTarget,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int,
        posterSettings: CustomPosterSettings,
    ): TmdbEntityRailPageResult {
        val requiredStatus = when (target) {
            HeroDiscoveryBadgeTarget.NowPlaying -> "Cinema"
            HeroDiscoveryBadgeTarget.Upcoming -> "Production"
            else -> null
        }
        if (requiredStatus != null) {
            return fetchReleaseStatusFilteredPage(target, mediaType, railType, language, page, posterSettings, requiredStatus)
        }
        val (endpoint, queryParams) = badgeDiscoverQuery(target, mediaType, railType, language, page)
            ?: return TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        val response = fetch<TmdbDiscoverResponse>(endpoint = endpoint, query = queryParams)
        val results = response?.results.orEmpty()
        val totalPages = response?.totalPages ?: page
        val mappedItems = coroutineScope {
            results
                .asSequence()
                .filter { it.id > 0 }
                .take(ENTITY_RAIL_MAX_ITEMS)
                .map { item ->
                    async {
                        entityPosterIdSemaphore.withPermit {
                            mapEntityDiscoverResult(item, mediaType, posterSettings)
                        }
                    }
                }
                .toList()
                .awaitAll()
                .filterNotNull()
        }
        return TmdbEntityRailPageResult(
            items = mappedItems,
            hasMore = page < totalPages && mappedItems.isNotEmpty(),
        )
    }

    /**
     * `now_playing` / `upcoming` are theatrical-window lists: a film stays on `now_playing` for
     * weeks after it has gone digital. The badge itself decides "Cinema" per title from
     * `release_dates` ([TmdbService.fetchMovieReleaseStatus]), so the rail must apply the same
     * rule or it lists films the app itself no longer badges as in cinemas. Each rail page reads
     * [RELEASE_STATUS_SOURCE_PAGES_PER_RAIL_PAGE] source pages and keeps the titles whose status
     * matches; the per-title lookups share the badge's 6-hour release_dates cache.
     */
    private suspend fun fetchReleaseStatusFilteredPage(
        target: HeroDiscoveryBadgeTarget,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int,
        posterSettings: CustomPosterSettings,
        requiredStatus: String,
    ): TmdbEntityRailPageResult {
        val firstSourcePage = (page - 1) * RELEASE_STATUS_SOURCE_PAGES_PER_RAIL_PAGE + 1
        val lastSourcePage = firstSourcePage + RELEASE_STATUS_SOURCE_PAGES_PER_RAIL_PAGE - 1
        var totalPages = lastSourcePage
        val candidates = mutableListOf<TmdbDiscoverResult>()
        for (sourcePage in firstSourcePage..lastSourcePage) {
            if (sourcePage > totalPages) break
            val (endpoint, queryParams) = badgeDiscoverQuery(target, mediaType, railType, language, sourcePage)
                ?: break
            val response = fetch<TmdbDiscoverResponse>(endpoint = endpoint, query = queryParams) ?: break
            totalPages = response.totalPages ?: sourcePage
            candidates += response.results.filter { it.id > 0 }
        }
        val items = coroutineScope {
            candidates
                .distinctBy { it.id }
                .map { item ->
                    async {
                        entityPosterIdSemaphore.withPermit {
                            val status = TmdbService.fetchMovieReleaseStatus(tmdbId = item.id, tmdbStatus = null)
                            if (status == requiredStatus) mapEntityDiscoverResult(item, mediaType, posterSettings) else null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        return TmdbEntityRailPageResult(
            items = items,
            hasMore = lastSourcePage < totalPages,
        )
    }

    /** Endpoint plus query for the discover-shaped badge targets; null when the target has no query. */
    private fun badgeDiscoverQuery(
        target: HeroDiscoveryBadgeTarget,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        page: Int,
    ): Pair<String, Map<String, String>>? {
        val isTv = mediaType == TmdbEntityMediaType.TV
        val base = mutableMapOf(
            "language" to language,
            "page" to page.toString(),
            "include_adult" to "false",
        )
        when (target) {
            HeroDiscoveryBadgeTarget.NowPlaying -> return "movie/now_playing" to base
            HeroDiscoveryBadgeTarget.Upcoming -> return "movie/upcoming" to base
            HeroDiscoveryBadgeTarget.NewAtHome -> {
                // `release_date.*` (not `primary_release_date.*`) is what scopes the window to
                // the release types asked for — otherwise the filter is on the cinema date.
                val now = WatchProgressClock.nowEpochMs()
                base["with_release_type"] = "4|5|6"
                base["release_date.gte"] = CurrentDateProvider.localIsoDateAt(now - NEW_AT_HOME_WINDOW_DAYS * 86_400_000L)
                base["release_date.lte"] = CurrentDateProvider.localIsoDateAt(now)
                base["sort_by"] = "release_date.desc"
                base["vote_count.gte"] = BADGE_DISCOVER_VOTE_FLOOR.toString()
                return "discover/movie" to base
            }
            else -> Unit
        }

        base["sort_by"] = when (railType) {
            TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
            TmdbEntityRailType.RECENT -> if (isTv) "first_air_date.desc" else "primary_release_date.desc"
            else -> "popularity.desc"
        }
        base["vote_count.gte"] = when (railType) {
            TmdbEntityRailType.TOP_RATED -> ENTITY_TOP_RATED_VOTE_FLOOR
            else -> BADGE_DISCOVER_VOTE_FLOOR
        }.toString()
        if (railType == TmdbEntityRailType.RECENT) {
            // Discover happily sorts unreleased titles first; cap at today like the badge does.
            val today = CurrentDateProvider.localIsoDateAt(WatchProgressClock.nowEpochMs())
            base[if (isTv) "first_air_date.lte" else "primary_release_date.lte"] = today
        }
        when (target) {
            is HeroDiscoveryBadgeTarget.Keyword -> base["with_keywords"] = target.keywordIds.joinToString("|")
            is HeroDiscoveryBadgeTarget.Language -> base["with_original_language"] = tmdbLanguageCode(target.code)
            HeroDiscoveryBadgeTarget.ShortFilm -> {
                base["with_runtime.gte"] = "1"
                base["with_runtime.lte"] = SHORT_FILM_MAX_MINUTES.toString()
            }
            else -> return null
        }
        return (if (isTv) "discover/tv" else "discover/movie") to base
    }

    /** `with_original_language` wants ISO 639-1; metadata sometimes carries the 639-2 form. */
    private fun tmdbLanguageCode(code: String): String {
        val normalized = code.trim().lowercase()
        return if (normalized.length == 3) ISO_639_2_TO_1[normalized] ?: normalized else normalized
    }

    private val ISO_639_2_TO_1 = mapOf(
        "eng" to "en", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "spa" to "es",
        "ita" to "it", "por" to "pt", "jpn" to "ja", "kor" to "ko", "zho" to "zh", "chi" to "zh",
        "dan" to "da", "swe" to "sv", "nor" to "no", "fin" to "fi", "dut" to "nl", "nld" to "nl",
        "pol" to "pl", "rus" to "ru", "tur" to "tr", "ara" to "ar", "hin" to "hi", "per" to "fa",
        "fas" to "fa", "rum" to "ro", "ron" to "ro", "hun" to "hu", "cze" to "cs", "ces" to "cs",
        "heb" to "he", "gre" to "el", "ell" to "el", "tha" to "th", "vie" to "vi", "ind" to "id",
        "tam" to "ta", "tel" to "te", "ben" to "bn", "ukr" to "uk",
    )

    private suspend fun fetchEntityHeader(
        entityKind: TmdbEntityKind,
        entityId: Int,
        fallbackName: String?,
        language: String,
    ): TmdbEntityHeader? {
        val cacheKey = "${entityKind.routeValue}:$entityId:$language:header"
        entityHeaderCache[cacheKey]?.let { return it }

        val header = try {
            when (entityKind) {
                TmdbEntityKind.COMPANY -> {
                    val body = fetch<TmdbCompanyDetailsResponse>(endpoint = "company/$entityId")
                    body?.let {
                        TmdbEntityHeader(
                            id = it.id,
                            kind = entityKind,
                            name = it.name?.takeIf { n -> n.isNotBlank() }
                                ?: fallbackName?.takeIf { n -> n.isNotBlank() }
                                ?: runBlocking { getString(Res.string.generic_unknown) },
                            logo = buildImageUrl(it.logoPath, "w500"),
                            originCountry = it.originCountry?.takeIf { c -> c.isNotBlank() },
                            secondaryLabel = it.headquarters?.takeIf { h -> h.isNotBlank() },
                            description = it.description?.takeIf { d -> d.isNotBlank() },
                        )
                    }
                }
                TmdbEntityKind.NETWORK -> {
                    val body = fetch<TmdbNetworkDetailsResponse>(endpoint = "network/$entityId")
                    body?.let {
                        TmdbEntityHeader(
                            id = it.id,
                            kind = entityKind,
                            name = it.name?.takeIf { n -> n.isNotBlank() }
                                ?: fallbackName?.takeIf { n -> n.isNotBlank() }
                                ?: runBlocking { getString(Res.string.generic_unknown) },
                            logo = buildImageUrl(it.logoPath, "w500"),
                            originCountry = it.originCountry?.takeIf { c -> c.isNotBlank() },
                            secondaryLabel = it.headquarters?.takeIf { h -> h.isNotBlank() },
                            description = null,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            log.w(e) { "Failed to fetch ${entityKind.routeValue} header for $entityId" }
            null
        } ?: fallbackName?.takeIf { it.isNotBlank() }?.let {
            TmdbEntityHeader(
                id = entityId,
                kind = entityKind,
                name = it,
                logo = null,
                originCountry = null,
                secondaryLabel = null,
                description = null,
            )
        }

        if (header != null) {
            entityHeaderCache[cacheKey] = header
        }
        return header
    }

    private suspend fun mapEntityDiscoverResult(
        result: TmdbDiscoverResult,
        mediaType: TmdbEntityMediaType,
        posterSettings: CustomPosterSettings,
    ): MetaPreview? = buildEntityPreview(
        tmdbId = result.id,
        title = result.title?.takeIf { it.isNotBlank() }
            ?: result.name?.takeIf { it.isNotBlank() }
            ?: result.originalTitle?.takeIf { it.isNotBlank() }
            ?: result.originalName?.takeIf { it.isNotBlank() },
        posterPath = result.posterPath,
        backdropPath = result.backdropPath,
        overview = result.overview,
        date = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> result.releaseDate
            TmdbEntityMediaType.TV -> result.firstAirDate
        },
        mediaType = mediaType,
        posterSettings = posterSettings,
    )

    private suspend fun mapEntitySearchResult(
        result: TmdbSearchResult,
        mediaType: TmdbEntityMediaType,
        posterSettings: CustomPosterSettings,
    ): MetaPreview? = buildEntityPreview(
        tmdbId = result.id,
        title = result.title?.takeIf { it.isNotBlank() } ?: result.name?.takeIf { it.isNotBlank() },
        posterPath = result.posterPath,
        backdropPath = result.backdropPath,
        overview = result.overview,
        date = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> result.releaseDate
            TmdbEntityMediaType.TV -> result.firstAirDate
        },
        mediaType = mediaType,
        posterSettings = posterSettings,
    )

    /**
     * One rail card from the fields every TMDB list-shaped payload carries (discover, trending,
     * `/movie/{id}`), with the library poster template applied the same way for all of them.
     */
    private suspend fun buildEntityPreview(
        tmdbId: Int,
        title: String?,
        posterPath: String?,
        backdropPath: String?,
        overview: String?,
        date: String?,
        mediaType: TmdbEntityMediaType,
        posterSettings: CustomPosterSettings,
    ): MetaPreview? {
        if (tmdbId <= 0 || title.isNullOrBlank()) return null
        val poster = buildImageUrl(posterPath, "w500")
            ?: buildImageUrl(backdropPath, "w780")
            ?: return null
        val type = if (mediaType == TmdbEntityMediaType.TV) "series" else "movie"
        val base = MetaPreview(
            id = "tmdb:$tmdbId",
            type = type,
            name = title,
            poster = poster,
            banner = buildImageUrl(backdropPath, "w780"),
            logo = null,
            description = overview?.takeIf { it.isNotBlank() },
            releaseInfo = date?.take(4),
        )
        if (!posterSettings.isActive) return base
        val keys = CustomPosterKeys.snapshot()
        // Only pay for the /find call when a template actually names {imdb_id}; TmdbService caches
        // and single-flights it, so the cost is one request per title ever.
        val imdbId = if (posterSettings.customPosterTemplateNeedsImdbId()) {
            resolveCustomPosterIds(
                settings = posterSettings,
                imdbId = null,
                tmdbId = tmdbId,
                type = type,
            ).imdbId
        } else {
            null
        }
        return base.withCustomPosters(
            settings = posterSettings,
            imdbId = imdbId,
            tmdbId = tmdbId,
            keys = keys,
        )
    }

    private fun entityPosterCacheKey(settings: CustomPosterSettings): String = settings.cacheToken()

    private fun buildEntityMediaOrder(
        entityKind: TmdbEntityKind,
        sourceType: String,
    ): List<TmdbEntityMediaType> {
        if (entityKind == TmdbEntityKind.NETWORK) {
            return listOf(TmdbEntityMediaType.TV)
        }
        return when (sourceType) {
            "movie" -> listOf(TmdbEntityMediaType.MOVIE, TmdbEntityMediaType.TV)
            else -> listOf(TmdbEntityMediaType.TV, TmdbEntityMediaType.MOVIE)
        }
    }

    private fun normalizeEntitySourceType(sourceType: String): String {
        return when (sourceType.trim().lowercase()) {
            "movie" -> "movie"
            "tv", "series", "show" -> "tv"
            else -> "tv"
        }
    }

    suspend fun enrichMeta(
        meta: MetaDetails,
        fallbackItemId: String,
        settings: TmdbSettings,
        trailerScope: TrailerScope = TrailerScope.AllSeasons,
        /**
         * Whether to decorate the episode list from TMDB — plan §25.
         *
         * Costs **one request per season of the show**, and the traced consumers of the lightweight
         * path do not read any of what it adds: the Home heroes take background/logo/ageRating/
         * runtime/genres, `enrichForMetaScreen` takes only a fallback logo, and Random Play reads
         * `videos.size`, which comes from the addon payload — this only decorates existing videos
         * with titles, thumbnails and season posters, so the count is unaffected.
         */
        includeEpisodes: Boolean = true,
        depth: EnrichmentDepth = EnrichmentDepth.Full,
        /**
         * Only these seasons' episodes are decorated (null: every season). The details page asks
         * for the season it opens on first and the rest afterwards: each season is its own request,
         * and a 20-season show held the whole page on ~20 of them (2026-10-19, Law & Order).
         * Undecorated episodes keep the addon's values.
         */
        episodeSeasons: Set<Int>? = null,
    ): MetaDetails {
        if (!settings.enabled || !settings.hasApiKey) return meta
        if (!meta.imdbTmdbIdentityTrusted) return meta

        val tmdbType = normalizeMetaType(meta.type)
        val tmdbId = TmdbService.ensureTmdbId(meta.id, tmdbType)
            ?: TmdbService.ensureTmdbId(fallbackItemId, tmdbType)
            ?: return meta

        val keepAddon = settings.keepsAddonRecord(meta)
        val needsEpisodes = includeEpisodes &&
            (settings.useEpisodes || settings.useSeasonPosters) &&
            tmdbType == "tv" &&
            // A TVDB/Kitsu record's episodes are the record; TMDB is asked only when some episode
            // has no title or still to fill (one request per season otherwise spent for nothing).
            (!keepAddon || meta.videos.any { it.title.isBlank() || it.thumbnail.isNullOrBlank() })
        val (enrichment, episodeMap) = coroutineScope {
            val enrichmentDeferred = async {
                fetchEnrichment(
                    tmdbId = tmdbId,
                    mediaType = tmdbType,
                    language = settings.language,
                    settings = settings,
                    trailerScope = trailerScope,
                    depth = depth,
                )
            }
            val episodeDeferred = if (needsEpisodes) {
                async {
                    val seasons = meta.videos.mapNotNull { it.season }.distinct()
                        .filter { season -> episodeSeasons == null || season in episodeSeasons }
                    fetchEpisodeEnrichment(
                        tmdbId = tmdbId,
                        seasonNumbers = seasons,
                        language = settings.language,
                    )
                }
            } else {
                null
            }
            enrichmentDeferred.await() to episodeDeferred?.await()
        }

        // Some addons hand back bare-numeric catalog ids with no tt:/tmdb:/tvdb: prefix
        // (TmdbService.ensureTmdbId trusts these directly — see its "all digits" branch). If
        // that number collides with an unrelated TMDB entry, blindly applying its logo/backdrop
        // and merging its cast produces a Frankenstein result: the wrong title's art and credits
        // stitched onto the correct addon-sourced synopsis/genres. Bail out and keep the addon's
        // own meta untouched when both title and release year clearly disagree with what TMDB
        // actually returned for this id.
        val mismatch = enrichment != null &&
            looksLikeDifferentTitle(meta.name, meta.releaseInfo, enrichment.localizedTitle, enrichment.releaseInfo)
        if (mismatch) {
            log.w {
                "TMDB tmdbId=$tmdbId looks like a different title than addon meta " +
                    "'${meta.name}' (${meta.releaseInfo}) — got '${enrichment?.localizedTitle}' " +
                    "(${enrichment?.releaseInfo}); skipping enrichment to avoid mismatched metadata."
            }
            return meta.quarantineMismatchedImdbTmdbIdentity()
        }

        return applyEnrichment(
            meta = meta,
            enrichment = enrichment,
            episodeMap = episodeMap.orEmpty(),
            settings = settings,
        )
    }

    /**
     * True when [titleB]/[releaseInfoB] (typically a TMDB-sourced candidate resolved from a
     * bare-numeric addon id — see [TmdbService.ensureTmdbId]'s "all digits" branch, which trusts
     * such ids without verification) looks like a different title than [titleA]/[releaseInfoA]
     * (typically the addon's own, already-correct meta). Used to avoid stitching a wrong id
     * collision's logo/backdrop/cast onto otherwise-correct addon metadata. Intentionally lenient
     * on title-only mismatches (legitimately-matched retitled/localized content is common) — only
     * treats it as a real mismatch when title AND release year both clearly disagree.
     */
    internal fun looksLikeDifferentTitle(
        titleA: String?,
        releaseInfoA: String?,
        titleB: String?,
        releaseInfoB: String?,
    ): Boolean {
        val normalizedA = titleA?.takeIf(String::isNotBlank)?.normalizedForTitleCompare() ?: return false
        val normalizedB = titleB?.takeIf(String::isNotBlank)?.normalizedForTitleCompare() ?: return false
        val titlesMatch = normalizedA.isNotBlank() && normalizedB.isNotBlank() &&
            (normalizedA == normalizedB || normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA))
        if (titlesMatch) return false

        // Titles disagree — only treat this as a real mismatch if the release years also
        // clearly disagree (allow a 1-year slack for premiere-year vs air-year conventions).
        val yearA = extractComparisonYear(releaseInfoA) ?: return false
        val yearB = extractComparisonYear(releaseInfoB) ?: return false
        return kotlin.math.abs(yearA - yearB) > 1
    }

    private fun String.normalizedForTitleCompare(): String =
        lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun extractComparisonYear(value: String?): Int? =
        value?.let { titleCompareYearRegex.find(it)?.value?.toIntOrNull() }

    suspend fun fetchStandaloneMeta(
        type: String,
        id: String,
        settings: TmdbSettings,
        depth: EnrichmentDepth = EnrichmentDepth.Full,
    ): MetaDetails? {
        if (!settings.hasApiKey) return null

        val tmdbId = id
            .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?: return null
        val tmdbType = normalizeMetaType(type)
        val enrichment = fetchEnrichment(
            tmdbId = tmdbId.toString(),
            mediaType = tmdbType,
            language = settings.language,
            settings = settings,
            // A standalone meta stands in for a full details view, so it takes the full set -
            // unless a card-depth caller (a background lookup) asked for less.
            trailerScope = TrailerScope.AllSeasons,
            depth = depth,
        ) ?: return null

        return buildStandaloneMeta(
            type = type,
            id = id,
            tmdbId = tmdbId,
            enrichment = enrichment,
        )
    }

    internal fun buildStandaloneMeta(
        type: String,
        id: String,
        tmdbId: Int,
        enrichment: TmdbEnrichment,
    ): MetaDetails =
        MetaDetails(
            id = id,
            type = type,
            name = enrichment.localizedTitle ?: "TMDB $tmdbId",
            tmdbId = tmdbId,
            poster = enrichment.poster,
            background = enrichment.backdrop,
            logo = enrichment.logo,
            description = enrichment.description,
            releaseInfo = enrichment.releaseInfo,
            lastAirDate = enrichment.lastAirDate,
            status = enrichment.status,
            imdbRating = enrichment.rating?.formatRating(),
            ageRating = enrichment.ageRating,
            runtime = enrichment.runtimeMinutes?.formatRuntime(),
            genres = enrichment.genres,
            director = enrichment.director,
            producer = enrichment.producer,
            writer = enrichment.writer,
            cast = enrichment.people,
            productionCompanies = enrichment.productionCompanies,
            networks = enrichment.networks,
            country = enrichment.countries.takeIf { it.isNotEmpty() }?.joinToString(", "),
            language = enrichment.language,
            moreLikeThis = enrichment.moreLikeThis,
            moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { enrichment.moreLikeThis.isNotEmpty() },
            collectionName = enrichment.collectionName,
            collectionItems = enrichment.collectionItems,
            trailers = enrichment.trailers,
        )

    internal fun applyEnrichment(
        meta: MetaDetails,
        enrichment: TmdbEnrichment?,
        episodeMap: Map<Pair<Int, Int>, TmdbEpisodeEnrichment>,
        settings: TmdbSettings,
    ): MetaDetails {
        if (enrichment == null && episodeMap.isEmpty()) return meta
        // TheTVDB / Kitsu record under the source-aware setting: TMDB fills blanks, replaces nothing.
        val keepAddon = settings.keepsAddonRecord(meta)

        var updated = meta

        if (enrichment != null && settings.useArtwork) {
            updated = updated.copy(
                background = enrichment.backdrop ?: updated.background,
                poster = enrichment.poster ?: updated.poster,
                logo = enrichment.logo ?: updated.logo,
            )
        }

        if (enrichment != null && settings.useBasicInfo && keepAddon) {
            updated = updated.copy(
                tmdbId = enrichment.tmdbId ?: updated.tmdbId,
                name = updated.name.takeIf(String::isNotBlank) ?: enrichment.localizedTitle ?: updated.name,
                description = updated.description?.takeIf(String::isNotBlank) ?: enrichment.description,
                imdbRating = updated.imdbRating?.takeIf { it.isNotBlank() }
                    ?: enrichment.rating?.formatRating(),
                genres = updated.genres.ifEmpty { enrichment.genres },
                tmdbKeywords = enrichment.keywords.ifEmpty { updated.tmdbKeywords },
            )
        } else if (enrichment != null && settings.useBasicInfo) {
            updated = updated.copy(
                tmdbId = enrichment.tmdbId ?: updated.tmdbId,
                name = enrichment.localizedTitle ?: updated.name,
                description = enrichment.description ?: updated.description,
                imdbRating = updated.imdbRating?.takeIf { it.isNotBlank() }
                    ?: enrichment.rating?.formatRating(),
                genres = enrichment.genres.ifEmpty { updated.genres },
                tmdbKeywords = enrichment.keywords.ifEmpty { updated.tmdbKeywords },
            )
        }

        if (enrichment != null && settings.useDetails && keepAddon) {
            updated = updated.copy(
                releaseInfo = updated.releaseInfo?.takeIf(String::isNotBlank) ?: enrichment.releaseInfo,
                lastAirDate = updated.lastAirDate?.takeIf(String::isNotBlank) ?: enrichment.lastAirDate,
                status = updated.status?.takeIf(String::isNotBlank) ?: enrichment.status,
                ageRating = updated.ageRating?.takeIf(String::isNotBlank) ?: enrichment.ageRating,
                runtime = updated.runtime?.takeIf(String::isNotBlank) ?: enrichment.runtimeMinutes?.formatRuntime(),
                country = updated.country?.takeIf(String::isNotBlank)
                    ?: enrichment.countries.takeIf { it.isNotEmpty() }?.joinToString(", "),
                language = updated.language?.takeIf(String::isNotBlank) ?: enrichment.language,
            )
        } else if (enrichment != null && settings.useDetails) {
            updated = updated.copy(
                releaseInfo = enrichment.releaseInfo ?: updated.releaseInfo,
                lastAirDate = enrichment.lastAirDate ?: updated.lastAirDate,
                status = enrichment.status ?: updated.status,
                ageRating = enrichment.ageRating ?: updated.ageRating,
                runtime = enrichment.runtimeMinutes?.formatRuntime() ?: updated.runtime,
                country = enrichment.countries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: updated.country,
                language = enrichment.language ?: updated.language,
            )
        }

        if (enrichment != null && settings.useCredits) {
            updated = updated.copy(
                director = enrichment.director.ifEmpty { updated.director },
                producer = enrichment.producer.ifEmpty { updated.producer },
                writer = enrichment.writer.ifEmpty { updated.writer },
                cast = mergeCastPreservingBaseOrder(
                    base = updated.cast,
                    enrichment = enrichment.people,
                    // A TVDB/Kitsu cast list is the cast: TMDB adds photos and person links to
                    // the people on it, not people of its own. Unless the addon sent only the top
                    // billing: AIOMetadata's TVDB records carry five names (Orange Is the New Black
                    // showed Piper, Alex, Red, Tiffany and Nicky and no one else), so a short list is
                    // kept first and in order, and TMDB's remaining cast follows it.
                    appendExtras = !keepAddon || updated.cast.size <= SHORT_ADDON_CAST_SIZE,
                ),
            )
        }

        if (enrichment != null && settings.useProductions && enrichment.productionCompanies.isNotEmpty() &&
            !(keepAddon && updated.productionCompanies.isNotEmpty())
        ) {
            updated = updated.copy(productionCompanies = enrichment.productionCompanies)
        }

        if (enrichment != null && settings.useNetworks && enrichment.networks.isNotEmpty() &&
            !(keepAddon && updated.networks.isNotEmpty())
        ) {
            updated = updated.copy(networks = enrichment.networks)
        }

        if (episodeMap.isNotEmpty()) {
            updated = updated.copy(
                videos = meta.videos.map { video ->
                    val key = video.season?.let { season ->
                        video.episode?.let { episode -> season to episode }
                    }
                    val enrichmentForEpisode = key?.let(episodeMap::get)
                    if (enrichmentForEpisode == null) {
                        video
                    } else if (keepAddon) {
                        // Only what the record left blank. TMDB orders some shows' seasons
                        // differently (anime, long-runners), so its episode at the same number
                        // may not be this one; blanks are the only place that risk is worth it.
                        video.copy(
                            title = video.title.ifBlank {
                                enrichmentForEpisode.title.takeIf { settings.useEpisodes } ?: video.title
                            },
                            overview = video.overview?.takeIf(String::isNotBlank)
                                ?: enrichmentForEpisode.overview.takeIf { settings.useEpisodes },
                            thumbnail = video.thumbnail?.takeIf(String::isNotBlank)
                                ?: enrichmentForEpisode.thumbnail.takeIf { settings.useEpisodes },
                            seasonPoster = video.seasonPoster?.takeIf(String::isNotBlank)
                                ?: enrichmentForEpisode.seasonPoster.takeIf { settings.useSeasonPosters },
                            runtime = video.runtime ?: enrichmentForEpisode.runtimeMinutes.takeIf { settings.useEpisodes },
                        )
                    } else {
                        video.copy(
                            title = if (settings.useEpisodes) {
                                enrichmentForEpisode.title ?: video.title
                            } else {
                                video.title
                            },
                            overview = if (settings.useEpisodes) {
                                enrichmentForEpisode.overview ?: video.overview
                            } else {
                                video.overview
                            },
                            released = if (settings.useEpisodes) {
                                preferPreciseReleaseDate(
                                    addonReleased = video.released,
                                    tmdbAirDate = enrichmentForEpisode.airDate,
                                )
                            } else {
                                video.released
                            },
                            thumbnail = if (settings.useEpisodes) {
                                enrichmentForEpisode.thumbnail ?: video.thumbnail
                            } else {
                                video.thumbnail
                            },
                            seasonPoster = if (settings.useSeasonPosters) {
                                enrichmentForEpisode.seasonPoster ?: video.seasonPoster
                            } else {
                                video.seasonPoster
                            },
                            runtime = if (settings.useEpisodes) {
                                enrichmentForEpisode.runtimeMinutes ?: video.runtime
                            } else {
                                video.runtime
                            },
                        )
                    }
                },
            )
        }

        if (enrichment != null && settings.useMoreLikeThis) {
            updated = updated.copy(
                moreLikeThis = enrichment.moreLikeThis,
                moreLikeThisSource = MoreLikeThisSource.TMDB.takeIf { enrichment.moreLikeThis.isNotEmpty() },
            )
        }

        if (enrichment != null && settings.useCollections) {
            updated = updated.copy(
                collectionName = enrichment.collectionName,
                collectionItems = enrichment.collectionItems,
            )
        }

        if (enrichment != null && settings.useTrailers && enrichment.trailers.isNotEmpty()) {
            updated = updated.copy(trailers = enrichment.trailers)
        }

        return updated
    }

    private fun mergeCastPreservingBaseOrder(
        base: List<MetaPerson>,
        enrichment: List<MetaPerson>,
        appendExtras: Boolean = true,
    ): List<MetaPerson> {
        if (enrichment.isEmpty()) return base
        if (base.isEmpty()) return enrichment

        val enrichedByName = enrichment.associateBy { it.name.normalizedPersonName() }
        val seen = mutableSetOf<String>()
        val merged = base.mapNotNull { basePerson ->
            val key = basePerson.name.normalizedPersonName()
            if (key.isBlank()) return@mapNotNull null
            seen += key
            val enriched = enrichedByName[key]
            if (enriched == null) {
                basePerson
            } else {
                basePerson.copy(
                    name = basePerson.name.trim().takeIf(String::isNotBlank) ?: enriched.name,
                    role = basePerson.role ?: enriched.role,
                    photo = enriched.photo ?: basePerson.photo,
                    tmdbId = enriched.tmdbId ?: basePerson.tmdbId,
                    episodeCount = enriched.episodeCount ?: basePerson.episodeCount,
                )
            }
        }
        if (!appendExtras) return merged
        return merged + enrichment.filter { it.name.normalizedPersonName() !in seen }
    }

    private suspend fun fetchEnrichment(
        tmdbId: String,
        mediaType: String,
        language: String,
        settings: TmdbSettings,
        trailerScope: TrailerScope,
        depth: EnrichmentDepth = EnrichmentDepth.Full,
    ): TmdbEnrichment? = withContext(Dispatchers.Default) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        // The scope is in the key because the payload differs: a Home entry holds one season's
        // trailers, and serving that to a details page would silently hide the rest. A card has no
        // trailers at all, so it is its own key - but any full record answers a card request.
        val keyPrefix = "$tmdbId:$mediaType:$normalizedLanguage:"
        val cacheKey = keyPrefix + if (depth == EnrichmentDepth.Card) "card" else trailerScope.cacheTag
        enrichmentCache[cacheKey]?.let { return@withContext it }
        if (depth == EnrichmentDepth.Card) {
            (enrichmentCache[keyPrefix + "all"] ?: enrichmentCache[keyPrefix + "s1"])
                ?.let { return@withContext it }
        }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext null
        val includeImageLanguage = buildString {
            append(normalizedLanguage.substringBefore("-"))
            append(",")
            append(normalizedLanguage)
            append(",en,null")
        }
        val full = depth == EnrichmentDepth.Full
        val wantMoreLikeThis = full && settings.useMoreLikeThis && (mediaType == "movie" || mediaType == "tv")
        val wantTrailers = full && settings.useTrailers && (mediaType == "movie" || mediaType == "tv")
        val moreLikeThisKey = moreLikeThisCacheKey(numericId, mediaType, normalizedLanguage)
        val trailerKey = trailerCacheKey(numericId, mediaType, normalizedLanguage, trailerScope)

        // One request instead of six or seven. Everything that used to be its own call - credits,
        // images, the age rating, recommendations, videos - rides on the details call; TMDB applies
        // `language` to the appended videos/recommendations and `include_image_language` to the
        // appended images exactly as it did to the standalone endpoints. Parts already cached
        // (recommendations, trailers) are not asked for again.
        val appended = buildList {
            add("keywords")
            add("images")
            add(if (mediaType == "tv") "content_ratings" else "release_dates")
            if (full) add(if (mediaType == "tv") "aggregate_credits" else "credits")
            if (wantMoreLikeThis && moreLikeThisCache[moreLikeThisKey] == null) add("recommendations")
            if (wantTrailers && cachedTrailers(trailerKey, numericId, mediaType, normalizedLanguage, trailerScope) == null) {
                add("videos")
            }
        }
        PerfTelemetry.count("TmdbMetadataService.fetchEnrichment.$depth")
        val details = fetch<TmdbDetailsResponse>(
            endpoint = "$mediaType/$numericId",
            query = mapOf(
                "language" to normalizedLanguage,
                "append_to_response" to appended.joinToString(","),
                "include_image_language" to includeImageLanguage,
            ),
        ) ?: return@withContext null

        val credits = when {
            !full -> null
            // A series' /credits is only its latest season's cast, so anyone who left earlier
            // was missing. aggregate_credits covers every season.
            mediaType == "tv" -> details.aggregateCredits?.toStandard()
            else -> details.credits
        }
        val images = details.images
        val ageRating = when (mediaType) {
            "tv" -> details.contentRatings?.results.orEmpty().selectTvAgeRating(normalizedLanguage)
            else -> details.releaseDates?.results.orEmpty().selectMovieAgeRating(normalizedLanguage)
        }
        val (moreLikeThis, trailers) = coroutineScope {
            val moreLikeThis = async {
                if (wantMoreLikeThis) {
                    fetchMoreLikeThis(
                        tmdbId = numericId,
                        mediaType = mediaType,
                        language = normalizedLanguage,
                        prefetched = details.recommendations,
                    )
                } else {
                    emptyList()
                }
            }
            val trailers = async {
                if (wantTrailers) {
                    fetchTrailers(
                        tmdbId = numericId,
                        mediaType = mediaType,
                        language = normalizedLanguage,
                        scope = trailerScope,
                        prefetchedVideos = details.videos?.results,
                        knownSeasonCount = details.numberOfSeasons,
                    )
                } else {
                    emptyList()
                }
            }
            moreLikeThis.await() to trailers.await()
        }

        val genres = details.genres.mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        val description = details.overview?.trim()?.takeIf(String::isNotBlank)
        val releaseInfo = details.releaseDate ?: details.firstAirDate
        val localizedTitle = listOf(details.title, details.name).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotBlank) }
        val people = buildPeople(details = details, credits = credits, mediaType = mediaType)
        val directors = buildDirectors(details = details, credits = credits, mediaType = mediaType)
        val producers = buildProducers(credits = credits)
        val writers = buildWriters(credits = credits)
        val lastAirDate = details.lastAirDate?.trim()?.takeIf(String::isNotBlank)
            ?.takeIf { mediaType == "tv" }
        val enrichment = TmdbEnrichment(
            tmdbId = numericId,
            localizedTitle = localizedTitle,
            description = description,
            genres = genres,
            backdrop = buildImageUrl(details.backdropPath, "original"),
            logo = buildImageUrl(images?.logos.orEmpty().selectBestLocalizedImagePath(normalizedLanguage), "w500"),
            poster = buildImageUrl(details.posterPath, "w500"),
            people = people,
            director = directors,
            producer = producers,
            writer = writers,
            releaseInfo = releaseInfo,
            lastAirDate = lastAirDate,
            rating = details.voteAverage,
            runtimeMinutes = details.runtime ?: details.episodeRunTime.firstOrNull(),
            ageRating = ageRating,
            status = details.status?.trim()?.takeIf(String::isNotBlank),
            countries = details.productionCountries
                .mapNotNull { it.iso31661?.trim()?.takeIf(String::isNotBlank) }
                .ifEmpty { details.originCountry.filter(String::isNotBlank) },
            language = details.originalLanguage?.trim()?.takeIf(String::isNotBlank),
            productionCompanies = details.productionCompanies.mapNotNull { it.toMetaCompany() },
            networks = details.networks.mapNotNull { it.toMetaCompany() },
            collectionName = details.belongsToCollection?.name?.trim()?.takeIf(String::isNotBlank),
            collectionItems = if (full && settings.useCollections && details.belongsToCollection?.id != null) {
                fetchCollection(
                    collectionId = details.belongsToCollection.id,
                    language = normalizedLanguage,
                ).second
            } else {
                emptyList()
            },
            moreLikeThis = moreLikeThis,
            trailers = trailers,
            keywords = details.keywords?.names().orEmpty(),
        )

        if (!enrichment.hasContent()) return@withContext null
        enrichmentCache[cacheKey] = enrichment
        enrichment
    }

    private suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String,
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.Default) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val normalizedSeasons = seasonNumbers.distinct().sorted()
        if (normalizedSeasons.isEmpty()) return@withContext emptyMap()

        val pairs = coroutineScope {
            normalizedSeasons.map { season ->
                async {
                    val seasonKey = "$numericId:$season:$normalizedLanguage"
                    episodeCacheMutex.withLock { episodeCache[seasonKey] }?.let { return@async it }

                    // Single-flighted per season: without this, two overlapping set requests both
                    // miss and both fetch, which is half of what the old set-keyed cache cost.
                    episodeFetchLock(seasonKey).withLock {
                        episodeCacheMutex.withLock { episodeCache[seasonKey] }
                            ?.let { return@async it }

                        val details = fetch<TmdbSeasonDetailsResponse>(
                            endpoint = "tv/$numericId/season/$season",
                            query = mapOf("language" to normalizedLanguage),
                        ) ?: return@async emptyMap()

                        val parsed = details.episodes
                        .mapNotNull { episode ->
                            val episodeNumber = episode.episodeNumber ?: return@mapNotNull null
                            (season to episodeNumber) to TmdbEpisodeEnrichment(
                                title = episode.name?.trim()?.takeIf(String::isNotBlank),
                                overview = episode.overview?.trim()?.takeIf(String::isNotBlank),
                                thumbnail = buildImageUrl(episode.stillPath, "w500"),
                                seasonPoster = buildImageUrl(details.posterPath, "w500"),
                                airDate = episode.airDate?.trim()?.takeIf(String::isNotBlank),
                                runtimeMinutes = episode.runtime,
                            )
                        }
                            .toMap()

                        // Stored even when empty: a season that genuinely has no episode data is a
                        // real answer, and re-asking for it on every visit is what the old key did.
                        episodeCacheMutex.withLock { episodeCache[seasonKey] = parsed }
                        parsed
                    }
                }
            }.awaitAll()
        }

        pairs.fold(emptyMap<Pair<Int, Int>, TmdbEpisodeEnrichment>()) { acc, value -> acc + value }
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val apiKey = TmdbSettingsRepository.snapshot().apiKey.trim().takeIf(String::isNotBlank) ?: return null
        val url = buildTmdbUrl(endpoint = endpoint, apiKey = apiKey, query = query)
        return runCatching {
            json.decodeFromString<T>(TmdbHttp.getText(url))
        }.onFailure { error ->
            log.w { "TMDB request failed for $endpoint: ${error.message}" }
        }.getOrNull()
    }

    private fun moreLikeThisCacheKey(tmdbId: Int, mediaType: String, language: String): String =
        "$tmdbId:$mediaType:$language:recommendations"

    private suspend fun fetchMoreLikeThis(
        tmdbId: Int,
        mediaType: String,
        language: String,
        /** Recommendations that arrived appended to the details call; fetched here only if absent. */
        prefetched: TmdbRecommendationResponse? = null,
    ): List<MetaPreview> {
        val cacheKey = moreLikeThisCacheKey(tmdbId, mediaType, language)
        moreLikeThisCache[cacheKey]?.let { return it }

        val response = prefetched ?: fetch<TmdbRecommendationResponse>(
            endpoint = "$mediaType/$tmdbId/recommendations",
            query = mapOf("language" to language),
        ) ?: return emptyList()

        val items = response.results
            .filter { it.id > 0 }
            .mapNotNull { recommendation ->
                val inferredType = when (recommendation.mediaType?.lowercase()) {
                    "tv" -> "series"
                    "movie" -> "movie"
                    else -> if (mediaType == "tv") "series" else "movie"
                }
                val title = recommendation.title
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: recommendation.name?.trim()?.takeIf(String::isNotBlank)
                    ?: recommendation.originalTitle?.trim()?.takeIf(String::isNotBlank)
                    ?: recommendation.originalName?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null

                MetaPreview(
                    id = "tmdb:${recommendation.id}",
                    type = inferredType,
                    name = title,
                    poster = buildImageUrl(recommendation.posterPath, "w500")
                        ?: buildImageUrl(recommendation.backdropPath, "w780"),
                    banner = buildImageUrl(recommendation.backdropPath, "w1280"),
                    posterShape = PosterShape.Poster,
                    description = recommendation.overview?.trim()?.takeIf(String::isNotBlank),
                    releaseInfo = (recommendation.releaseDate ?: recommendation.firstAirDate)?.take(4),
                    rawReleaseDate = recommendation.releaseDate ?: recommendation.firstAirDate,
                    imdbRating = recommendation.voteAverage?.formatRating(),
                )
            }
            .take(12)

        moreLikeThisCache[cacheKey] = items
        return items
    }

    private val sameCastCache = mutableMapOf<String, List<MetaPreview>>()
    private val sameCastMutex = Mutex()

    /**
     * "Starring the same cast": the main cast's other films and shows, ported from the Nuvio
     * Tracker site (`sameCast` in recommend.ts).
     *
     * A show's main cast is whoever is in 40% of its episodes or 50 of them, whichever is fewer
     * (billed in the top 30, so a long-running extra isn't one); a film's is its top six billing.
     * At most ten of them. Each of their other roles weighs by its size — a film's billing, a
     * show's episodes. Titles two or more of them really starred in lead; then the rest go round
     * the cast in billing order, each actor's biggest, best-known roles first, so everyone is
     * represented. Talk/news/reality/documentary, "Self" appearances, guest spots, unreleased or
     * barely-voted titles, and reunions (two or more of them reprising the same characters) are
     * left out. Each card's description names who from the cast is in it.
     *
     * One credits call plus one combined_credits call per person (≤11), cached for the session.
     */
    suspend fun fetchSameCast(tmdbId: Int, mediaType: String): List<MetaPreview> = withContext(Dispatchers.Default) {
        if (mediaType != "movie" && mediaType != "tv") return@withContext emptyList()
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext emptyList()
        val language = normalizeTmdbLanguage(settings.language)
        val cacheKey = "$tmdbId:$mediaType:$language"
        sameCastMutex.withLock { sameCastCache[cacheKey] }?.let { return@withContext it }

        val leads: List<SameCastLead> = if (mediaType == "tv") {
            val details = fetch<TmdbSameCastShowDetails>(
                endpoint = "tv/$tmdbId",
                query = mapOf("language" to language, "append_to_response" to "aggregate_credits"),
            ) ?: return@withContext emptyList()
            val needed = ceil((details.numberOfEpisodes ?: 0) * 0.4).toInt().coerceIn(1, 50)
            details.aggregateCredits?.cast.orEmpty()
                .filter { (it.totalEpisodeCount ?: 0) >= needed && (it.order ?: 999) < 30 && it.id != null }
                .map { SameCastLead(it.id!!, it.name.orEmpty(), it.roles.mapNotNull { role -> role.character }) }
        } else {
            fetch<TmdbSameCastMovieCredits>(
                endpoint = "movie/$tmdbId/credits",
                query = mapOf("language" to language),
            )?.cast.orEmpty()
                .filter { (it.order ?: 99) < SAME_CAST_MOVIE_BILLING && it.id != null }
                .map { SameCastLead(it.id!!, it.name.orEmpty(), listOfNotNull(it.character)) }
        }.take(SAME_CAST_PEOPLE)
        if (leads.isEmpty()) return@withContext emptyList()

        val today = CurrentDateProvider.todayIsoDate()
        val credits = coroutineScope {
            leads.map { lead ->
                async {
                    lead to fetch<TmdbSameCastPersonCredits>(
                        endpoint = "person/${lead.id}/combined_credits",
                        query = mapOf("language" to language),
                    )?.cast.orEmpty()
                }
            }.map { it.await() }
        }

        val works = linkedMapOf<String, SameCastWork>()
        val byPerson = mutableMapOf<Int, MutableList<SameCastRole>>()
        credits.forEach { (lead, roles) ->
            val seen = mutableSetOf<String>()
            roles.forEach roles@{ r ->
                val m = r.mediaType ?: return@roles
                if ((m != "movie" && m != "tv") || r.adult == true || (m == mediaType && r.id == tmdbId)) return@roles
                if (r.genreIds.any { it in SAME_CAST_SKIP_GENRES } || SAME_CAST_SELF.containsMatchIn(r.character.orEmpty())) return@roles
                if (m == "tv" && (r.episodeCount ?: 0) < 3) return@roles
                val date = r.releaseDate?.takeIf { it.isNotBlank() } ?: r.firstAirDate?.takeIf { it.isNotBlank() }
                if (date == null || date > today || (r.voteCount ?: 0) < 20) return@roles
                val key = "$m:${r.id}"
                if (!seen.add(key)) return@roles
                val work = works.getOrPut(key) { SameCastWork(key, m, r) }
                val weight = if (m == "movie") {
                    val order = r.order ?: 99
                    if (order < 4) 1.0 else if (order < 10) 0.6 else 0.3
                } else {
                    if ((r.episodeCount ?: 0) >= 10) 1.0 else 0.3
                }
                val playing = sameCastCharacterName(r.character)
                if (playing.isNotEmpty() && lead.characters.any { sameCastCharacterName(it) == playing }) work.reprised++
                work.with += lead.name
                work.score += weight
                byPerson.getOrPut(lead.id) { mutableListOf() } += SameCastRole(work, weight)
            }
        }
        works.values.filter { it.reprised >= 2 }.map { it.key }.forEach(works::remove)

        val picked = mutableListOf<SameCastWork>()
        val taken = mutableSetOf<String>()
        fun take(w: SameCastWork) {
            if (picked.size < SAME_CAST_MAX && taken.add(w.key)) picked += w
        }
        works.values
            .filter { it.with.size > 1 && it.score >= 1.5 }
            .sortedWith(compareByDescending<SameCastWork> { it.score }.thenByDescending { it.fame })
            .forEach(::take)
        val queues = leads.map { lead ->
            ArrayDeque(
                byPerson[lead.id].orEmpty()
                    .sortedWith(compareByDescending<SameCastRole> { it.weight }.thenByDescending { it.work.fame }),
            )
        }
        var more = true
        while (more && picked.size < SAME_CAST_MAX) {
            more = false
            for (queue in queues) {
                while (queue.isNotEmpty() && (queue.first().work.key in taken || queue.first().work.key !in works)) {
                    queue.removeFirst()
                }
                queue.removeFirstOrNull()?.let {
                    take(it.work)
                    more = true
                }
            }
        }

        val items = picked.mapNotNull { w ->
            val r = w.raw
            val title = r.title?.trim()?.takeIf(String::isNotBlank)
                ?: r.name?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val surname = { name: String -> name.trim().substringAfterLast(' ') }
            // Who from the cast is in it: "Hugh Laurie", "Laurie & Wilde", "Laurie, Wilde +2"
            val withLabel = when (w.with.size) {
                1 -> w.with[0]
                2 -> w.with.joinToString(" & ", transform = surname)
                else -> w.with.take(2).joinToString(", ", transform = surname) + " +${w.with.size - 2}"
            }
            val date = r.releaseDate?.takeIf { it.isNotBlank() } ?: r.firstAirDate
            MetaPreview(
                id = "tmdb:${r.id}",
                type = if (w.media == "tv") "series" else "movie",
                name = title,
                poster = buildImageUrl(r.posterPath, "w500") ?: buildImageUrl(r.backdropPath, "w780"),
                banner = buildImageUrl(r.backdropPath, "w1280"),
                posterShape = PosterShape.Poster,
                description = r.overview?.trim()?.takeIf(String::isNotBlank),
                recommendationReason = "With $withLabel",
                releaseInfo = date?.take(4),
                rawReleaseDate = date,
                imdbRating = r.voteAverage?.formatRating(),
            )
        }
        sameCastMutex.withLock {
            if (sameCastCache.size > 200) sameCastCache.clear()
            sameCastCache[cacheKey] = items
        }
        items
    }

    private val titleProfileCache = mutableMapOf<String, TmdbTitleProfile>()
    private val personWorksCache = mutableMapOf<String, List<TmdbSearchResult>>()
    private val peopleCacheMutex = Mutex()

    /**
     * What Discover's taste rows need to know about a watched title (ported from the Nuvio Tracker
     * site's recommend.ts): its episode count, to tell a finished show from a dropped one, and the
     * people who lead it. A film's leads are its directors and top six billing; a show's are its
     * main cast (in 40% of the episodes or 50 of them). A show's directors change per episode, so
     * none are taken. One TMDB call per title, kept for the session.
     */
    suspend fun fetchTitleProfile(tmdbId: Int, mediaType: String): TmdbTitleProfile? = withContext(Dispatchers.Default) {
        if (mediaType != "movie" && mediaType != "tv") return@withContext null
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext null
        val key = "$mediaType:$tmdbId"
        peopleCacheMutex.withLock { titleProfileCache[key] }?.let { return@withContext it }
        val profile = if (mediaType == "movie") {
            val credits = fetch<TmdbLeadsMovieCredits>(endpoint = "movie/$tmdbId/credits") ?: return@withContext null
            TmdbTitleProfile(
                numberOfEpisodes = null,
                leads = (
                    credits.crew.filter { it.job == "Director" && it.id != null }
                        .map { TmdbLeadPerson(it.id!!, it.name.orEmpty(), isDirector = true) } +
                        credits.cast.filter { (it.order ?: 99) < SAME_CAST_MOVIE_BILLING && it.id != null }
                            .map { TmdbLeadPerson(it.id!!, it.name.orEmpty(), isDirector = false) }
                    ).distinctBy { it.id to it.isDirector },
            )
        } else {
            val details = fetch<TmdbLeadsShowDetails>(
                endpoint = "tv/$tmdbId",
                query = mapOf("append_to_response" to "aggregate_credits"),
            ) ?: return@withContext null
            val needed = ceil((details.numberOfEpisodes ?: 0) * 0.4).toInt().coerceIn(1, 50)
            TmdbTitleProfile(
                numberOfEpisodes = details.numberOfEpisodes,
                leads = details.aggregateCredits?.cast.orEmpty()
                    .filter { (it.totalEpisodeCount ?: 0) >= needed && (it.order ?: 999) < 30 && it.id != null }
                    .take(SAME_CAST_PEOPLE)
                    .map { TmdbLeadPerson(it.id!!, it.name.orEmpty(), isDirector = false) },
            )
        }
        peopleCacheMutex.withLock {
            if (titleProfileCache.size > 1000) titleProfileCache.clear()
            titleProfileCache[key] = profile
        }
        profile
    }

    /**
     * A person's other work as list results, most popular first: the films they directed, or the
     * films and shows they starred in (a film's top ten billing, five or more episodes of a show).
     * Talk/news/reality and documentary, "Self" appearances, unreleased titles and anything with
     * under 50 votes are left out.
     */
    suspend fun fetchPersonWorks(personId: Int, asDirector: Boolean): List<TmdbSearchResult> = withContext(Dispatchers.Default) {
        val settings = TmdbSettingsRepository.snapshot()
        if (!settings.enabled || !settings.hasApiKey) return@withContext emptyList()
        val language = normalizeTmdbLanguage(settings.language)
        val key = "$personId:$asDirector:$language"
        peopleCacheMutex.withLock { personWorksCache[key] }?.let { return@withContext it }
        val credits = fetch<TmdbLeadsPersonCredits>(
            endpoint = "person/$personId/combined_credits",
            query = mapOf("language" to language),
        ) ?: return@withContext emptyList()
        val today = CurrentDateProvider.todayIsoDate()
        val picked = if (asDirector) {
            credits.crew.filter { it.job == "Director" && it.mediaType == "movie" }
        } else {
            credits.cast
                .filter { c -> if (c.mediaType == "tv") (c.episodeCount ?: 0) >= 5 else (c.order ?: 99) < 10 }
                .filterNot { SAME_CAST_SELF.containsMatchIn(it.character.orEmpty()) }
        }
        val works = picked
            .asSequence()
            .filter { (it.mediaType == "movie" || it.mediaType == "tv") && it.adult != true }
            .filterNot { it.genreIds.any { genre -> genre in SAME_CAST_SKIP_GENRES } }
            .filter { (it.voteCount ?: 0) >= 50 }
            .filter { c ->
                val date = c.releaseDate?.takeIf(String::isNotBlank) ?: c.firstAirDate?.takeIf(String::isNotBlank)
                date != null && date <= today
            }
            .distinctBy { "${it.mediaType}:${it.id}" }
            .sortedByDescending { it.popularity ?: 0.0 }
            .map { c ->
                TmdbSearchResult(
                    id = c.id,
                    title = c.title,
                    name = c.name,
                    overview = c.overview,
                    posterPath = c.posterPath,
                    backdropPath = c.backdropPath,
                    releaseDate = c.releaseDate,
                    firstAirDate = c.firstAirDate,
                    popularity = c.popularity ?: 0.0,
                    mediaType = c.mediaType,
                    genreIds = c.genreIds,
                    voteAverage = c.voteAverage ?: 0.0,
                    voteCount = c.voteCount ?: 0,
                )
            }
            .toList()
        peopleCacheMutex.withLock {
            if (personWorksCache.size > 200) personWorksCache.clear()
            personWorksCache[key] = works
        }
        works
    }

    private suspend fun fetchCollection(
        collectionId: Int,
        language: String,
    ): Pair<String?, List<MetaPreview>> {
        val cacheKey = "$collectionId:$language:collection"
        collectionCache[cacheKey]?.let { return it }

        val response = fetch<TmdbCollectionResponse>(
            endpoint = "collection/$collectionId",
            query = mapOf("language" to language),
        ) ?: return null to emptyList()

        val items = response.parts
            .sortedBy { it.releaseDate?.takeIf(String::isNotBlank) ?: "9999" }
            .mapNotNull { part ->
                val title = part.title?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MetaPreview(
                    id = "tmdb:${part.id}",
                    type = "movie",
                    name = title,
                    poster = buildImageUrl(part.backdropPath, "w780")
                        ?: buildImageUrl(part.posterPath, "w500"),
                    banner = buildImageUrl(part.backdropPath, "w1280"),
                    posterShape = PosterShape.Landscape,
                    description = part.overview?.trim()?.takeIf(String::isNotBlank),
                    releaseInfo = part.releaseDate?.take(4),
                    rawReleaseDate = part.releaseDate,
                    imdbRating = part.voteAverage?.formatRating(),
                )
            }

        val result = response.name?.trim()?.takeIf(String::isNotBlank) to items
        collectionCache[cacheKey] = result
        return result
    }

    private fun trailerCacheKey(tmdbId: Int, mediaType: String, language: String, scope: TrailerScope): String =
        "$tmdbId:$mediaType:$language:trailers:${scope.cacheTag}"

    private fun cachedTrailers(
        cacheKey: String,
        tmdbId: Int,
        mediaType: String,
        language: String,
        scope: TrailerScope,
    ): List<MetaTrailer>? {
        trailerCache[cacheKey]?.let { return it }
        // An all-seasons result is a superset, so it answers a single-season request too. The
        // reverse is not true and must never be served the other way round.
        if (scope is TrailerScope.SingleSeason) {
            trailerCache["$tmdbId:$mediaType:$language:trailers:all"]?.let { return it }
        }
        return null
    }

    private suspend fun fetchTrailers(
        tmdbId: Int,
        mediaType: String,
        language: String,
        scope: TrailerScope,
        /** The title's own videos, when they arrived appended to the details call. */
        prefetchedVideos: List<TmdbVideoResult>? = null,
        /** The show's season count, when the caller already holds its details. */
        knownSeasonCount: Int? = null,
    ): List<MetaTrailer> {
        val cacheKey = trailerCacheKey(tmdbId, mediaType, language, scope)
        cachedTrailers(cacheKey, tmdbId, mediaType, language, scope)?.let { return it }

        val lock = episodeCacheMutex.withLock { trailerFetchLocks.getOrPut(cacheKey) { Mutex() } }
        return lock.withLock {
            trailerCache[cacheKey]?.let { return@withLock it }
            fetchTrailersUncached(tmdbId, mediaType, language, cacheKey, scope, prefetchedVideos, knownSeasonCount)
        }
    }

    private suspend fun fetchTrailersUncached(
        tmdbId: Int,
        mediaType: String,
        language: String,
        cacheKey: String,
        scope: TrailerScope,
        prefetchedVideos: List<TmdbVideoResult>? = null,
        knownSeasonCount: Int? = null,
    ): List<MetaTrailer> {
        val allVideos = mutableListOf<MetaTrailer>()

        val primaryVideos = prefetchedVideos ?: fetchTmdbVideos(
            endpoint = "$mediaType/$tmdbId/videos",
            language = language,
        )
        allVideos += primaryVideos.map { video ->
            video.toMetaTrailer(
                seasonNumber = null,
                displayName = video.name,
            )
        }

        if (mediaType == "tv") {
            // The enrichment's own details call already carries the season count; re-fetching the
            // show's details just for it was one extra request per show.
            val seasonCount = (
                knownSeasonCount ?: fetch<TmdbDetailsResponse>(
                    endpoint = "tv/$tmdbId",
                    query = mapOf("language" to language),
                )?.numberOfSeasons ?: 0
                ).coerceAtLeast(0)
            val wantedSeasons = trailerSeasonsFor(scope, seasonCount)
            if (seasonCount > 0) {
                val seasonVideos = coroutineScope {
                    wantedSeasons.map { seasonNumber ->
                        async {
                            seasonNumber to fetchTmdbVideos(
                                endpoint = "tv/$tmdbId/season/$seasonNumber/videos",
                                language = language,
                            )
                        }
                    }.awaitAll()
                }

                seasonVideos.forEach { (seasonNumber, videos) ->
                    allVideos += videos.map { video ->
                        video.toMetaTrailer(
                            seasonNumber = seasonNumber,
                            displayName = runBlocking {
                                getString(
                                    Res.string.trailer_season_label,
                                    seasonNumber,
                                    video.name.orEmpty(),
                                )
                            },
                        )
                    }
                }
            }
        }

        val byCategory = linkedMapOf<String, MutableList<MetaTrailer>>()
        allVideos
            .asSequence()
            .filter { trailer ->
                trailer.site.equals("YouTube", ignoreCase = true) && trailer.key.isNotBlank()
            }
            .forEach { trailer ->
                byCategory.getOrPut(
                    trailer.type.ifBlank { runBlocking { getString(Res.string.generic_trailer) } },
                ) { mutableListOf() }
                    .add(trailer)
            }

        byCategory.values.forEach { trailers ->
            trailers.sortWith(
                compareBy<MetaTrailer> {
                    when {
                        it.seasonNumber != null -> 0
                        else -> 1
                    }
                }
                    .thenByDescending { it.seasonNumber ?: Int.MIN_VALUE }
                    .thenByDescending { it.official }
                    .thenByDescending { it.publishedAt.orEmpty() }
            )
        }

        val sortedCategories = byCategory.keys.sortedWith(
            compareBy<String> { category ->
                when {
                    category.equals(
                        runBlocking { getString(Res.string.generic_trailer) },
                        ignoreCase = true,
                    ) -> 0
                    byCategory[category].orEmpty().any { it.official } -> 1
                    else -> 2
                }
            }.thenBy { it.lowercase() }
        )

        val result = sortedCategories.flatMap { byCategory[it].orEmpty() }
        trailerCache[cacheKey] = result
        return result
    }

    private suspend fun fetchTmdbVideos(
        endpoint: String,
        language: String,
    ): List<TmdbVideoResult> {
        val response = fetch<TmdbVideosResponse>(
            endpoint = endpoint,
            query = mapOf("language" to language),
        )
        return response?.results.orEmpty()
    }
}

internal data class TmdbEnrichment(
    val tmdbId: Int? = null,
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val people: List<MetaPerson>,
    val director: List<String>,
    val producer: List<String>,
    val writer: List<String>,
    val releaseInfo: String?,
    val lastAirDate: String? = null,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val ageRating: String?,
    val status: String?,
    val countries: List<String>,
    val language: String?,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val collectionName: String? = null,
    val collectionItems: List<MetaPreview> = emptyList(),
    val moreLikeThis: List<MetaPreview> = emptyList(),
    val trailers: List<MetaTrailer> = emptyList(),
    val keywords: List<String> = emptyList(),
) {
    fun hasContent(): Boolean =
        localizedTitle != null ||
            description != null ||
            genres.isNotEmpty() ||
            backdrop != null ||
            logo != null ||
            poster != null ||
            people.isNotEmpty() ||
            director.isNotEmpty() ||
            producer.isNotEmpty() ||
            writer.isNotEmpty() ||
            releaseInfo != null ||
            lastAirDate != null ||
            rating != null ||
            runtimeMinutes != null ||
            ageRating != null ||
            status != null ||
            countries.isNotEmpty() ||
            language != null ||
            productionCompanies.isNotEmpty() ||
            networks.isNotEmpty() ||
            collectionItems.isNotEmpty() ||
            moreLikeThis.isNotEmpty() ||
            trailers.isNotEmpty()
}

/** Whether this record keeps its own text and episodes under the user's [TmdbShowEnrichment] choice. */
internal fun TmdbSettings.keepsAddonRecord(meta: MetaDetails): Boolean =
    showEnrichment == TmdbShowEnrichment.SourceAware && meta.isTvdbOrKitsuShowRecord()

private val ShowTypes = setOf("series", "tv", "show", "tvshow", "anime")
private val SourceIdPrefixes = listOf("tvdb:", "kitsu:", "mal:", "anilist:", "anidb:")
private val SourceStillHosts = listOf("thetvdb.com", "kitsu.app", "kitsu.io")

/**
 * A show or anime whose addon built the record from TheTVDB or Kitsu (AIOMetadata's defaults for
 * series and anime): an id in one of their namespaces, a MyAnimeList id (anime addons send one,
 * Cinemeta does not), or episode stills hosted by TheTVDB or Kitsu. Not the TVDB id field alone:
 * Cinemeta sends `tvdb_id` too, and its records are the ones worth upgrading.
 */
internal fun MetaDetails.isTvdbOrKitsuShowRecord(): Boolean {
    if (type.trim().lowercase() !in ShowTypes) return false
    val lowerId = id.trim().lowercase()
    if (SourceIdPrefixes.any(lowerId::startsWith)) return true
    if (!malId.isNullOrBlank()) return true
    return videos.any { video ->
        val still = video.thumbnail?.lowercase() ?: return@any false
        SourceStillHosts.any(still::contains)
    }
}

internal data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val seasonPoster: String? = null,
    val airDate: String?,
    val runtimeMinutes: Int?,
)

private fun normalizeMetaType(type: String): String =
    when (type.trim().lowercase()) {
        "series", "tv", "show", "tvshow" -> "tv"
        else -> "movie"
    }

internal fun normalizeTmdbLanguage(language: String?): String {
    val raw = language
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.replace('_', '-')
        ?: return "en"
    val parts = raw.split("-")
    val normalized = if (parts.size == 2) {
        "${parts[0].lowercase()}-${parts[1].uppercase()}"
    } else {
        raw.lowercase()
    }
    return when (normalized) {
        "es-419" -> "es-MX"
        else -> normalized
    }
}

private fun buildPeople(
    details: TmdbDetailsResponse,
    credits: TmdbCreditsResponse?,
    mediaType: String,
): List<MetaPerson> {
    val creators = if (mediaType == "tv") {
        details.createdBy.mapNotNull { creator ->
            val name = creator.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = runBlocking { getString(Res.string.person_role_creator) },
                photo = buildImageUrl(creator.profilePath, "w500"),
                tmdbId = creator.id,
            )
        }
    } else {
        emptyList()
    }

    val directors = credits?.crew.orEmpty()
        .filter { it.job.equals("Director", ignoreCase = true) }
        .mapNotNull { crew ->
            val name = crew.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = runBlocking { getString(Res.string.person_role_director) },
                photo = buildImageUrl(crew.profilePath, "w500"),
                tmdbId = crew.id,
            )
        }

    val writers = credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job.contains("writer") || job.contains("screenplay")
        }
        .mapNotNull { crew ->
            val name = crew.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = runBlocking { getString(Res.string.person_role_writer) },
                photo = buildImageUrl(crew.profilePath, "w500"),
                tmdbId = crew.id,
            )
        }

    val cast = credits?.cast.orEmpty()
        .mapNotNull { castMember ->
            val name = castMember.name?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            MetaPerson(
                name = name,
                role = castMember.character?.trim()?.takeIf(String::isNotBlank),
                photo = buildImageUrl(castMember.profilePath, "w500"),
                tmdbId = castMember.id,
                episodeCount = castMember.episodeCount.takeIf { mediaType == "tv" },
            )
        }

    val primaryCrew = when {
        mediaType == "tv" && creators.isNotEmpty() -> creators
        mediaType != "tv" && directors.isNotEmpty() -> directors
        else -> writers
    }

    return (primaryCrew + cast)
        .dedupePeople()
}

private fun buildDirectors(
    details: TmdbDetailsResponse,
    credits: TmdbCreditsResponse?,
    mediaType: String,
): List<String> {
    if (mediaType == "tv") {
        return details.createdBy
            .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
    }

    return credits?.crew.orEmpty()
        .filter { it.job.equals("Director", ignoreCase = true) }
        .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
}

private fun buildWriters(
    credits: TmdbCreditsResponse?,
): List<String> {
    return credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job.contains("writer") || job.contains("screenplay")
        }
        .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
}

private fun buildProducers(credits: TmdbCreditsResponse?): List<String> =
    credits?.crew.orEmpty()
        .filter { crew ->
            val job = crew.job?.lowercase().orEmpty()
            job == "producer" ||
                job == "executive producer" ||
                job == "co-producer" ||
                job == "co-executive producer"
        }
        .mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
        .distinct()

private fun List<MetaPerson>.dedupePeople(): List<MetaPerson> {
    val merged = linkedMapOf<String, MetaPerson>()
    forEach { person ->
        val key = person.name.normalizedPersonName() + "|" + person.role.orEmpty().lowercase()
        val existing = merged[key]
        merged[key] = if (existing == null) {
            person
        } else {
            existing.copy(photo = existing.photo ?: person.photo)
        }
    }
    return merged.values.toList()
}

// Accent- and punctuation-blind, so an addon's "Lauren Velez" and TMDB's "Lauren Vélez" merge.
private fun String.normalizedPersonName(): String =
    com.nuvio.app.features.details.personNameKey(this).ifBlank { trim().lowercase() }

private fun buildImageUrl(path: String?, size: String): String? {
    val clean = path?.trim()?.takeIf(String::isNotBlank) ?: return null
    return "https://image.tmdb.org/t/p/$size$clean"
}

private fun List<TmdbImage>.selectBestLocalizedImagePath(normalizedLanguage: String): String? {
    if (isEmpty()) return null
    val langCode = normalizedLanguage.substringBefore("-")

    // AIOMetadata algorithm: single O(N) pass across language tiers, vote_average tiebreaker.
    // Tier order: target lang → English → null/xx (language-neutral) → any other lang
    var bestTarget: TmdbImage? = null
    var bestEn: TmdbImage? = null
    var bestNull: TmdbImage? = null
    var bestAny: TmdbImage? = null

    for (img in this) {
        val lang = img.iso6391
        val score = img.voteAverage ?: 0.0
        when {
            lang == langCode -> if (bestTarget == null || score > (bestTarget!!.voteAverage ?: 0.0)) bestTarget = img
            lang == "en" -> if (bestEn == null || score > (bestEn!!.voteAverage ?: 0.0)) bestEn = img
            lang == null || lang == "xx" -> if (bestNull == null || score > (bestNull!!.voteAverage ?: 0.0)) bestNull = img
            else -> if (bestAny == null || score > (bestAny!!.voteAverage ?: 0.0)) bestAny = img
        }
    }
    return (bestTarget ?: bestEn ?: bestNull ?: bestAny)?.filePath
}

private val defaultLanguageRegions = mapOf(
    "pt" to "PT",
    "es" to "ES",
)

private fun Double.formatRating(): String =
    if (this == 0.0) {
        "0.0"
    } else {
        (kotlin.math.round(this * 10.0) / 10.0).toString()
    }

private fun Int.formatRuntime(): String = "${this}m"

private fun List<TmdbMovieReleaseDateCountry>.selectMovieAgeRating(normalizedLanguage: String): String? {
    val preferredRegions = preferredRegions(normalizedLanguage)
    val byRegion = associateBy { it.iso31661?.uppercase() }
    preferredRegions.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull(String::isNotBlank)
        if (!rating.isNullOrBlank()) return rating
    }
    return asSequence()
        .flatMap { it.releaseDates.asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull(String::isNotBlank)
}

private fun List<TmdbTvContentRating>.selectTvAgeRating(normalizedLanguage: String): String? {
    val preferredRegions = preferredRegions(normalizedLanguage)
    val byRegion = associateBy { it.iso31661?.uppercase() }
    preferredRegions.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return mapNotNull { it.rating?.trim() }.firstOrNull(String::isNotBlank)
}

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val directRegion = normalizedLanguage.substringAfter("-", "").uppercase().takeIf { it.length == 2 }
    return buildList {
        if (!directRegion.isNullOrBlank()) add(directRegion)
        add("US")
        add("GB")
    }.distinct()
}

private fun TmdbCompany.toMetaCompany(): MetaCompany? {
    val name = name?.trim()?.takeIf(String::isNotBlank) ?: return null
    return MetaCompany(
        name = name,
        logo = buildImageUrl(logoPath, "w300"),
        tmdbId = id,
    )
}

@Serializable
private data class TmdbDetailsResponse(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("production_countries") val productionCountries: List<TmdbProductionCountry> = emptyList(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreator> = emptyList(),
    val genres: List<TmdbNamedItem> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    val networks: List<TmdbCompany> = emptyList(),
    @SerialName("belongs_to_collection") val belongsToCollection: TmdbCollectionRef? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    /** Present only with `append_to_response=keywords`. */
    val keywords: TmdbAppendedKeywords? = null,
    // The rest are present only when appended; see fetchEnrichment.
    val images: TmdbImagesResponse? = null,
    val credits: TmdbCreditsResponse? = null,
    @SerialName("aggregate_credits") val aggregateCredits: TmdbAggregateCreditsResponse? = null,
    @SerialName("release_dates") val releaseDates: TmdbMovieReleaseDatesResponse? = null,
    @SerialName("content_ratings") val contentRatings: TmdbTvContentRatingsResponse? = null,
    val recommendations: TmdbRecommendationResponse? = null,
    val videos: TmdbVideosResponse? = null,
)

/** TMDB puts a movie's keywords under `keywords` and a show's under `results`. */
@Serializable
private data class TmdbAppendedKeywords(
    val keywords: List<TmdbNamedItem> = emptyList(),
    val results: List<TmdbNamedItem> = emptyList(),
) {
    fun names(): List<String> =
        (keywords + results).mapNotNull { it.name?.trim()?.takeIf(String::isNotBlank) }
}

@Serializable
private data class TmdbVideosResponse(
    val results: List<TmdbVideoResult> = emptyList(),
)

@Serializable
private data class TmdbVideoResult(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
    val site: String? = null,
    val size: Int? = null,
    val type: String? = null,
    val official: Boolean? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
)

private fun TmdbVideoResult.toMetaTrailer(
    seasonNumber: Int?,
    displayName: String?,
): MetaTrailer {
    val videoKey = key?.trim().orEmpty()
    val videoName = name?.trim().takeUnless { it.isNullOrBlank() } ?: runBlocking { getString(Res.string.generic_trailer) }
    val trailerId = id?.trim().takeUnless { it.isNullOrBlank() } ?: videoKey
    return MetaTrailer(
        id = trailerId,
        key = videoKey,
        name = videoName,
        site = site?.trim().takeUnless { it.isNullOrBlank() } ?: "YouTube",
        size = size,
        type = type?.trim().takeUnless { it.isNullOrBlank() } ?: runBlocking { getString(Res.string.generic_trailer) },
        official = official == true,
        publishedAt = publishedAt,
        seasonNumber = seasonNumber,
        displayName = displayName,
        language = iso6391?.trim()?.takeIf(String::isNotBlank),
    )
}

@Serializable
private data class TmdbNamedItem(
    val name: String? = null,
)

@Serializable
private data class TmdbProductionCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
)

@Serializable
private data class TmdbCreator(
    val name: String? = null,
    val id: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbCreditsResponse(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

/** Most people a series keeps from aggregate_credits, by episode count; long runs list over a thousand. */
private const val AGGREGATE_CAST_LIMIT = 60

/** An addon cast list this short is top billing only, not the full cast; see enrich(). */
private const val SHORT_ADDON_CAST_SIZE = 6
private const val AGGREGATE_CREW_LIMIT = 60

@Serializable
private data class TmdbAggregateCreditsResponse(
    val cast: List<TmdbAggregateCastMember> = emptyList(),
    val crew: List<TmdbAggregateCrewMember> = emptyList(),
) {
    /**
     * Folds the per-role lists into the plain credits shape the rest of this file reads. Each
     * person is ranked by how many episodes they appear in, so series regulars lead and the
     * one-episode guests are what the limit cuts; a person's character is their longest-running role.
     */
    fun toStandard(): TmdbCreditsResponse = TmdbCreditsResponse(
        cast = cast
            .sortedByDescending { it.totalEpisodeCount ?: it.roles.sumOf { role -> role.episodeCount ?: 0 } }
            .take(AGGREGATE_CAST_LIMIT)
            .map { member ->
                TmdbCastMember(
                    id = member.id,
                    name = member.name,
                    character = member.roles.maxByOrNull { it.episodeCount ?: 0 }?.character,
                    profilePath = member.profilePath,
                    episodeCount = member.totalEpisodeCount ?: member.roles.sumOf { role -> role.episodeCount ?: 0 }.takeIf { it > 0 },
                )
            },
        crew = crew
            .sortedByDescending { it.totalEpisodeCount ?: it.jobs.sumOf { job -> job.episodeCount ?: 0 } }
            .take(AGGREGATE_CREW_LIMIT)
            .flatMap { member ->
                member.jobs.map { job ->
                    TmdbCrewMember(
                        id = member.id,
                        name = member.name,
                        job = job.job,
                        profilePath = member.profilePath,
                    )
                }
            },
    )
}

@Serializable
private data class TmdbAggregateCastMember(
    val id: Int? = null,
    val name: String? = null,
    val roles: List<TmdbAggregateRole> = emptyList(),
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbAggregateRole(
    val character: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbAggregateCrewMember(
    val id: Int? = null,
    val name: String? = null,
    val jobs: List<TmdbAggregateJob> = emptyList(),
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbAggregateJob(
    val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbCastMember(
    val id: Int? = null,
    val name: String? = null,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("total_episode_count") val episodeCount: Int? = null,
)

@Serializable
private data class TmdbCrewMember(
    val id: Int? = null,
    val name: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
private data class TmdbImagesResponse(
    val logos: List<TmdbImage> = emptyList(),
)

@Serializable
private data class TmdbImage(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
private data class TmdbMovieReleaseDatesResponse(
    val results: List<TmdbMovieReleaseDateCountry> = emptyList(),
)

@Serializable
private data class TmdbMovieReleaseDateCountry(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
private data class TmdbReleaseDate(
    val certification: String? = null,
)

@Serializable
private data class TmdbTvContentRatingsResponse(
    val results: List<TmdbTvContentRating> = emptyList(),
)

@Serializable
private data class TmdbTvContentRating(
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val rating: String? = null,
)

@Serializable
private data class TmdbCompany(
    val id: Int? = null,
    val name: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable
private data class TmdbCollectionRef(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
private data class TmdbRecommendationResponse(
    val results: List<TmdbRecommendationItem> = emptyList(),
)

@Serializable
private data class TmdbRecommendationItem(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("media_type") val mediaType: String? = null,
)

@Serializable
private data class TmdbCollectionResponse(
    val name: String? = null,
    val parts: List<TmdbCollectionPart> = emptyList(),
)

@Serializable
private data class TmdbCollectionPart(
    val id: Int,
    val title: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
private data class TmdbSeasonDetailsResponse(
    @SerialName("poster_path") val posterPath: String? = null,
    val episodes: List<TmdbEpisodeResponse> = emptyList(),
)

@Serializable
private data class TmdbEpisodeResponse(
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
)

// ─── Person Detail Models ───

@Serializable
private data class TmdbPersonResponse(
    val id: Int? = null,
    val name: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
)

@Serializable
private data class TmdbPersonCombinedCreditsResponse(
    val cast: List<TmdbPersonCreditCast> = emptyList(),
    val crew: List<TmdbPersonCreditCrew> = emptyList(),
)

@Serializable
private data class TmdbPersonCreditCast(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val character: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
)

@Serializable
private data class TmdbPersonCreditCrew(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val job: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
)

// ─── Same cast ───

private const val SAME_CAST_PEOPLE = 10
private const val SAME_CAST_MOVIE_BILLING = 6
private const val SAME_CAST_MAX = 20

/** Talk, news, reality, documentary. */
private val SAME_CAST_SKIP_GENRES = setOf(10767, 10763, 10764, 99)
private val SAME_CAST_SELF = Regex("\\b(self|himself|herself|themselves|host|narrator)\\b", RegexOption.IGNORE_CASE)
private val SAME_CAST_PARENTHETICAL = Regex("\\s*\\(.*?\\)\\s*")

private fun sameCastCharacterName(value: String?): String =
    value.orEmpty().lowercase().replace(SAME_CAST_PARENTHETICAL, " ").trim()

private data class SameCastLead(val id: Int, val name: String, val characters: List<String>)

private class SameCastWork(val key: String, val media: String, val raw: TmdbSameCastCredit) {
    val with = mutableListOf<String>()
    var score = 0.0
    var reprised = 0
    val fame: Int get() = raw.voteCount ?: 0
}

private class SameCastRole(val work: SameCastWork, val weight: Double)

@Serializable
private data class TmdbSameCastShowDetails(
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("aggregate_credits") val aggregateCredits: TmdbSameCastAggregate? = null,
)

@Serializable
private data class TmdbSameCastAggregate(
    val cast: List<TmdbSameCastAggregateMember> = emptyList(),
)

@Serializable
private data class TmdbSameCastAggregateMember(
    val id: Int? = null,
    val name: String? = null,
    val order: Int? = null,
    val roles: List<TmdbAggregateRole> = emptyList(),
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null,
)

@Serializable
private data class TmdbSameCastMovieCredits(
    val cast: List<TmdbSameCastMovieMember> = emptyList(),
)

@Serializable
private data class TmdbSameCastMovieMember(
    val id: Int? = null,
    val name: String? = null,
    val character: String? = null,
    val order: Int? = null,
)

@Serializable
private data class TmdbSameCastPersonCredits(
    val cast: List<TmdbSameCastCredit> = emptyList(),
)

@Serializable
private data class TmdbSameCastCredit(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val character: String? = null,
    val adult: Boolean? = null,
    val order: Int? = null,
    val overview: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
)

// ─── Discover people rows ───

/** Someone who leads a title: a director/creator, or main cast. */
data class TmdbLeadPerson(val id: Int, val name: String, val isDirector: Boolean)

/** A watched title as Discover's taste rows see it. [numberOfEpisodes] is null for a film. */
data class TmdbTitleProfile(val numberOfEpisodes: Int?, val leads: List<TmdbLeadPerson>)

@Serializable
private data class TmdbLeadsMovieCredits(
    val cast: List<TmdbSameCastMovieMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

@Serializable
private data class TmdbLeadsShowDetails(
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    @SerialName("aggregate_credits") val aggregateCredits: TmdbSameCastAggregate? = null,
)

@Serializable
private data class TmdbLeadsPersonCredits(
    val cast: List<TmdbLeadsCredit> = emptyList(),
    val crew: List<TmdbLeadsCredit> = emptyList(),
)

@Serializable
private data class TmdbLeadsCredit(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val character: String? = null,
    val job: String? = null,
    val adult: Boolean? = null,
    val order: Int? = null,
    val overview: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("vote_count") val voteCount: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val popularity: Double? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
)

// ─── Entity Browse (Company / Network) Models ───

private const val ENTITY_RAIL_MAX_ITEMS = 20
private const val ENTITY_TOP_RATED_VOTE_FLOOR = 200

// Hero badge browse. The vote floor keeps a language or keyword "Popular" rail from filling with
// untitled uploads; the window and runtime cap mirror the badge rules in HeroDiscoveryMetadataService.
private const val BADGE_DISCOVER_VOTE_FLOOR = 50
private const val NEW_AT_HOME_WINDOW_DAYS = 30
private const val SHORT_FILM_MAX_MINUTES = 39
private const val RELEASE_STATUS_SOURCE_PAGES_PER_RAIL_PAGE = 2

enum class TmdbEntityKind(val routeValue: String) {
    COMPANY("company"),
    NETWORK("network");

    companion object {
        fun fromRouteValue(value: String): TmdbEntityKind = when (value.trim().lowercase()) {
            "network" -> NETWORK
            else -> COMPANY
        }
    }
}

enum class TmdbEntityMediaType(val value: String) {
    MOVIE("movie"),
    TV("tv"),
}

enum class TmdbEntityRailType(val value: String) {
    POPULAR("popular"),
    TOP_RATED("top_rated"),
    RECENT("recent"),

    // Emitted only by the hero badge browse (see fetchBadgeBrowse); the company/network rails
    // never use them.
    WINNERS("winners"),
    NOMINEES("nominees"),
    /** A single rail whose label is the badge itself — trending, new at home, now playing. */
    FEATURED("featured"),
}

data class TmdbEntityHeader(
    val id: Int,
    val kind: TmdbEntityKind,
    val name: String,
    val logo: String?,
    val originCountry: String?,
    val secondaryLabel: String?,
    val description: String?,
)

data class TmdbEntityRail(
    val mediaType: TmdbEntityMediaType,
    val railType: TmdbEntityRailType,
    val items: List<MetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
)

data class TmdbEntityBrowseData(
    val header: TmdbEntityHeader,
    val rails: List<TmdbEntityRail>,
)

data class TmdbEntityRailPageResult(
    val items: List<MetaPreview>,
    val hasMore: Boolean,
)

@Serializable
private data class TmdbCompanyDetailsResponse(
    val id: Int,
    val name: String? = null,
    val description: String? = null,
    val headquarters: String? = null,
    val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
private data class TmdbNetworkDetailsResponse(
    val id: Int,
    val name: String? = null,
    val headquarters: String? = null,
    val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
private data class TmdbDiscoverResponse(
    val page: Int? = null,
    val results: List<TmdbDiscoverResult> = emptyList(),
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("total_results") val totalResults: Int? = null,
)

@Serializable
private data class TmdbDiscoverResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
)

/**
 * Which season numbers a trailer lookup should request.
 *
 * Pure and tested because the zero-seasons case broke production: `coerceIn(1, 0)` throws
 * `IllegalArgumentException: Cannot coerce value to an empty range`, and since this runs inside the
 * shared meta fetch, that exception failed the **whole** meta — nine series in one session lost
 * their metadata to a trailer lookup they did not even need. TMDB reports 0 seasons for plenty of
 * records, so it is an ordinary input, not an edge case.
 */
internal fun trailerSeasonsFor(
    scope: TmdbMetadataService.TrailerScope,
    seasonCount: Int,
): List<Int> = when {
    seasonCount <= 0 -> emptyList()
    scope is TmdbMetadataService.TrailerScope.AllSeasons -> (1..seasonCount).toList()
    // The season the user is on, clamped into range, or the first — one request instead of one
    // per season, which is the whole point of the scope.
    else -> listOf(
        (scope as TmdbMetadataService.TrailerScope.SingleSeason).preferred?.coerceIn(1, seasonCount)
            ?: 1,
    )
}
