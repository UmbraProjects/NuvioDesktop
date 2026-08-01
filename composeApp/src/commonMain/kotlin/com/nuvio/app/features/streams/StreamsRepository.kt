package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.player.PlaybackStartTrace
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.plugins.PluginsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch

object StreamsRepository {
    private const val LOCAL_LIBRARY_GROUP_ID = "local-library"
    private val log = Logger.withTag("StreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(StreamsUiState())
    val uiState: StateFlow<StreamsUiState> = _uiState.asStateFlow()

    /**
     * Whether the streams currently loaded belong to an episode rather than a film.
     *
     * A property of the *request*, not of any one emission, which is why it lives here instead of on
     * [StreamsUiState] — the state is rebuilt from scratch at several points during a load and the
     * flag would have to be re-threaded through every one of them.
     *
     * Anything that scores these streams outside the load itself needs it: size thresholds are
     * per-content-type, and judging an episode against the movie band marks every normal episode as
     * "far from preferred size".
     */
    private val _isEpisodeRequest = MutableStateFlow(false)
    val isEpisodeRequest: StateFlow<Boolean> = _isEpisodeRequest.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null

    fun requestToken(
        type: String,
        videoId: String,
        parentMetaId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
    ): String {
        val resolvedEpisode = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = type,
            parentMetaId = parentMetaId ?: videoId,
            videoId = videoId,
            title = title,
            season = season,
            episode = episode,
            isAnimeHint = type.equals("anime", ignoreCase = true),
        )
        return "$type::${resolvedEpisode.videoId}::${resolvedEpisode.streamSeason}::${resolvedEpisode.streamEpisode}::$manualSelection"
    }

    fun load(type: String, videoId: String, parentMetaId: String? = null, title: String? = null, season: Int? = null, episode: Int? = null, manualSelection: Boolean = false, preferLocalStreams: Boolean = false) {
        load(
            preferLocalStreams = preferLocalStreams,
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            forceRefresh = false,
        )
    }

    fun reload(type: String, videoId: String, parentMetaId: String? = null, title: String? = null, season: Int? = null, episode: Int? = null, manualSelection: Boolean = false, preferLocalStreams: Boolean = false) {
        load(
            preferLocalStreams = preferLocalStreams,
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            title = title,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            forceRefresh = true,
        )
    }

    private fun load(type: String, videoId: String, parentMetaId: String?, title: String?, season: Int?, episode: Int?, manualSelection: Boolean, forceRefresh: Boolean, preferLocalStreams: Boolean) {
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
        val requestToken = requestToken(
            type = type,
            videoId = effectiveVideoId,
            parentMetaId = parentMetaId,
            title = title,
            season = effectiveSeason,
            episode = effectiveEpisode,
            manualSelection = manualSelection,
        )
        val requestKey = "$requestToken::pluginsGrouped=${pluginUiState.groupStreamsByRepository}"
        val currentState = _uiState.value
        if (
            !forceRefresh &&
            activeRequestKey == requestKey &&
            (currentState.groups.isNotEmpty() || currentState.emptyStateReason != null || currentState.isAnyLoading)
        ) {
            log.d { "Skipping stream reload for unchanged request type=$type id=$effectiveVideoId" }
            return
        }

        activeRequestKey = requestKey
        activeJob?.cancel()
        _uiState.value = StreamsUiState(requestToken = requestToken)

        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        val localStreams = MetaDetailsRepository.findLocalStreams(effectiveVideoId)
        val includeLocalInPicker = localStreams.isNotEmpty() && !preferLocalStreams
        val scoreProfile = StreamScoreRepository.profile
        _isEpisodeRequest.value = effectiveEpisode != null
        val scoreContext = StreamScoreContexts.forPlayback(
            isEpisode = effectiveEpisode != null,
            contentId = parentMetaId ?: videoId,
            contentType = type,
        )
        // Scoring turns any automatic pick into a best-score pick; see StreamAutoPlayPolicy.
        val autoPlayMode = StreamAutoPlayPolicy.effectiveMode(playerSettings.streamAutoPlayMode, scoreProfile)
        val isAutoPlayEnabled = !manualSelection && autoPlayMode != StreamAutoPlayMode.MANUAL &&
            !(autoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
                !StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex))

        // Look up persisted binge group when both settings are enabled, unless the score profile is
        // set to override binge affinity — every downstream use here keys off this being non-null, so
        // clearing it is all it takes to hand the pick back to the scored order.
        val persistedBingeGroup = if (
            playerSettings.streamAutoPlayPreferBingeGroup &&
            playerSettings.streamAutoPlayReuseBingeGroup &&
            !StreamAutoPlayPolicy.scoreOverridesBingeGroup(autoPlayMode, scoreProfile)
        ) {
            parentMetaId?.let { BingeGroupCacheRepository.get(it) }
        } else null

        // Enable direct auto-play flow if normal auto-play is enabled,
        // OR if we have a persisted binge group in MANUAL mode
        val bingeGroupDirectFlow = !manualSelection &&
            persistedBingeGroup != null &&
            autoPlayMode == StreamAutoPlayMode.MANUAL
        // A local file reached from Home/Search is an additional source, not a silent override
        // for add-on streams. Keep the picker open so the user can choose between them.
        val isDirectAutoPlayFlow = !includeLocalInPicker && (isAutoPlayEnabled || bingeGroupDirectFlow)

        if (isDirectAutoPlayFlow) {
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isDirectAutoPlayFlow = true,
                showDirectAutoPlayOverlay = true,
            )
        }

        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(effectiveVideoId)
        val directStreams = when {
            preferLocalStreams && localStreams.isNotEmpty() -> localStreams
            embeddedStreams.isNotEmpty() -> embeddedStreams
            else -> emptyList()
        }
        if (directStreams.isNotEmpty()) {
            log.d { "Using ${directStreams.size} direct streams for type=$type id=$effectiveVideoId" }
            val group = AddonStreamGroup(
                addonName = directStreams.first().addonName,
                addonId = "embedded",
                streams = directStreams,
                isLoading = false,
            )
            val presentedGroup = StreamBadgePresentation.apply(
                groups = listOf(group),
                rules = streamBadgeRules,
            ).firstOrNull() ?: group
            // When there's a single option (e.g. a local-library file), there's nothing to choose —
            // play it immediately regardless of the auto-play-vs-select preference.
            val soloStream = directStreams.singleOrNull()?.takeIf { !manualSelection }
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = listOf(presentedGroup),
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
                autoPlayStream = soloStream,
                isDirectAutoPlayFlow = soloStream != null,
                showDirectAutoPlayOverlay = soloStream != null,
            )
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
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
            if (includeLocalInPicker) {
                _uiState.value = localOnlyStreamsState(requestToken, localStreams, streamBadgeRules)
                return
            }
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoAddonsInstalled,
            )
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

        log.d { "Found ${streamAddons.size} addons for stream type=$type id=$effectiveVideoId" }

        if (streamAddons.isEmpty() && pluginProviderGroups.isEmpty()) {
            if (includeLocalInPicker) {
                _uiState.value = localOnlyStreamsState(requestToken, localStreams, streamBadgeRules)
                return
            }
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoCompatibleAddons,
            )
            return
        }

        // Initialise loading placeholders
        val installedAddonOrder = streamAddons.map { it.addonName }
        val initialGroups = StreamAutoPlaySelector.orderAddonStreams(
            groups = buildList {
                if (includeLocalInPicker) {
                    add(
                        AddonStreamGroup(
                            addonName = localStreams.first().addonName,
                            addonId = LOCAL_LIBRARY_GROUP_ID,
                            streams = localStreams,
                            isLoading = false,
                        ),
                    )
                }
                addAll(streamAddons.map { addon ->
            AddonStreamGroup(
                addonName = addon.addonName,
                addonId = addon.addonId,
                streams = emptyList(),
                isLoading = true,
            )
                })
                addAll(pluginProviderGroups.map { providerGroup ->
            AddonStreamGroup(
                addonName = providerGroup.addonName,
                addonId = providerGroup.addonId,
                streams = emptyList(),
                isLoading = true,
            )
                })
            },
            installedOrder = installedAddonOrder,
        )
        val isInitiallyLoading = initialGroups.any { it.isLoading }
        _uiState.value = StreamsUiState(
            requestToken = requestToken,
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
            emptyStateReason = null,
            isDirectAutoPlayFlow = isDirectAutoPlayFlow,
            showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
        )

        PlaybackStartTrace.begin(
            "loadStreams type=$type id=$effectiveVideoId addons=${streamAddons.size} " +
                "scrapers=${pluginProviderGroups.sumOf { it.scrapers.size }} direct=$isDirectAutoPlayFlow",
        )
        activeJob = scope.launch {
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.BUFFERED)
            val pluginRemainingByAddonId = pluginProviderGroups
                .associate { it.addonId to it.scrapers.size }
                .toMutableMap()
            val pluginFirstErrorByAddonId = mutableMapOf<String, String>()
            val totalTasks = streamAddons.size +
                pluginProviderGroups.sumOf { it.scrapers.size }

            val installedAddonNames = installedAddonOrder.toSet()
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val debridAvailabilityJobs = mutableListOf<Job>()
            var autoSelectTriggered = false
            var timeoutElapsed = false

            // Runs whatever auto-play selection is currently allowed, called after every
            // addon/plugin/debrid response. Before the configured timeout only a persisted
            // binge-group match may fire; once the timeout has elapsed — which the "instant"
            // and "unlimited" modes set immediately — any eligible stream may be selected.
            // This is what gives those modes their documented select-on-each-response
            // behavior instead of waiting for the slowest addon to finish.
            fun autoSelectOnResponse() {
                if (!isDirectAutoPlayFlow || autoSelectTriggered) return
                if (!timeoutElapsed && persistedBingeGroup == null) return
                val allStreams = _uiState.value.groups
                    .filterNot { it.addonId == LOCAL_LIBRARY_GROUP_ID }
                    .flatMap { it.streams }
                if (allStreams.isEmpty()) return
                val selected = StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = autoPlayMode,
                    scoreProfile = scoreProfile,
                    scoreContext = scoreContext,
                    regexPattern = playerSettings.streamAutoPlayRegex,
                    source = playerSettings.streamAutoPlaySource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                    preferredBingeGroup = persistedBingeGroup,
                    preferBingeGroupInSelection = persistedBingeGroup != null,
                    bingeGroupOnly = !timeoutElapsed,
                    debridEnabled = debridSettings.canResolvePlayableLinks,
                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                ) ?: return
                autoSelectTriggered = true
                PlaybackStartTrace.mark("autoSelect:${selected.addonName}")
                _uiState.update { it.copy(autoPlayStream = selected) }
            }

            fun publishCompletion(completion: StreamLoadCompletion) {
                if (completions.trySend(completion).isFailure) {
                    log.d { "Ignoring late stream load completion after channel close" }
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
                ).firstOrNull() ?: badgeGroup
            }

            fun publishAddonGroup(group: AddonStreamGroup) {
                _uiState.update { current ->
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

            fun publishAddonGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishAddonGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)

                // Torrent-name lookup for already-resolved debrid rows, so a season pack among them
                // can be marked without opening it. Deliberately outside the cache-check gate and
                // never awaited: the list publishes now, and a row lights up if a name arrives.
                debridAvailabilityJobs += launch {
                    val named = LocalDebridAvailabilityService.annotateTorrentNames(
                        groups = listOf(group),
                        eligibleGroupIds = eligibleGroupIds,
                    ).firstOrNull()
                    if (named != null && named != group) publishAddonGroup(presentStreamGroup(named))
                }

                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishAddonGroup(presentStreamGroup(group))
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
                    publishAddonGroup(presentStreamGroup(availabilityGroup))
                    autoSelectOnResponse()
                }
                debridAvailabilityJobs += availabilityJob
            }

            val timeoutJob = if (isDirectAutoPlayFlow) {
                val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds
                val isUnlimitedTimeout = timeoutSeconds == Int.MAX_VALUE
                // Timeout semantics:
                // - 0 (instant): timeoutElapsed immediately, full select on each response
                // - 1-30 (bounded): wait the configured delay, then full select
                // - unlimited (Int.MAX_VALUE): timeoutElapsed immediately, full select on each response,
                //   with 60s hard fallback to stream picker
                if (timeoutSeconds <= 0 || isUnlimitedTimeout) {
                    timeoutElapsed = true
                    // For unlimited: launch a hard 60s fallback to dismiss overlay
                    if (isUnlimitedTimeout) {
                        launch {
                            delay(60_000L)
                            if (!autoSelectTriggered) {
                                autoSelectTriggered = true
                                val allStreams = _uiState.value.groups
                                    .filterNot { it.addonId == LOCAL_LIBRARY_GROUP_ID }
                                    .flatMap { it.streams }
                                if (allStreams.isNotEmpty()) {
                                    val selected = StreamAutoPlaySelector.selectAutoPlayStream(
                                        streams = allStreams,
                                        mode = autoPlayMode,
                                        scoreProfile = scoreProfile,
                                        scoreContext = scoreContext,
                                        regexPattern = playerSettings.streamAutoPlayRegex,
                                        source = playerSettings.streamAutoPlaySource,
                                        installedAddonNames = installedAddonNames,
                                        selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                        selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                        preferredBingeGroup = persistedBingeGroup,
                                        preferBingeGroupInSelection = persistedBingeGroup != null,
                                        bingeGroupOnly = false,
                                        debridEnabled = debridSettings.canResolvePlayableLinks,
                                        activeResolverProviderId = debridSettings.activeResolverProviderId,
                                    )
                                    selected?.let { PlaybackStartTrace.mark("autoSelect:fallback60s:${it.addonName}") }
                                    _uiState.update { it.copy(autoPlayStream = selected) }
                                }
                                if (_uiState.value.autoPlayStream == null) {
                                    _uiState.update {
                                        it.copy(
                                            isDirectAutoPlayFlow = false,
                                            showDirectAutoPlayOverlay = false,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    // Bounded timeout (1-30s)
                    launch {
                        delay(timeoutSeconds * 1_000L)
                        timeoutElapsed = true
                        if (!autoSelectTriggered) {
                            val allStreams = _uiState.value.groups
                                .filterNot { it.addonId == LOCAL_LIBRARY_GROUP_ID }
                                .flatMap { it.streams }
                            if (allStreams.isNotEmpty()) {
                                val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
                                    streams = allStreams,
                                    mode = autoPlayMode,
                                    scoreProfile = scoreProfile,
                                    scoreContext = scoreContext,
                                    regexPattern = playerSettings.streamAutoPlayRegex,
                                    source = playerSettings.streamAutoPlaySource,
                                    installedAddonNames = installedAddonNames,
                                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                    preferredBingeGroup = persistedBingeGroup,
                                    preferBingeGroupInSelection = persistedBingeGroup != null,
                                    bingeGroupOnly = false,
                                    debridEnabled = debridSettings.canResolvePlayableLinks,
                                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                                )
                                if (evaluation.stream != null || !evaluation.hasPendingDebridCandidate) {
                                    autoSelectTriggered = true
                                    evaluation.stream?.let { PlaybackStartTrace.mark("autoSelect:timeout:${it.addonName}") }
                                    _uiState.update {
                                        it.copy(
                                            autoPlayStream = evaluation.stream,
                                            autoPlayCandidates = evaluation.readyStreams,
                                        )
                                    }
                                }
                                if (evaluation.stream == null && !evaluation.hasPendingDebridCandidate) {
                                    _uiState.update {
                                        it.copy(
                                            isDirectAutoPlayFlow = false,
                                            showDirectAutoPlayOverlay = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                null
            }

            streamAddons.forEach { addon ->
                launch {
                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = effectiveVideoId,
                    )
                    log.d { "Fetching streams from: $url" }

                    val displayName = addon.addonName
                    val group = runCatchingUnlessCancelled {
                        val payload = httpGetTextWithHeaders(url, STREAM_METADATA_REQUEST_HEADERS)
                        val parsedStreams = StreamParser.parse(
                            payload = payload,
                            addonName = displayName,
                            addonId = addon.addonId,
                            addonLogo = addon.manifest.logoUrl,
                        )
                        val streams = parsedStreams.filterForRequestedEpisode(
                            season = effectiveSeason,
                            episode = effectiveEpisode,
                            episodeTitlesByCoordinate = MetaDetailsRepository.episodeTitlesByCoordinate(
                                type = type,
                                id = parentMetaId ?: videoId,
                            ),
                            seriesTitle = MetaDetailsRepository.seriesTitleFor(
                                type = type,
                                id = parentMetaId ?: videoId,
                            ) ?: title,
                        )
                        val removedCount = parsedStreams.size - streams.size
                        if (removedCount > 0) {
                            log.d { "Filtered $removedCount explicit episode mismatches from $displayName" }
                        }
                        streams
                    }.fold(
                        onSuccess = { streams ->
                            log.d { "Got ${streams.size} streams from ${displayName}" }
                            AddonStreamGroup(
                                addonName = displayName,
                                addonId = addon.addonId,
                                streams = streams,
                                isLoading = false,
                            )
                        },
                        onFailure = { err ->
                            log.w(err) { "Failed to fetch streams from ${displayName}" }
                            AddonStreamGroup(
                                addonName = displayName,
                                addonId = addon.addonId,
                                streams = emptyList(),
                                isLoading = false,
                                error = err.message,
                            )
                        },
                    )
                    publishCompletion(StreamLoadCompletion.Addon(group))
                }
            }

            pluginProviderGroups.forEach { providerGroup ->
                val includeScraperNameInSubtitle = false
                providerGroup.scrapers.forEach { scraper ->
                    launch {
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
                        val result = completion.group
                        PlaybackStartTrace.mark("addon:${result.addonName} streams=${result.streams.size}")
                        publishAddonGroupAfterCacheCheck(result)
                        autoSelectOnResponse()
                    }

                    is StreamLoadCompletion.PluginScraper -> {
                        val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                        pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                        if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                            pluginFirstErrorByAddonId[completion.addonId] = completion.error
                        }

                        _uiState.update { current ->
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
                        PlaybackStartTrace.mark("plugin:${completion.addonId} streams=${completion.streams.size}")
                        autoSelectOnResponse()
                    }

                }
            }

            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()
                autoSelectOnResponse()
            }

            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = _uiState.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = effectiveSeason,
                    episode = effectiveEpisode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                    contentId = parentMetaId ?: videoId,
                    contentType = type,
                ) { original, prepared ->
                    _uiState.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }

                    // Selection reacts to each debrid-prepared stream as it becomes playable.
                    autoSelectOnResponse()
                }

                autoSelectOnResponse()
            }

            // All addons finished — run final auto-select if not yet triggered
            if (isDirectAutoPlayFlow && !autoSelectTriggered) {
                autoSelectTriggered = true
                val allStreams = _uiState.value.groups
                    .filterNot { it.addonId == LOCAL_LIBRARY_GROUP_ID }
                    .flatMap { it.streams }
                val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
                    streams = allStreams,
                    mode = autoPlayMode,
                    scoreProfile = scoreProfile,
                    scoreContext = scoreContext,
                    regexPattern = playerSettings.streamAutoPlayRegex,
                    source = playerSettings.streamAutoPlaySource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                    preferredBingeGroup = persistedBingeGroup,
                    preferBingeGroupInSelection = persistedBingeGroup != null,
                    bingeGroupOnly = false,
                    debridEnabled = debridSettings.canResolvePlayableLinks,
                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                )
                evaluation.stream?.let { PlaybackStartTrace.mark("autoSelect:allResponses:${it.addonName}") }
                _uiState.update {
                    it.copy(
                        autoPlayStream = evaluation.stream,
                        autoPlayCandidates = evaluation.readyStreams,
                    )
                }
            }
            if (isDirectAutoPlayFlow && _uiState.value.autoPlayStream == null) {
                _uiState.update {
                    it.copy(
                        isDirectAutoPlayFlow = false,
                        showDirectAutoPlayOverlay = false,
                    )
                }
            }
            timeoutJob?.cancel()
        }
    }

    fun selectFilter(addonId: String?) {
        _uiState.update { it.copy(selectedFilter = addonId) }
    }

    fun consumeAutoPlay() {
        PlaybackStartTrace.mark("autoPlayConsumed")
        activeRequestKey = null
        _uiState.update {
            it.copy(
                autoPlayStream = null,
                autoPlayCandidates = emptyList(),
                isDirectAutoPlayFlow = false,
                showDirectAutoPlayOverlay = false,
            )
        }
    }

    fun skipAutoPlayStream(stream: StreamItem): Boolean {
        var hasNext = false
        _uiState.update { current ->
            val failedIndex = current.autoPlayCandidates.indexOf(stream)
            val remaining = if (failedIndex >= 0) {
                current.autoPlayCandidates.drop(failedIndex + 1)
            } else {
                current.autoPlayCandidates.drop(1)
            }
            hasNext = remaining.isNotEmpty()
            current.copy(
                autoPlayStream = remaining.firstOrNull(),
                autoPlayCandidates = remaining,
                isDirectAutoPlayFlow = remaining.isNotEmpty(),
                showDirectAutoPlayOverlay = remaining.isNotEmpty(),
            )
        }
        return hasNext
    }

    fun cancelLoading() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { current ->
            if (!current.isAnyLoading && current.groups.none { it.isLoading }) {
                current
            } else {
                val updatedGroups = current.groups.map { group ->
                    if (group.isLoading) group.copy(isLoading = false) else group
                }
                current.copy(
                    groups = updatedGroups,
                    isAnyLoading = false,
                    emptyStateReason = if (updatedGroups.isEmpty()) {
                        current.emptyStateReason
                    } else {
                        updatedGroups.toEmptyStateReason(anyLoading = false)
                    },
                )
            }
        }
    }

    private fun localOnlyStreamsState(
        requestToken: String,
        localStreams: List<StreamItem>,
        badgeRules: StreamBadgeRules,
    ): StreamsUiState {
        val group = AddonStreamGroup(
            addonName = localStreams.first().addonName,
            addonId = LOCAL_LIBRARY_GROUP_ID,
            streams = localStreams,
            isLoading = false,
        )
        val presentedGroup = StreamBadgePresentation.apply(
            groups = listOf(group),
            rules = badgeRules,
        ).firstOrNull() ?: group
        return StreamsUiState(
            requestToken = requestToken,
            groups = listOf(presentedGroup),
            activeAddonIds = setOf(LOCAL_LIBRARY_GROUP_ID),
            isAnyLoading = false,
        )
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        _uiState.value = StreamsUiState()
    }

    fun setOverlayVisible(visible: Boolean, message: String? = null) {
        _uiState.update { it.copy(showDirectAutoPlayOverlay = visible, overlayMessage = message) }
    }
}
