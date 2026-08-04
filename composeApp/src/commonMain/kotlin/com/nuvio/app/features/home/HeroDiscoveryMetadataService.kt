package com.nuvio.app.features.home

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.parseRuntimeMinutes
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object HeroDiscoveryMetadataService {
    const val CACHE_VERSION = 17

    /**
     * Turns a saved priority string into the slot list to evaluate.
     *
     * This only renames slots — it must never *add* one. Slots added after the setting shipped are
     * migrated into the saved string once by HomeCatalogSettingsRepository instead. Adding here
     * looked equivalent but silently overrode the user: the settings page edits the saved string,
     * so an unticked slot came straight back on the next read and could never be turned off.
     */
    internal fun normalizePriority(priority: String): List<String> {
        val slots = priority
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toMutableList()
        // Rename: the single "structural" slot was split into three distinct badges.
        val structuralIndex = slots.indexOf("structural")
        if (structuralIndex >= 0) {
            slots.removeAt(structuralIndex)
            slots.addAll(structuralIndex, listOf("short_film", "mini_series", "binge_ready"))
        }
        return slots
    }

    private val log = Logger.withTag("HeroDiscovery")
    private val cacheMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<List<HeroDiscoveryFact>>>()

    suspend fun fetch(
        type: String,
        id: String,
        priority: List<String>,
        releaseStatusUnavailableOnly: Boolean = true,
    ): List<HeroDiscoveryFact> {
        val cacheKey = "v$CACHE_VERSION:$type:$id:${priority.hashCode()}:unavailableOnly=$releaseStatusUnavailableOnly"
        val now = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs()
        var ownsRequest = false
        val pending = cacheMutex.withLock {
            inFlightRequests[cacheKey] ?: CompletableDeferred<List<HeroDiscoveryFact>>().also {
                inFlightRequests[cacheKey] = it
                ownsRequest = true
            }
        }
        if (!ownsRequest) return pending.await()

        val facts = try {
            val meta = MetaDetailsRepository.peek(type = type, id = id)
                ?: MetaDetailsRepository.fetch(type = type, id = id)
            if (meta != null) {
                val enrichedMeta = enrichDiscoveryMeta(meta = meta, fallbackItemId = id)
                computeFacts(enrichedMeta, priority, releaseStatusUnavailableOnly)
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            cacheMutex.withLock { inFlightRequests.remove(cacheKey)?.completeExceptionally(e) }
            throw e
        } catch (e: Throwable) {
            log.w(e) { "Discovery request failed for $cacheKey" }
            emptyList()
        }

        cacheMutex.withLock {
            inFlightRequests.remove(cacheKey)?.complete(facts)
        }
        return facts
    }

    private suspend fun enrichDiscoveryMeta(
        meta: MetaDetails,
        fallbackItemId: String,
    ): MetaDetails {
        val settings = MdbListSettingsRepository.snapshot()
        val mdbListMeta = if (MdbListMetadataService.shouldFetchForMeta(meta, fallbackItemId, settings)) {
            withTimeoutOrNull(MDBLIST_DISCOVERY_TIMEOUT_MS) {
                MdbListMetadataService.enrichMeta(
                    meta = meta,
                    fallbackItemId = fallbackItemId,
                    settings = settings,
                )
            } ?: meta
        } else {
            meta
        }
        val tmdbId = mdbListMeta.tmdbId ?: resolveTmdbId(mdbListMeta, fallbackItemId)
        return if (tmdbId != null && tmdbId != mdbListMeta.tmdbId) {
            mdbListMeta.copy(tmdbId = tmdbId)
        } else {
            mdbListMeta
        }
    }

    private suspend fun resolveTmdbId(meta: MetaDetails, fallbackItemId: String): Int? {
        if (!meta.imdbTmdbIdentityTrusted) return null
        meta.id.extractTmdbId()?.let { return it }
        fallbackItemId.extractTmdbId()?.let { return it }
        val externalId = fallbackItemId.split("_").firstOrNull { it.startsWith("tt", ignoreCase = true) }
            ?: meta.id.split("_").firstOrNull { it.startsWith("tt", ignoreCase = true) }
            ?: fallbackItemId
        return TmdbService.ensureTmdbId(externalId, meta.type)?.toIntOrNull()
    }

    private suspend fun computeFacts(
        meta: MetaDetails,
        priority: List<String>,
        releaseStatusUnavailableOnly: Boolean,
    ): List<HeroDiscoveryFact> {
        val discoveryMeta = extractDiscoveryMeta(meta, priority)
        return pickSashMultiple(discoveryMeta, priority, releaseStatusUnavailableOnly)
    }

    internal suspend fun computeFactsForMeta(
        meta: MetaDetails,
        priority: List<String>,
        releaseStatusUnavailableOnly: Boolean = true,
    ): List<HeroDiscoveryFact> =
        computeFacts(meta, priority, releaseStatusUnavailableOnly)

    private suspend fun extractDiscoveryMeta(
        meta: MetaDetails,
        priority: List<String>,
    ): DiscoveryMeta {
        val keywordNames = meta.mdblistKeywords.map { it.lowercase().trim() }.toSet()

        val awardWins = mutableListOf<String>()
        val awardNoms = mutableListOf<String>()
        fun addDistinct(target: MutableList<String>, label: String) {
            if (target.none { it.equals(label, ignoreCase = true) }) target += label
        }

        // Best Picture
        if ("best-picture-winner" in keywordNames) addDistinct(awardWins, "Best Picture")
        else if ("best-picture-nominated" in keywordNames) addDistinct(awardNoms, "Best Picture")

        val tmdbId = meta.tmdbId ?: meta.id
            .removePrefix("tmdb:")
            .substringBefore(':')
            .toIntOrNull()

        if (tmdbId != null) {
            if (tmdbId in HeroDiscoveryAwards.BEST_PICTURE_WINNER_TMDB_IDS) {
                addDistinct(awardWins, "Best Picture")
            } else if (tmdbId in HeroDiscoveryAwards.BEST_PICTURE_NOM_TMDB_IDS) {
                addDistinct(awardNoms, "Best Picture")
            }

            // Golden Globe
            if (tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_DRAMA_WINNER_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_COMEDY_WINNER_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_TV_DRAMA_WINNER_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_TV_COMEDY_WINNER_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_TV_LIMITED_WINNER_TMDB_IDS
            ) {
                awardWins.add("Golden Globe")
            } else if (tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_DRAMA_NOM_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_COMEDY_NOM_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_TV_DRAMA_NOM_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_TV_COMEDY_NOM_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.GOLDEN_GLOBE_TV_LIMITED_NOM_TMDB_IDS
            ) {
                awardNoms.add("Golden Globe")
            }

            // Emmy
            if (tmdbId in HeroDiscoveryAwards.EMMY_WINNER_TMDB_IDS) {
                awardWins.add("Emmy Winner")
            } else if (tmdbId in HeroDiscoveryAwards.EMMY_DRAMA_NOM_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.EMMY_COMEDY_NOM_TMDB_IDS ||
                tmdbId in HeroDiscoveryAwards.EMMY_LIMITED_NOM_TMDB_IDS
            ) {
                awardNoms.add("Emmy Nominee")
            }
        }

        applyAwardsText(meta.awards, awardWins, awardNoms)

        var festivalLabel: String? = null
        for ((kw, label) in FESTIVAL_KEYWORDS) {
            if (kw in keywordNames) {
                festivalLabel = label
                break
            }
        }

        val isCult = "cult-classic" in keywordNames || "cult-film" in keywordNames
        val isTrueStory = "based-on-true-story" in keywordNames
        // TMDB tags stingers as two independent keywords (ids 179430 / 179431) and MDBList passes
        // them through verbatim. They are tagged per episode on TV, and MDBList only exposes
        // series-level keywords, so this can only ever fire for movies.
        val hasMidCreditsScene = "duringcreditsstinger" in keywordNames
        val hasPostCreditsScene = "aftercreditsstinger" in keywordNames
        val isMetacriticMustSee = "metacritic-must-see" in keywordNames ||
            meta.externalRatings.isMetacriticMustSee()
        val discoveryConfig = HeroDiscoveryConfigRepository.snapshot()

        val matchedStudios = meta.productionCompanies
            .mapNotNull { discoveryConfig.studios[it.name] }
            .distinct()

        val matchedDirectors = meta.director
            .mapNotNull { discoveryConfig.directors[it] }
            .distinct()

        val isTv = meta.type.equals("series", ignoreCase = true) || meta.type.equals("tv", ignoreCase = true)
        var isShortFilm = false
        var isMiniSeries = false
        var isBingeReady = false

        if (!isTv) {
            // Must use the same hour/minute-aware parser as the runtime display (e.g. "2h 1m")
            // — naively stripping non-digit chars from a formatted string concatenates the hour
            // and minute digits (e.g. "2h 1m" -> "21"), which previously misclassified ordinary
            // feature-length movies as short films.
            val runtimeInt = meta.runtime?.let(::parseRuntimeMinutes) ?: 0
            isShortFilm = runtimeInt in 1..39
        } else {
            val seasonNumbers = meta.videos
                .mapNotNull { it.season }
                .filter { it > 0 }
                .distinct()
            val episodeCount = meta.videos.count { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
            val isEnded = meta.status.isEndedSeriesStatus()
            val hasLimitedSignal = keywordNames.any { keyword ->
                keyword in LIMITED_SERIES_KEYWORDS ||
                    keyword.contains("limited-series") ||
                    keyword.contains("mini-series") ||
                    keyword.contains("miniseries")
            } || meta.genres.any { genre ->
                val normalizedGenre = genre.trim().lowercase()
                normalizedGenre == "miniseries" ||
                    normalizedGenre == "mini-series" ||
                    normalizedGenre == "limited series"
            }

            isMiniSeries = hasLimitedSignal ||
                (isEnded && seasonNumbers.size == 1 && episodeCount in 1..12)
            isBingeReady = !isMiniSeries &&
                isEnded &&
                episodeCount in BINGE_READY_EPISODE_RANGE &&
                seasonNumbers.size in 1..BINGE_READY_MAX_SEASONS
        }

        // New release = a movie that became available to watch at home within the window.
        // Driven purely by TMDB's typed digital/physical/TV dates (not year-only releaseInfo,
        // which can't distinguish streaming from cinema), so it never overlaps "in cinema".
        val wantsNewRelease = "new_release" in priority || "digital_release" in priority
        val isNewRelease = wantsNewRelease &&
            isRecentOriginalRelease(meta.releaseInfo) &&
            !isTv && tmdbId != null &&
            isWithinDays(TmdbService.fetchMovieAvailabilityDate(tmdbId), NEW_RELEASE_WINDOW_DAYS)
        val releaseStatus = if (
            "release_status" in priority &&
            !isTv &&
            tmdbId != null
        ) {
            TmdbService.fetchMovieReleaseStatus(
                tmdbId = tmdbId,
                tmdbStatus = meta.status,
            ) ?: normalizeReleaseStatus(meta)
        } else {
            normalizeReleaseStatus(meta)
        }

        val isTrending = "trending" in priority &&
            tmdbId != null &&
            TmdbService.isTrending(tmdbId, meta.type)

        return DiscoveryMeta(
            awardWins = awardWins,
            awardNoms = awardNoms,
            matchedStudios = matchedStudios,
            matchedDirectors = matchedDirectors,
            festivalLabel = festivalLabel,
            isShortFilm = isShortFilm,
            isMiniSeries = isMiniSeries,
            isBingeReady = isBingeReady,
            originalLanguage = meta.language,
            isNewRelease = isNewRelease,
            isCult = isCult,
            isTrueStory = isTrueStory,
            isMetacriticMustSee = isMetacriticMustSee,
            isTrending = isTrending,
            hasMidCreditsScene = hasMidCreditsScene && !isTv,
            hasPostCreditsScene = hasPostCreditsScene && !isTv,
            releaseStatus = releaseStatus
        )
    }

    private fun isWithinDays(dateString: String?, days: Int): Boolean {
        val since = daysSinceIsoDate(dateString) ?: return false
        return since in 0..days
    }

    private fun daysSinceIsoDate(dateString: String?): Int? {
        if (dateString.isNullOrBlank()) return null
        return try {
            val parts = dateString.take(10).split("-")
            if (parts.size != 3) return null
            val epochDay = daysFromCivil(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            val todayDays = com.nuvio.app.features.watchprogress.WatchProgressClock.nowEpochMs() / 86400000L
            (todayDays - epochDay).toInt()
        } catch (e: Exception) {
            null
        }
    }

    // Unix epoch day for a civil date (Howard Hinnant's days_from_civil), consistent with
    // nowEpochMs() / 86400000. The previous formula was offset by ~272 days, so new-release
    // detection never fired even for a title released today.
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = (y - era * 400).toLong()
        val doy = ((153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1).toLong()
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    private fun pickSashMultiple(
        meta: DiscoveryMeta,
        priority: List<String>,
        releaseStatusUnavailableOnly: Boolean,
    ): List<HeroDiscoveryFact> {
        val facts = mutableListOf<HeroDiscoveryFact>()
        for (slot in priority) {
            val label = evaluateSlot(slot, meta, releaseStatusUnavailableOnly)
            if (label != null) {
                val type = SASH_TYPES[slot] ?: "info"
                // Only add if we don't already have a fact with this label
                if (facts.none { it.label == label }) {
                    facts.add(HeroDiscoveryFact(label, type, factCategory(slot, meta, label)))
                }
            }
        }
        return facts
    }

    private fun factCategory(slot: String, meta: DiscoveryMeta, label: String): String =
        when {
            slot == "foreign" -> "foreign:${meta.originalLanguage?.trim()?.lowercase().orEmpty()}"
            label.equals("Best Picture", ignoreCase = true) && slot == "pic_noms" -> "award:best_picture_nom"
            label.equals("Best Picture", ignoreCase = true) -> "award:best_picture"
            label.contains("Golden Globe", ignoreCase = true) && slot.endsWith("_noms") -> "award:globe_nom"
            label.contains("Golden Globe", ignoreCase = true) -> "award:globe_win"
            label.contains("Emmy", ignoreCase = true) && label.contains("Nominee", ignoreCase = true) -> "award:emmy_nom"
            label.contains("Emmy", ignoreCase = true) -> "award:emmy_win"
            label.equals("Palme d'Or", ignoreCase = true) -> "award:palme"
            label.equals("Golden Lion", ignoreCase = true) -> "award:golden_lion"
            label.equals("Golden Bear", ignoreCase = true) -> "award:golden_bear"
            label.equals("People's Choice", ignoreCase = true) -> "award:people_choice"
            slot == "festival" -> "award:festival"
            slot == "release_status" -> "release_status"
            else -> slot
        }

    private fun evaluateSlot(
        slot: String,
        meta: DiscoveryMeta,
        releaseStatusUnavailableOnly: Boolean,
    ): String? {
        return when (slot) {
            "wins" -> meta.awardWins.firstOrNull { it != "Golden Globe" }
            "gg_wins" -> if ("Golden Globe" in meta.awardWins) "Golden Globe" else null
            "pic_noms" -> meta.awardNoms.firstOrNull { "Best Picture" in it }
            "emmy_noms" -> meta.awardNoms.firstOrNull { "Emmy" in it }
            "gg_noms" -> if ("Golden Globe" in meta.awardNoms) "Golden Globe" else null
            "noms" -> meta.awardNoms.joinToString(" • ").takeIf { it.isNotBlank() }
            "festival" -> meta.festivalLabel
            "foreign" -> {
                val lang = meta.originalLanguage
                if (lang.isNullOrBlank() || lang.isEnglishLanguage()) null
                else "${lang.languageDisplayName()} Film"
            }
            "studio" -> meta.matchedStudios.firstOrNull()
            "director" -> meta.matchedDirectors.firstOrNull()?.let { "Directed by $it" }
            "trending" -> if (meta.isTrending) "Trending" else null
            "new_release", "digital_release" -> if (meta.isNewRelease) "New" else null
            "metacritic" -> if (meta.isMetacriticMustSee) "Must-See" else null
            "cult" -> if (meta.isCult) "Cult Classic" else null
            "true_story" -> if (meta.isTrueStory) "True Story" else null
            // The two keywords are independent, so all three combinations are real.
            "stinger" -> when {
                meta.hasMidCreditsScene && meta.hasPostCreditsScene -> "Mid & Post-Credits"
                meta.hasMidCreditsScene -> "Mid-Credits Scene"
                meta.hasPostCreditsScene -> "Post-Credits Scene"
                else -> null
            }
            "short_film" -> if (meta.isShortFilm) "Short Film" else null
            "mini_series" -> if (meta.isMiniSeries) "Mini Series" else null
            "binge_ready" -> if (meta.isBingeReady) "Binge Ready" else null
            "release_status" -> meta.releaseStatus
                ?.takeIf { status -> !releaseStatusUnavailableOnly || status in UnavailableReleaseStatuses }
            else -> null
        }
    }

    private fun normalizeReleaseStatus(meta: MetaDetails): String? {
        val rawStatus = meta.status?.trim()?.takeIf(String::isNotBlank) ?: return null
        val status = when (rawStatus.lowercase()) {
            "physical", "bluray", "blu-ray", "dvd" -> "Physical"
            "streaming", "digital", "released", "ended" -> "Streaming"
            "in cinemas", "cinema", "theatrical" -> "Cinema"
            "returning series", "airing" -> null
            "in production", "post production", "post-production", "planned", "pilot", "rumored" -> "Production"
            else -> rawStatus
        }

        if (status in UnavailableReleaseStatuses && releaseYear(meta.releaseInfo) != null) {
            val year = releaseYear(meta.releaseInfo) ?: return status
            val currentYear = com.nuvio.app.features.watchprogress.CurrentDateProvider.todayIsoDate()
                .take(4)
                .toIntOrNull()
                ?: return status
            if (currentYear - year > RELEASE_STATUS_STALE_YEAR_GAP) return null
        }
        return status.takeIf { it in DisplayReleaseStatuses }
    }

    private fun releaseYear(value: String?): Int? =
        value?.take(4)?.toIntOrNull()

    /**
     * A new regional streaming/reissue record must not turn an old catalogue title into a new
     * release. Allow the current and previous release years so late digital windows still qualify.
     */
    internal fun isRecentOriginalRelease(
        releaseInfo: String?,
        todayIsoDate: String = com.nuvio.app.features.watchprogress.CurrentDateProvider.todayIsoDate(),
    ): Boolean {
        val releaseYear = releaseYear(releaseInfo) ?: return false
        val currentYear = todayIsoDate.take(4).toIntOrNull() ?: return false
        return releaseYear in (currentYear - NEW_RELEASE_ORIGINAL_YEAR_LOOKBACK)..currentYear
    }

    private fun String.isEnglishLanguage(): Boolean {
        val normalized = trim().lowercase()
        return normalized in setOf("en", "eng", "english")
    }

    private fun String?.isEndedSeriesStatus(): Boolean {
        val normalized = this?.trim()?.lowercase() ?: return false
        return normalized in setOf(
            "ended",
            "canceled",
            "cancelled",
            "finished",
            "complete",
            "completed",
        )
    }

    private fun String.extractTmdbId(): Int? =
        removePrefix("tmdb:")
            .substringBefore(':')
            .toIntOrNull()

    private fun String.languageDisplayName(): String {
        val normalized = trim().lowercase()
        return LANGUAGE_LABELS[normalized] ?: takeIf { it.length > 2 }?.replaceFirstChar(Char::uppercase) ?: "Foreign Language"
    }

    private fun List<MetaExternalRating>.isMetacriticMustSee(): Boolean =
        any { rating ->
            rating.source == MdbListMetadataService.PROVIDER_METACRITIC && rating.value >= METACRITIC_MUST_SEE_MIN_SCORE
        }

    private fun applyAwardsText(
        awards: String?,
        awardWins: MutableList<String>,
        awardNoms: MutableList<String>,
    ) {
        val normalized = awards?.lowercase().orEmpty()
        if (normalized.isBlank()) return

        fun addDistinct(target: MutableList<String>, label: String) {
            if (target.none { it.equals(label, ignoreCase = true) }) target += label
        }

        val goldenGlobeWin = Regex("""won\s+\d+\s+golden\s+globes?""").containsMatchIn(normalized)
        val goldenGlobeNom = Regex("""nominated\s+for\s+\d+\s+golden\s+globes?""").containsMatchIn(normalized)
        val emmyWin = Regex("""won\s+\d+\s+(primetime\s+)?emmys?""").containsMatchIn(normalized)
        val emmyNom = Regex("""nominated\s+for\s+\d+\s+(primetime\s+)?emmys?""").containsMatchIn(normalized)

        if (goldenGlobeWin) addDistinct(awardWins, "Golden Globe") else if (goldenGlobeNom) addDistinct(awardNoms, "Golden Globe")
        if (emmyWin) addDistinct(awardWins, "Emmy Winner") else if (emmyNom) addDistinct(awardNoms, "Emmy Nominee")
    }



    private val SASH_TYPES = mapOf(
        "wins" to "win",
        "gg_wins" to "win",
        "pic_noms" to "nom",
        "gg_noms" to "nom",
        "festival" to "win",
        "studio" to "prestige",
        "director" to "prestige",
        "trending" to "trending",
        "cult" to "trending",
        "foreign" to "info",
        "new_release" to "alert",
        "metacritic" to "nom",
        "true_story" to "info",
        "stinger" to "info",
        "short_film" to "info",
        "mini_series" to "info",
        "binge_ready" to "info",
        "release_status" to "alert"
    )

    private val DisplayReleaseStatuses = setOf("Physical", "Streaming", "Cinema", "Production")
    private val UnavailableReleaseStatuses = setOf("Cinema", "Production")
    private const val RELEASE_STATUS_STALE_YEAR_GAP = 3
    private const val NEW_RELEASE_WINDOW_DAYS = 30
    private const val NEW_RELEASE_ORIGINAL_YEAR_LOOKBACK = 1
    private const val MDBLIST_DISCOVERY_TIMEOUT_MS = 6_000L
    private const val METACRITIC_MUST_SEE_MIN_SCORE = 81.0
    private const val BINGE_READY_MAX_SEASONS = 3
    private val BINGE_READY_EPISODE_RANGE = 2..30
    private val LIMITED_SERIES_KEYWORDS = setOf(
        "limited-series",
        "mini-series",
        "miniseries",
        "tv-mini-series",
        "tv-miniseries",
    )

    private val LANGUAGE_LABELS = mapOf(
        "fr" to "French",
        "fre" to "French",
        "fra" to "French",
        "de" to "German",
        "ger" to "German",
        "deu" to "German",
        "es" to "Spanish",
        "spa" to "Spanish",
        "it" to "Italian",
        "ita" to "Italian",
        "pt" to "Portuguese",
        "por" to "Portuguese",
        "ja" to "Japanese",
        "jpn" to "Japanese",
        "ko" to "Korean",
        "kor" to "Korean",
        "zh" to "Chinese",
        "zho" to "Chinese",
        "chi" to "Chinese",
        "da" to "Danish",
        "dan" to "Danish",
        "sv" to "Swedish",
        "swe" to "Swedish",
        "no" to "Norwegian",
        "nor" to "Norwegian",
        "fi" to "Finnish",
        "fin" to "Finnish",
        "nl" to "Dutch",
        "dut" to "Dutch",
        "nld" to "Dutch",
        "pl" to "Polish",
        "pol" to "Polish",
        "ru" to "Russian",
        "rus" to "Russian",
        "tr" to "Turkish",
        "tur" to "Turkish",
        "ar" to "Arabic",
        "ara" to "Arabic",
        "hi" to "Hindi",
        "hin" to "Hindi",
        "fa" to "Persian",
        "per" to "Persian",
        "fas" to "Persian",
        "ro" to "Romanian",
        "rum" to "Romanian",
        "ron" to "Romanian",
        "hu" to "Hungarian",
        "hun" to "Hungarian",
        "cs" to "Czech",
        "cze" to "Czech",
        "ces" to "Czech",
        "he" to "Hebrew",
        "heb" to "Hebrew",
        "el" to "Greek",
        "gre" to "Greek",
        "ell" to "Greek",
    )
    private val FESTIVAL_KEYWORDS = mapOf(
        "festival-cannes-winner" to "Palme d'Or",
        "festival-venice-winner" to "Golden Lion",
        "festival-berlin-winner" to "Golden Bear",
        "festival-toronto-winner" to "People's Choice",
        "festival-sundance-winner" to "Sundance Grand Jury",
        "festival-busan-winner" to "New Currents",
        "festival-locarno-winner" to "Golden Leopard",
        "festival-rotterdam-winner" to "Tiger Award",
        "festival-sxsw-winner" to "SXSW Jury",
        "festival-tribeca-winner" to "Tribeca Audience Award"
    )
}

data class DiscoveryMeta(
    val awardWins: List<String>,
    val awardNoms: List<String>,
    val matchedStudios: List<String>,
    val matchedDirectors: List<String>,
    val festivalLabel: String?,
    val isShortFilm: Boolean,
    val isMiniSeries: Boolean,
    val isBingeReady: Boolean,
    val originalLanguage: String?,
    val isNewRelease: Boolean,
    val isCult: Boolean,
    val isTrueStory: Boolean,
    val isMetacriticMustSee: Boolean,
    val isTrending: Boolean = false,
    val hasMidCreditsScene: Boolean = false,
    val hasPostCreditsScene: Boolean = false,
    val releaseStatus: String?
)

data class HeroDiscoveryFact(
    val label: String,
    val type: String,
    val category: String = type
)
