package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerSettingsRepository

object SkipIntroRepository {

    private val cache = HashMap<String, List<SkipInterval>>()
    private val imdbEntriesCache = HashMap<String, List<ArmEntry>>()
    private val animeIdsCache = HashMap<String, AnimeIds>()
    private val animeSkipShowIdCache = HashMap<String, String>()
    private const val NO_ID = "__none__"

    private val introDbConfigured: Boolean
        get() = IntroDbConfig.URL.isNotBlank()

    suspend fun getSkipIntervals(
        imdbId: String,
        season: Int,
        episode: Int,
        durationSeconds: Long? = null,
    ): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        if (!settings.skipIntroEnabled) return emptyList()

        val cacheKey = "$imdbId:$season:$episode:${durationSeconds ?: 0L}"
        cache[cacheKey]?.let { return it }

        val skipDbResult = fetchFromSkipDb(imdbId, season, episode, durationSeconds)
        if (skipDbResult.isNotEmpty()) return skipDbResult.also { cache[cacheKey] = it }

        if (introDbConfigured) {
            val result = fetchFromIntroDb(imdbId, season, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        val entries = resolveImdbEntries(imdbId)
        val malId = entries.getOrNull(season - 1)?.myanimelist?.toString()
            ?: entries.firstOrNull()?.myanimelist?.toString()
        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        val seasonAnilistId = entries.getOrNull(season - 1)?.anilist?.toString()
        val fallbackAnilistId = entries.firstOrNull()?.anilist?.toString()
        for ((anilistId, seasonFilter) in listOfNotNull(
            seasonAnilistId?.let { it to null },
            if (fallbackAnilistId != null && fallbackAnilistId != seasonAnilistId) fallbackAnilistId to season else null
        )) {
            val result = fetchFromAnimeSkip(anilistId, episode, season = seasonFilter)
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    /**
     * Skip intervals for a film.
     *
     * SkipDB is the only source consulted: IntroDB, AniSkip and Anime-Skip are all keyed by episode
     * and have nothing to say about a film. What SkipDB holds for films today is opening title
     * sequences and end credits.
     */
    suspend fun getMovieSkipIntervals(
        imdbId: String,
        durationSeconds: Long? = null,
    ): List<SkipInterval> {
        if (!PlayerSettingsRepository.uiState.value.skipIntroEnabled) return emptyList()

        val cacheKey = "$imdbId:movie:${durationSeconds ?: 0L}"
        cache[cacheKey]?.let { return it }

        return fetchFromSkipDb(imdbId, season = null, episode = null, durationSeconds = durationSeconds)
            .also { cache[cacheKey] = it }
    }

    /**
     * Skip intervals for a playback addressed by an anime-list entry (`kitsu:`, `mal:`, `anilist:`,
     * `anidb:`) rather than an IMDb id, with [episode] the entry-local absolute episode number.
     *
     * All four namespaces share one chain because they differ only in how the entry is named:
     * ARM turns any of them into the same set of ids, and it is that set, not the namespace the
     * user happened to arrive with, that decides which providers can answer.
     */
    internal suspend fun getSkipIntervalsForAnime(
        namespace: AnimeIdNamespace,
        id: String,
        episode: Int,
        durationSeconds: Long? = null,
    ): List<SkipInterval> {
        if (!PlayerSettingsRepository.uiState.value.skipIntroEnabled) return emptyList()

        val cacheKey = "${namespace.armSource}:$id:$episode:${durationSeconds ?: 0L}"
        cache[cacheKey]?.let { return it }

        val ids = resolveAnimeIds(namespace, id)
        val imdbId = ids.imdb
        val placement = if (imdbId != null) resolveAnimeSeason(imdbId, ids) else null

        // SkipDB and IntroDB are keyed on an IMDb season and episode, so reaching them means
        // translating the entry into that numbering.
        val imdbChain: suspend () -> List<SkipInterval> = chain@{
            if (imdbId == null || placement == null) return@chain emptyList()
            val skipDbResult = fetchFromSkipDb(imdbId, placement.season, episode, durationSeconds)
            if (skipDbResult.isNotEmpty()) return@chain skipDbResult
            if (introDbConfigured) {
                val introDbResult = fetchFromIntroDb(imdbId, placement.season, episode)
                if (introDbResult.isNotEmpty()) return@chain introDbResult
            }
            emptyList()
        }

        // AniSkip and Anime-Skip are keyed on the anime-list entry and its own absolute episode —
        // exactly what the id already carries, with nothing to translate.
        val animeChain: suspend () -> List<SkipInterval> = chain@{
            ids.myanimelist?.let { malId ->
                val result = fetchFromAniSkip(malId, episode)
                if (result.isNotEmpty()) return@chain result
            }
            ids.anilist?.let { anilistId ->
                val result = fetchFromAnimeSkip(anilistId, episode, season = null)
                if (result.isNotEmpty()) return@chain result
            }
            emptyList()
        }

        // Which chain leads turns on whether the entry's episode numbering can disagree with the
        // IMDb season's. A title ARM maps to a single entry numbers the same episodes as the IMDb
        // season does, so there is no translation to get wrong and SkipDB leads — it is the only
        // source that matches timings against the runtime of the cut being played, which is what
        // tells two releases of an episode apart. Across a multi-entry franchise the two can
        // disagree (an entry restarting at 1 while IMDb keeps counting, or the reverse), so the
        // translation is unsafe there and the chain keyed on the id we already hold leads instead.
        val ordered = if (placement?.sharesEpisodeNumbering == true) {
            listOf(imdbChain, animeChain)
        } else {
            listOf(animeChain, imdbChain)
        }
        for (chain in ordered) {
            val result = chain()
            if (result.isNotEmpty()) return result.also { cache[cacheKey] = it }
        }

        return emptyList<SkipInterval>().also { cache[cacheKey] = it }
    }

    /**
     * Skip intervals for an anime film addressed by an anime-list entry. Only SkipDB holds
     * anything for a film and it is keyed on IMDb, so this is a lookup of that id followed by the
     * ordinary film path.
     */
    internal suspend fun getMovieSkipIntervalsForAnime(
        namespace: AnimeIdNamespace,
        id: String,
        durationSeconds: Long? = null,
    ): List<SkipInterval> {
        if (!PlayerSettingsRepository.uiState.value.skipIntroEnabled) return emptyList()
        val imdbId = resolveAnimeIds(namespace, id).imdb ?: return emptyList()
        return getMovieSkipIntervals(imdbId, durationSeconds)
    }

    /** Every id ARM holds for one anime-list entry, as the strings the providers are keyed on. */
    private data class AnimeIds(
        val myanimelist: String? = null,
        val anilist: String? = null,
        val kitsu: String? = null,
        val imdb: String? = null,
    )

    private suspend fun resolveAnimeIds(namespace: AnimeIdNamespace, id: String): AnimeIds {
        val cacheKey = "${namespace.armSource}:$id"
        animeIdsCache[cacheKey]?.let { return it }

        val entry = try {
            SkipIntroApi.resolveAnimeEntry(namespace.armSource, id)
        } catch (_: Exception) {
            null
        }
        // ARM answers with the ids it was asked to map to and need not echo the one it was queried
        // by, so the id already in hand is put back into the set rather than left null.
        val ids = AnimeIds(
            myanimelist = entry?.myanimelist?.toString()
                ?: id.takeIf { namespace == AnimeIdNamespace.MAL },
            anilist = entry?.anilist?.toString()
                ?: id.takeIf { namespace == AnimeIdNamespace.ANILIST },
            kitsu = entry?.kitsu?.toString()
                ?: id.takeIf { namespace == AnimeIdNamespace.KITSU },
            imdb = entry?.imdb?.takeIf { it.isNotBlank() },
        )
        return ids.also { animeIdsCache[cacheKey] = it }
    }

    /**
     * Where an anime-list entry sits within an IMDb title.
     *
     * [sharesEpisodeNumbering] records that the title is a single entry, so its episode numbers and
     * the IMDb season's are the same numbers and may be used interchangeably.
     */
    private data class AnimeSeasonPlacement(val season: Int, val sharesEpisodeNumbering: Boolean)

    /**
     * Which season of [imdbId] an anime entry is, or null when that cannot be established.
     *
     * SkipDB and IntroDB number episodes per IMDb season, so an entry has to be placed within its
     * franchise before either can be asked. ARM lists a title's entries in season order, making an
     * entry's position in that list its season number.
     */
    private suspend fun resolveAnimeSeason(imdbId: String, ids: AnimeIds): AnimeSeasonPlacement? {
        val entries = resolveImdbEntries(imdbId)
        val singleEntry = entries.size <= 1
        val index = entries.indexOfFirst { entry ->
            (ids.myanimelist != null && entry.myanimelist?.toString() == ids.myanimelist) ||
                (ids.anilist != null && entry.anilist?.toString() == ids.anilist) ||
                (ids.kitsu != null && entry.kitsu?.toString() == ids.kitsu)
        }
        if (index >= 0) return AnimeSeasonPlacement(index + 1, sharesEpisodeNumbering = singleEntry)
        // A miss used to fall through as season 0, which no provider holds anything for. A title
        // ARM lists as a single entry is season 1 whatever ids it reports back; anything else is
        // genuinely ambiguous, and asking about a guessed season risks skipping playback against
        // another season's timings — worse than not offering to skip at all.
        return if (singleEntry) AnimeSeasonPlacement(1, sharesEpisodeNumbering = true) else null
    }

    /**
     * SkipDB covers intro, recap, outro and preview at once, so unlike the other sources a single
     * answer can populate several kinds. Pass a null [season]/[episode] for a movie.
     *
     * Answered from the locally held export wherever one exists. Because that export is SkipDB's
     * complete set of approved segments, an episode missing from it is one SkipDB has nothing for,
     * and asking anyway would only confirm the miss a few hundred milliseconds later. The API is
     * used only while no export is held at all — a first run still downloading, or a sync that has
     * never got through — where the choice is between asking and answering nothing.
     */
    private suspend fun fetchFromSkipDb(
        imdbId: String,
        season: Int?,
        episode: Int?,
        durationSeconds: Long?,
    ): List<SkipInterval> {
        return try {
            SkipDbDumpRepository.lookup(imdbId, season, episode, durationSeconds)
                ?.let { segments -> return segments.toSkipIntervals() }

            SkipIntroApi
                .getSkipDbSegments(imdbId, season, episode, durationSeconds)
                ?.segments
                ?.toSkipIntervals()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val data = SkipIntroApi.getIntroDbSegments(imdbId, season, episode) ?: return emptyList()
            val start = data.startSec ?: data.startMs?.let { it / 1000.0 }
            val end = data.endSec ?: data.endMs?.let { it / 1000.0 }
            if (start == null || end == null || end <= start) return emptyList()
            listOf(SkipInterval(startTime = start, endTime = end, type = "intro", provider = "introdb"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val response = SkipIntroApi.getAniSkipTimes(malId, episode)
            if (response == null) return emptyList()
            if (!response.found) return emptyList()
            response.results?.map { result ->
                SkipInterval(
                    startTime = result.interval.startTime,
                    endTime = result.interval.endTime,
                    type = result.skipType,
                    provider = "aniskip",
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        val clientId = settings.animeSkipClientId.trim()
        if (clientId.isBlank()) return emptyList()
        if (!settings.animeSkipEnabled) return emptyList()

        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                val response = SkipIntroApi.queryAnimeSkip(clientId, query) ?: continue
                val episodes = response.data?.findEpisodesByShowId ?: continue

                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits" -> "ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
        val showIds = try {
            SkipIntroApi.queryAnimeSkip(clientId, query)
                ?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    private suspend fun resolveImdbEntries(imdbId: String): List<ArmEntry> {
        imdbEntriesCache[imdbId]?.let { return it }
        return try {
            SkipIntroApi.resolveImdbToAll(imdbId)
        } catch (_: Exception) { emptyList() }.also { imdbEntriesCache[imdbId] = it }
    }

    /**
     * Contributes a segment to SkipDB, and to IntroDB as well when a key for it is configured.
     *
     * SkipDB drives what the user is told: it explains what happened to a submission, whereas
     * IntroDB only answers with a status code. IntroDB is therefore best-effort — a failure there
     * does not turn a published SkipDB submission into an error message.
     *
     * [durationSeconds] is the runtime of the cut the timings were taken from. It is what lets
     * SkipDB serve them back only to matching releases, so it is worth sending whenever known.
     */
    suspend fun submitSegment(
        imdbId: String,
        season: Int?,
        episode: Int?,
        startSec: Double,
        endSec: Double,
        segmentType: String,
        durationSeconds: Long?,
    ): SkipSubmitOutcome {
        val settings = PlayerSettingsRepository.uiState.value
        if (!settings.introSubmitEnabled) {
            return SkipSubmitOutcome(accepted = false, message = "Submitting timestamps is turned off.")
        }

        val startMs = (startSec * 1000).toLong()
        val endMs = (endSec * 1000).toLong()
        val skipDbKey = settings.skipDbApiKey.trim()
        val introDbKey = settings.introDbApiKey.trim()

        if (skipDbKey.isBlank() && introDbKey.isBlank()) {
            return SkipSubmitOutcome(accepted = false, message = "Add a SkipDB key in settings first.")
        }

        val skipDbOutcome = if (skipDbKey.isNotBlank()) {
            submitToSkipDb(skipDbKey, imdbId, season, episode, segmentType, startMs, endMs, durationSeconds)
        } else {
            null
        }

        // IntroDB takes episodes only, and never reports more than whether it accepted.
        val introDbOutcome = if (introDbKey.isNotBlank() && season != null && episode != null) {
            val accepted = runCatching {
                SkipIntroApi.submitIntro(
                    apiKey = introDbKey,
                    request = SubmitIntroRequest(
                        imdbId = imdbId,
                        season = season,
                        episode = episode,
                        startSec = startSec,
                        endSec = endSec,
                        startMs = startMs,
                        endMs = endMs,
                        segmentType = segmentType,
                    ),
                )
            }.getOrDefault(false)
            SkipSubmitOutcome(
                accepted = accepted,
                message = if (accepted) "Submitted to IntroDB." else "IntroDB rejected the submission.",
            )
        } else {
            null
        }

        val outcome = skipDbOutcome
            ?: introDbOutcome
            // Left with an IntroDB key and a movie: IntroDB is episodes-only, and SkipDB, which
            // does take movies, has no key to submit with.
            ?: SkipSubmitOutcome(
                accepted = false,
                message = "Add a SkipDB key in settings to submit timestamps for a movie.",
            )
        return finishSubmit(outcome, imdbId, season, episode)
    }

    private suspend fun submitToSkipDb(
        apiKey: String,
        imdbId: String,
        season: Int?,
        episode: Int?,
        segmentType: String,
        startMs: Long,
        endMs: Long,
        durationSeconds: Long?,
    ): SkipSubmitOutcome {
        val response = SkipIntroApi.submitSkipDbSegment(
            apiKey = apiKey,
            request = SkipDbSubmitRequest(
                imdbId = imdbId,
                season = season,
                episode = episode,
                segmentType = segmentType,
                startMs = startMs,
                endMs = endMs,
                durationMs = durationSeconds?.takeIf { it > 0L }?.let { it * 1000L },
            ),
        ) ?: return SkipSubmitOutcome(accepted = false, message = "Could not reach SkipDB.")

        return response.toOutcome()
    }

    /**
     * Drops the cached lookup for the episode just contributed to, so a rewatch shows the new
     * timings rather than the "nothing here" answer cached before the submission.
     */
    private fun finishSubmit(
        outcome: SkipSubmitOutcome,
        imdbId: String,
        season: Int?,
        episode: Int?,
    ): SkipSubmitOutcome {
        if (outcome.accepted) {
            val prefix = if (season == null || episode == null) "$imdbId:" else "$imdbId:$season:$episode:"
            cache.keys.filter { key -> key.startsWith(prefix) }.toList().forEach(cache::remove)
        }
        return outcome
    }

    /** Mints an account-less SkipDB submission key and stores it. */
    suspend fun createSkipDbAnonymousKey(): Boolean {
        val key = SkipIntroApi.createSkipDbAnonymousKey() ?: return false
        PlayerSettingsRepository.setSkipDbApiKey(key)
        return true
    }

    suspend fun verifyIntroDbApiKey(apiKey: String): Boolean {
        return SkipIntroApi.verifyIntroDbApiKey(apiKey)
    }

    fun clearCache() {
        cache.clear()
        imdbEntriesCache.clear()
        animeIdsCache.clear()
        animeSkipShowIdCache.clear()
    }
}
