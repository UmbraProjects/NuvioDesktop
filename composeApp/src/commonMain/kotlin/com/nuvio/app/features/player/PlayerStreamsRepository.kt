package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.streams.StreamScoreContexts
import com.nuvio.app.features.streams.StreamScoreRepository
import com.nuvio.app.features.streams.StreamScoring
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamBadgePresentation
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.filterForRequestedEpisode
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLoadCompletion
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.StreamPrefetchCache
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.reStampedFor
import com.nuvio.app.features.streams.runCatchingUnlessCancelled
import com.nuvio.app.features.streams.sortedForGroupedDisplay
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.toEmptyStateReason
import com.nuvio.app.features.streams.toPluginProviderGroups
import com.nuvio.app.features.streams.toStreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlin.time.TimeSource

/**
 * Dedicated stream fetcher for use inside the player (sources & episodes panels).
 * Uses its own state so it doesn't interfere with the main [StreamsRepository].
 */
object PlayerStreamsRepository {
    private val log = Logger.withTag("PlayerStreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // source panel
    private val _sourceState = MutableStateFlow(StreamsUiState())
    val sourceState: StateFlow<StreamsUiState> = _sourceState.asStateFlow()
    private var sourceJob: Job? = null
    private var sourceRequestKey: String? = null

    // episode streams panel
    private val _episodeStreamsState = MutableStateFlow(StreamsUiState())
    val episodeStreamsState: StateFlow<StreamsUiState> = _episodeStreamsState.asStateFlow()
    private var episodeStreamsJob: Job? = null
    private var episodeStreamsRequestKey: String? = null

    fun loadSources(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false,
    ) {
        fetchStreams(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            forceRefresh = forceRefresh,
            stateFlow = _sourceState,
            requestKeyHolder = { sourceRequestKey },
            setRequestKey = { sourceRequestKey = it },
            jobHolder = { sourceJob },
            setJob = { sourceJob = it },
        )
    }

    fun loadEpisodeStreams(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        sourceAffinity: PlayerSourceAffinity = PlayerSourceAffinity.Stream,
        forceRefresh: Boolean = false,
    ) {
        fetchStreams(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            sourceAffinity = sourceAffinity,
            forceRefresh = forceRefresh,
            stateFlow = _episodeStreamsState,
            requestKeyHolder = { episodeStreamsRequestKey },
            setRequestKey = { episodeStreamsRequestKey = it },
            jobHolder = { episodeStreamsJob },
            setJob = { episodeStreamsJob = it },
        )
    }

    fun selectSourceFilter(addonId: String?) {
        _sourceState.update { it.copy(selectedFilter = addonId) }
    }

    fun selectEpisodeStreamsFilter(addonId: String?) {
        _episodeStreamsState.update { it.copy(selectedFilter = addonId) }
    }

    fun clearEpisodeStreams() {
        episodeStreamsJob?.cancel()
        episodeStreamsRequestKey = null
        _episodeStreamsState.value = StreamsUiState()
    }

    fun clearAll() {
        sourceJob?.cancel()
        sourceRequestKey = null
        _sourceState.value = StreamsUiState()
        clearEpisodeStreams()
    }

    private fun fetchStreams(
        type: String,
        videoId: String,
        parentMetaId: String?,
        title: String?,
        season: Int?,
        episode: Int?,
        sourceAffinity: PlayerSourceAffinity = PlayerSourceAffinity.Stream,
        forceRefresh: Boolean,
        stateFlow: MutableStateFlow<StreamsUiState>,
        requestKeyHolder: () -> String?,
        setRequestKey: (String?) -> Unit,
        jobHolder: () -> Job?,
        setJob: (Job) -> Unit,
    ) {
        val resolvedEpisode = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = type,
            parentMetaId = parentMetaId ?: videoId,
            videoId = videoId,
            title = title,
            season = season,
            episode = episode,
            isAnimeHint = type.equals("anime", ignoreCase = true),
        )
        val effectiveVideoId = resolvedEpisode.videoId
        val effectiveSeason = resolvedEpisode.streamSeason
        val effectiveEpisode = resolvedEpisode.streamEpisode
        val pluginUiState = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.uiState.value
        } else {
            PluginsUiState(pluginsEnabled = false)
        }
        val requestKey = "$type::$effectiveVideoId::$effectiveSeason::$effectiveEpisode::affinity=$sourceAffinity::pluginsGrouped=${pluginUiState.groupStreamsByRepository}"
        val current = stateFlow.value
        if (
            !forceRefresh &&
            requestKeyHolder() == requestKey &&
            (current.groups.isNotEmpty() || current.emptyStateReason != null || current.isAnyLoading)
        ) {
            return
        }

        setRequestKey(requestKey)
        jobHolder()?.cancel()
        stateFlow.value = StreamsUiState()

        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        // Same key shape and same forced-refresh rule as StreamsRepository, so a background search
        // serves both. forceRefresh must keep bypassing this: next-episode auto-play forces a
        // refresh on purpose, having once stalled on a reused terminal-empty result.
        val prefetchContentKey = StreamPrefetchCache.contentKey(
            type = type,
            videoId = effectiveVideoId,
            season = effectiveSeason,
            episode = effectiveEpisode,
        )

        fun singleGroupState(group: AddonStreamGroup): StreamsUiState {
            val presentedGroup = StreamBadgePresentation.apply(
                groups = listOf(group),
                rules = streamBadgeRules,
            ).firstOrNull() ?: group
            return StreamsUiState(
                groups = listOf(presentedGroup),
                activeAddonIds = setOf(group.addonId),
                isAnyLoading = false,
            )
        }

        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(effectiveVideoId)
        if (embeddedStreams.isNotEmpty()) {
            log.d { "Using ${embeddedStreams.size} embedded streams for type=$type id=$effectiveVideoId" }
            stateFlow.value = singleGroupState(
                AddonStreamGroup(
                    addonName = embeddedStreams.first().addonName,
                    addonId = "embedded",
                    streams = embeddedStreams,
                    isLoading = false,
                ),
            )
            return
        }

        // Local-library files for this episode. The lookup is video-id-keyed (safe against a
        // stale details meta), and we try the raw id too — the resolver may have converted an
        // anime id into a form the mapping knows but the local item does not, or vice versa.
        val localStreams = LocalLibraryRepository.localStreamsFor(parentMetaId ?: videoId, effectiveVideoId)
            .ifEmpty {
                if (videoId != effectiveVideoId) {
                    LocalLibraryRepository.localStreamsFor(parentMetaId ?: videoId, videoId)
                } else {
                    emptyList()
                }
            }
        val localGroup = localStreams.takeIf { it.isNotEmpty() }?.let { streams ->
            AddonStreamGroup(
                addonName = streams.first().addonName,
                addonId = "locallibrary",
                streams = streams,
                isLoading = false,
            )
        }
        // A local-library playback session (opened via the library) stays local across episode
        // switches and next-episode auto-play — mirror StreamsRepository's local-first behaviour
        // instead of scraping addons for a file that is already on disk.
        if (localGroup != null && sourceAffinity == PlayerSourceAffinity.Local) {
            log.d { "Using ${localGroup.streams.size} local file(s) for type=$type id=$effectiveVideoId" }
            stateFlow.value = singleGroupState(localGroup)
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val prefetchMaxAgeMs = if (forceRefresh) {
            0L
        } else {
            playerSettings.streamPrefetchCacheMinutes * 60L * 1000L
        }
        val debridSettings = DebridSettingsRepository.snapshot()
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.getEnabledScrapersForType(type)
        } else {
            emptyList()
        }
        val pluginProviderGroups = pluginScrapers.toPluginProviderGroups(
            repositories = pluginUiState.repositories,
            groupByRepository = pluginUiState.groupStreamsByRepository,
        )

        if (installedAddons.isEmpty() && pluginProviderGroups.isEmpty()) {
            // No scrapers at all — a local file is still a valid (and the only) source.
            if (localGroup != null) {
                stateFlow.value = singleGroupState(localGroup)
            } else {
                stateFlow.value = StreamsUiState(
                    isAnyLoading = false,
                    emptyStateReason = com.nuvio.app.features.streams.StreamsEmptyStateReason.NoAddonsInstalled,
                )
            }
            return
        }

        val streamAddons = installedAddons
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                val supportsRequestedStream = manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() ||
                            resource.idPrefixes.any { effectiveVideoId.startsWith(it) })
                }
                if (!supportsRequestedStream) return@mapNotNull null

                InstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

        if (streamAddons.isEmpty() && pluginProviderGroups.isEmpty()) {
            if (localGroup != null) {
                stateFlow.value = singleGroupState(localGroup)
            } else {
                stateFlow.value = StreamsUiState(
                    isAnyLoading = false,
                    emptyStateReason = com.nuvio.app.features.streams.StreamsEmptyStateReason.NoCompatibleAddons,
                )
            }
            return
        }

        val installedAddonOrder = streamAddons.map { it.addonName }
        // The local file rides along as an already-loaded group next to the addon results, so
        // episode switching still offers it even when scrapers are also in play.
        val initialGroups = listOfNotNull(localGroup) + StreamAutoPlaySelector.orderAddonStreams(streamAddons.map { addon ->
            AddonStreamGroup(
                addonName = addon.addonName,
                addonId = addon.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + pluginProviderGroups.map { providerGroup ->
            AddonStreamGroup(
                addonName = providerGroup.addonName,
                addonId = providerGroup.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        }, installedAddonOrder)
        val isInitiallyLoading = initialGroups.any { it.isLoading }
        stateFlow.value = StreamsUiState(
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
        )

        val job = scope.launch {
            val fetchStarted = TimeSource.Monotonic.markNow()
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val installedAddonNames = installedAddonOrder.toSet()
            val pluginRemainingByAddonId = pluginProviderGroups
                .associate { it.addonId to it.scrapers.size }
                .toMutableMap()
            val pluginFirstErrorByAddonId = mutableMapOf<String, String>()
            val totalTasks = streamAddons.size + pluginProviderGroups.sumOf { it.scrapers.size }
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.BUFFERED)
            val debridAvailabilityJobs = mutableListOf<Job>()

            log.i {
                "Fetch started type=$type id=$effectiveVideoId season=$effectiveSeason episode=$effectiveEpisode " +
                    "affinity=$sourceAffinity addons=${streamAddons.size} " +
                    "pluginScrapers=${pluginProviderGroups.sumOf { it.scrapers.size }}"
            }

            fun publishCompletion(completion: StreamLoadCompletion) {
                if (completions.trySend(completion).isFailure) {
                    log.d { "Ignoring late player stream load completion after channel close" }
                }
            }

            fun presentStreamGroup(group: AddonStreamGroup): AddonStreamGroup {
                val badgeGroup = StreamBadgePresentation.apply(
                    groups = listOf(group),
                    rules = streamBadgeRules,
                ).firstOrNull() ?: group
                return DebridStreamPresentation.apply(
                    groups = listOf(badgeGroup),
                    settings = debridSettings,
                    // The context must match the content: scoring an episode with a movie context
                    // measures it against the movie size band and buries every normal episode.
                    scoring = StreamScoring(
                        profile = StreamScoreRepository.profile,
                        context = StreamScoreContexts.forPlayback(
                            isEpisode = effectiveEpisode != null,
                            contentId = parentMetaId ?: videoId,
                            contentType = type,
                        ),
                    ),
                ).firstOrNull() ?: badgeGroup
            }

            fun publishStreamGroup(group: AddonStreamGroup) {
                stateFlow.update { current ->
                    val updated = StreamAutoPlaySelector.orderAddonStreams(
                        groups = current.groups.map { currentGroup ->
                            if (currentGroup.addonId == group.addonId) group else currentGroup
                        },
                        installedOrder = installedAddonOrder,
                    )
                    val anyLoading = updated.any { it.isLoading }
                    current.copy(
                        groups = updated,
                        isAnyLoading = anyLoading,
                        emptyStateReason = updated.toEmptyStateReason(anyLoading),
                    )
                }
            }

            fun publishStreamGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishStreamGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)
                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishStreamGroup(presentStreamGroup(group))
                    return
                }

                val checkingGroup = LocalDebridAvailabilityService.markChecking(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                ).firstOrNull() ?: group

                val availabilityJob = launch {
                    val availabilityGroup = LocalDebridAvailabilityService.annotateCachedAvailability(
                        groups = listOf(checkingGroup),
                        eligibleGroupIds = eligibleGroupIds,
                    ).firstOrNull() ?: checkingGroup
                    publishStreamGroup(presentStreamGroup(availabilityGroup))
                }
                debridAvailabilityJobs += availabilityJob
            }

            streamAddons.forEach { addon ->
                launch {
                    val prefetched = (
                        StreamPrefetchCache
                            .get(prefetchContentKey, addon.addonId, prefetchMaxAgeMs)
                            ?.streams
                            ?: StreamPrefetchCache
                                .awaitInFlight(prefetchContentKey, addon.addonId, prefetchMaxAgeMs)
                        )?.reStampedFor(addon.addonName, addon.addonId)
                    if (prefetched != null) {
                        log.i {
                            "Provider served from prefetch addon=${addon.addonName} " +
                                "id=${addon.addonId} streams=${prefetched.size}"
                        }
                        publishCompletion(
                            StreamLoadCompletion.Addon(
                                AddonStreamGroup(addon.addonName, addon.addonId, prefetched, isLoading = false),
                            ),
                        )
                        return@launch
                    }

                    val providerStarted = TimeSource.Monotonic.markNow()
                    log.i {
                        "Provider started addon=${addon.addonName} id=${addon.addonId} " +
                            "type=$type contentId=$effectiveVideoId"
                    }
                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = effectiveVideoId,
                    )

                    val displayName = addon.addonName
                    val group = runCatchingUnlessCancelled {
                        val payload = httpGetText(url)
                        StreamParser.parse(
                            payload = payload,
                            addonName = displayName,
                            addonId = addon.addonId,
                            addonLogo = addon.manifest.logoUrl,
                        ).filterForRequestedEpisode(
                            season = effectiveSeason,
                            episode = effectiveEpisode,
                            episodeTitlesByCoordinate = MetaDetailsRepository.episodeTitlesByCoordinate(
                                type = type,
                                id = parentMetaId ?: videoId,
                            ),
                            seriesTitle = MetaDetailsRepository.seriesTitleFor(
                                type = type,
                                id = parentMetaId ?: videoId,
                            ),
                        )
                    }.fold(
                        onSuccess = { streams ->
                            log.i {
                                "Provider completed addon=${addon.addonName} id=${addon.addonId} " +
                                    "streams=${streams.size} elapsedMs=${providerStarted.elapsedNow().inWholeMilliseconds}"
                            }
                            AddonStreamGroup(displayName, addon.addonId, streams, isLoading = false)
                        },
                        onFailure = { err ->
                            log.w(err) {
                                "Provider failed addon=$displayName id=${addon.addonId} " +
                                    "elapsedMs=${providerStarted.elapsedNow().inWholeMilliseconds}"
                            }
                            AddonStreamGroup(displayName, addon.addonId, emptyList(), isLoading = false, error = err.message)
                        },
                    )
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }

            pluginProviderGroups.forEach { providerGroup ->
                val includeScraperNameInSubtitle = false
                providerGroup.scrapers.forEach { scraper ->
                    launch {
                        val scraperProviderId = StreamPrefetchCache.scraperProviderId(scraper.id)
                        val prefetched = (
                            StreamPrefetchCache
                                .get(prefetchContentKey, scraperProviderId, prefetchMaxAgeMs)
                                ?.streams
                                ?: StreamPrefetchCache
                                    .awaitInFlight(prefetchContentKey, scraperProviderId, prefetchMaxAgeMs)
                            )?.reStampedFor(providerGroup.addonName, providerGroup.addonId)
                        if (prefetched != null) {
                            log.i {
                                "Plugin provider served from prefetch group=${providerGroup.addonName} " +
                                    "scraper=${scraper.name} streams=${prefetched.size}"
                            }
                            publishCompletion(
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = prefetched,
                                    error = null,
                                ),
                            )
                            return@launch
                        }

                        val providerStarted = TimeSource.Monotonic.markNow()
                        log.i {
                            "Plugin provider started group=${providerGroup.addonName} " +
                                "scraper=${scraper.name} type=$type contentId=$effectiveVideoId"
                        }
                        val completion = PluginRepository.executeScraper(
                            scraper = scraper,
                            tmdbId = pluginContentId(
                                videoId = effectiveVideoId,
                                season = effectiveSeason,
                                episode = effectiveEpisode,
                            ),
                            mediaType = type,
                            season = effectiveSeason,
                            episode = effectiveEpisode,
                        ).fold(
                            onSuccess = { results ->
                                log.i {
                                    "Plugin provider completed group=${providerGroup.addonName} " +
                                        "scraper=${scraper.name} streams=${results.size} " +
                                        "elapsedMs=${providerStarted.elapsedNow().inWholeMilliseconds}"
                                }
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = results.map { result ->
                                        result.toStreamItem(
                                            scraper = scraper,
                                            addonName = providerGroup.addonName,
                                            addonId = providerGroup.addonId,
                                            includeScraperNameInSubtitle = includeScraperNameInSubtitle,
                                        )
                                    },
                                    error = null,
                                )
                            },
                            onFailure = { error ->
                                log.w(error) {
                                    "Plugin provider failed group=${providerGroup.addonName} " +
                                        "scraper=${scraper.name} " +
                                        "elapsedMs=${providerStarted.elapsedNow().inWholeMilliseconds}"
                                }
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = emptyList(),
                                    error = error.message ?: getString(Res.string.streams_failed_to_load_scraper, scraper.name),
                                )
                            },
                        )
                        publishCompletion(completion)
                    }
                }
            }

            repeat(totalTasks) {
                when (val completion = completions.receive()) {
                    is StreamLoadCompletion.Addon -> {
                        publishStreamGroupAfterCacheCheck(completion.group)
                    }

                    is StreamLoadCompletion.PluginScraper -> {
                        val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                        pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                        if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                            pluginFirstErrorByAddonId[completion.addonId] = completion.error
                        }

                        stateFlow.update { current ->
                            val updated = StreamAutoPlaySelector.orderAddonStreams(
                                groups = current.groups.map { group ->
                                    if (group.addonId != completion.addonId) {
                                        group
                                    } else {
                                        val mergedStreams = if (completion.streams.isEmpty()) {
                                            group.streams
                                        } else {
                                            (group.streams + completion.streams).sortedForGroupedDisplay()
                                        }
                                        val stillLoading = remaining > 0
                                        val finalError = if (mergedStreams.isEmpty() && !stillLoading) {
                                            pluginFirstErrorByAddonId[completion.addonId]
                                        } else {
                                            null
                                        }
                                        group.copy(
                                            streams = mergedStreams,
                                            isLoading = stillLoading,
                                            error = finalError,
                                        )
                                    }
                                },
                                installedOrder = installedAddonOrder,
                            )
                            val anyLoading = updated.any { it.isLoading }
                            current.copy(
                                groups = updated,
                                isAnyLoading = anyLoading,
                                emptyStateReason = updated.toEmptyStateReason(anyLoading),
                            )
                        }
                    }
                }
            }

            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()
            }
            val completedState = stateFlow.value
            log.i {
                "Fetch completed type=$type id=$effectiveVideoId season=$effectiveSeason episode=$effectiveEpisode " +
                    "providers=${completedState.groups.size} streams=${completedState.groups.sumOf { it.streams.size }} " +
                    "errors=${completedState.groups.count { !it.error.isNullOrBlank() }} " +
                    "elapsedMs=${fetchStarted.elapsedNow().inWholeMilliseconds}"
            }
            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = stateFlow.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = effectiveSeason,
                    episode = effectiveEpisode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                    contentId = parentMetaId ?: videoId,
                    contentType = type,
                ) { original, prepared ->
                    stateFlow.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }
                }
            }
            completions.close()
        }
        setJob(job)
    }
}
