package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.metadata.MediaIdResolver
import com.nuvio.app.features.player.PlaybackStartTrace
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.coroutines.async
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

    private val preparationMutex = Mutex()
    private var preparationJob: Job? = null
    private var requestedInputKey: String? = null
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
        PerfTelemetry.count("StreamsRepository.load")
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
        PerfTelemetry.count("StreamsRepository.reload")
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
        // Repository initialization and local-library lookup must not block the first picker frame.
        // Serialize preparation, and cancel an obsolete request before it can publish its result.
        val inputKey = "$type::$videoId::$parentMetaId::$season::$episode::$manualSelection::$preferLocalStreams"
        if (forceRefresh || requestedInputKey != inputKey) {
            requestedInputKey = inputKey
            activeJob?.cancel()
            _uiState.value = StreamsUiState(isAnyLoading = true)
        }
        preparationJob?.cancel()
        preparationJob = scope.launch {
            preparationMutex.withLock {
                currentCoroutineContext().ensureActive()
                PlaybackStartTrace.markPendingOrActive("streamsPrepare:start")
                prepareLoad(type, videoId, parentMetaId, title, season, episode, manualSelection, forceRefresh, preferLocalStreams)
            }
        }
    }

    private suspend fun prepareLoad(type: String, videoId: String, parentMetaId: String?, title: String?, season: Int?, episode: Int?, manualSelection: Boolean, forceRefresh: Boolean, preferLocalStreams: Boolean) {
        val resolvedEpisode = MediaIdResolver.resolveLocalEpisodeIdentity(
            contentType = type,
            parentMetaId = parentMetaId ?: videoId,
            videoId = videoId,
            title = title,
            season = season,
            episode = episode,
            isAnimeHint = type.equals("anime", ignoreCase = true),
        )
        currentCoroutineContext().ensureActive()
        PlaybackStartTrace.markPendingOrActive("streamsPrepare:identity")
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
        currentCoroutineContext().ensureActive()
        PlaybackStartTrace.markPendingOrActive("streamsPrepare:plugins")
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
        currentCoroutineContext().ensureActive()
        PlaybackStartTrace.markPendingOrActive("streamsPrepare:settings")
        val localStreams = MetaDetailsRepository.findLocalStreams(effectiveVideoId)
        currentCoroutineContext().ensureActive()
        PlaybackStartTrace.markPendingOrActive("streamsPrepare:localSources")
        val includeLocalInPicker = localStreams.isNotEmpty() && !preferLocalStreams
        val scoreProfile = StreamScoreRepository.profile
        // Prefetched provider responses are keyed on the *resolved* identity, the same one every
        // fetch below uses, so a background search and this load agree on the key for anime ids
        // that the resolver rewrote. A forced refresh never reads them: reload() exists precisely
        // because the caller has reason to distrust what it already has.
        val prefetchContentKey = StreamPrefetchCache.contentKey(
            type = type,
            videoId = effectiveVideoId,
            season = effectiveSeason,
            episode = effectiveEpisode,
        )
        val prefetchMaxAgeMs = if (forceRefresh) {
            0L
        } else {
            playerSettings.streamPrefetchCacheMinutes * 60L * 1000L
        }
        // The Continue Watching cache is shown first and refreshed; a forced refresh skips reading
        // it for the same reason it skips the prefetch, but still writes what it finds back.
        val readCwCache = !forceRefresh
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
        // The release watched last within that group (upstream d2cc0fb8a), aimed at this episode in
        // the numbering filenames use, so autoplay picks the same release rather than any row that
        // happens to share the group.
        val persistedRelease = if (persistedBingeGroup != null) {
            parentMetaId?.let { BingeGroupCacheRepository.releaseHint(it, season, episode) }
        } else null

        // A local file reached from Home/Search is an additional source, not a silent override
        // for add-on streams. Keep the picker open so the user can choose between them.
        //
        // A persisted binge group used to force this flow on in MANUAL mode too. That defeated the
        // one setting whose whole meaning is "always let me choose", and because the binge cache is
        // keyed per show it only fired on titles played before — so Continue Watching auto-played
        // some entries and opened the picker for others, with nothing on screen to explain the
        // difference. Both binge toggles default to on while the mode defaults to MANUAL, so this
        // hit a default install. Binge affinity still orders the picker and still drives the
        // in-player next-episode pick (PlayerNextEpisodeAutoPlay, which uses PlayerStreamsRepository
        // and is untouched by this); it just no longer starts playback by itself.
        //
        // A title the user set to play its best online source is the exception: its local copy is a
        // backup, still listed should auto-play fall back to the picker, but never a reason to stop.
        // Auto-play already skips the local group when it picks.
        val localCopyIsBackup = includeLocalInPicker && !manualSelection &&
            LocalLibraryRepository.routingFor(parentMetaId, effectiveVideoId).ignoresLocalFile
        val isDirectAutoPlayFlow = (!includeLocalInPicker || localCopyIsBackup) && isAutoPlayEnabled

        currentCoroutineContext().ensureActive()
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
        currentCoroutineContext().ensureActive()
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
        currentCoroutineContext().ensureActive()
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

        currentCoroutineContext().ensureActive()
        PlaybackStartTrace.markPendingOrActive("streamsPrepare:complete")
        PlaybackStartTrace.begin(
            "loadStreams type=$type id=$effectiveVideoId addons=${streamAddons.size} " +
                "scrapers=${pluginProviderGroups.sumOf { it.scrapers.size }} direct=$isDirectAutoPlayFlow",
        )
        activeJob = scope.launch {
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.BUFFERED)
            // Native anime episodes: TMDB coordinates for plugin scrapers and the addon fallback.
            val animeTmdbLocation = if (AnimeTmdbEpisodeLocator.appliesTo(resolvedEpisode)) {
                async { runCatchingUnlessCancelled { AnimeTmdbEpisodeLocator.locate(resolvedEpisode) }.getOrNull() }
            } else {
                null
            }
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
                    .autoPlayEligible()
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
                    preferredRelease = persistedRelease,
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

            // A scraper's cached rows join its group while the scraper runs; its completion swaps
            // them for the live rows (StreamLoadCompletion.PluginScraper.replacesCachedRows).
            fun publishCachedPluginRows(groupId: String, rows: List<StreamItem>) {
                _uiState.update { current ->
                    current.copy(
                        groups = current.groups.map { group ->
                            if (group.addonId == groupId) {
                                group.copy(streams = (group.streams + rows).sortedForGroupedDisplay())
                            } else {
                                group
                            }
                        },
                    )
                }
            }

            // Every real answer so far, for the Continue Watching cache. Its gates are judged on the
            // whole target, so this grows as providers answer and is offered each time until it
            // passes; after that each new answer is stored as it lands.
            val cwAnswers = mutableMapOf<String, List<StreamItem>>()
            var cwStored = false
            fun offerToCwCache(providerId: String, answer: List<StreamItem>?) {
                if (answer == null || providerId.isBlank()) return
                cwAnswers[providerId] = answer
                if (cwStored) {
                    CwStreamCache.offer(prefetchContentKey, mapOf(providerId to answer), gatesPassed = true)
                } else {
                    cwStored = CwStreamCache.offer(prefetchContentKey, cwAnswers.toMap())
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
                                    .autoPlayEligible()
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
                                        preferredRelease = persistedRelease,
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
                                .autoPlayEligible()
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
                                    preferredRelease = persistedRelease,
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
                    val displayName = addon.addonName
                    val prefetched = (
                        StreamPrefetchCache
                            .get(prefetchContentKey, addon.addonId, prefetchMaxAgeMs)
                            ?.streams
                            // Nothing cached, but a background sweep may already be waiting on this
                            // very provider. Waiting for that costs the same time as our own request
                            // and one fewer call; returns null the instant no sweep is running.
                            ?: StreamPrefetchCache
                                .awaitInFlight(prefetchContentKey, addon.addonId, prefetchMaxAgeMs)
                        )?.reStampedFor(displayName, addon.addonId)
                    if (prefetched != null) {
                        PlaybackStartTrace.mark("prefetchHit:$displayName streams=${prefetched.size}")
                        log.d { "Serving ${prefetched.size} prefetched streams from $displayName" }
                        publishCompletion(
                            StreamLoadCompletion.Addon(
                                AddonStreamGroup(
                                    addonName = displayName,
                                    addonId = addon.addonId,
                                    streams = prefetched,
                                    isLoading = false,
                                ),
                                cacheableAnswer = prefetched,
                            ),
                        )
                        return@launch
                    }

                    val cwEntry = if (readCwCache) CwStreamCache.get(prefetchContentKey, addon.addonId) else null
                    val cwRows = cwEntry?.streams
                        ?.reStampedFor(displayName, addon.addonId)
                        ?.markedCachedAt(cwEntry.fetchedAtMs)
                    if (cwEntry != null && cwRows != null) {
                        val cachedGroup = AddonStreamGroup(
                            addonName = displayName,
                            addonId = addon.addonId,
                            streams = cwRows,
                            isLoading = false,
                        )
                        if (CwStreamCache.isFreshEnoughToSkipRefresh(cwEntry)) {
                            PlaybackStartTrace.mark("cwCacheHit:$displayName streams=${cwRows.size}")
                            publishCompletion(StreamLoadCompletion.Addon(cachedGroup))
                            return@launch
                        }
                        // Shown now, still marked loading: the live answer replaces it below.
                        publishAddonGroup(presentStreamGroup(cachedGroup.copy(isLoading = true)))
                    }

                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = effectiveVideoId,
                    )
                    log.d { "Fetching streams from: ${com.nuvio.app.core.network.redactAddonUrl(url)}" }

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
                        if (!streams.hasPlayableStream() && AnimeFranchiseEpisodeLocator.appliesTo(resolvedEpisode)) {
                            animeFranchiseFallbackStreams(
                                franchiseVideoId = AnimeFranchiseEpisodeLocator.franchiseVideoId(
                                    identity = resolvedEpisode,
                                    type = type,
                                    parentMetaId = parentMetaId,
                                ),
                                manifest = addon.manifest,
                                type = type,
                                seriesTitle = MetaDetailsRepository.seriesTitleFor(
                                    type = type,
                                    id = parentMetaId ?: videoId,
                                ) ?: title,
                                fetch = { fallbackUrl -> httpGetTextWithHeaders(fallbackUrl, STREAM_METADATA_REQUEST_HEADERS) },
                                parse = { fallbackPayload ->
                                    StreamParser.parse(
                                        payload = fallbackPayload,
                                        addonName = displayName,
                                        addonId = addon.addonId,
                                        addonLogo = addon.manifest.logoUrl,
                                    )
                                },
                            )?.also { log.d { "Anime IMDb-numbering fallback found ${it.size} streams from $displayName" } }
                                ?: streams
                        } else {
                            streams
                        }
                    }.fold(
                        onSuccess = { streams ->
                            log.d { "Got ${streams.size} streams from ${displayName}" }
                            StreamLoadCompletion.Addon(
                                AddonStreamGroup(
                                    addonName = displayName,
                                    addonId = addon.addonId,
                                    streams = streams,
                                    isLoading = false,
                                ),
                                cacheableAnswer = streams,
                            )
                        },
                        onFailure = { err ->
                            log.w(err) { "Failed to fetch streams from ${displayName}" }
                            // With cached rows on screen they stay, still tagged: better than an
                            // empty provider.
                            StreamLoadCompletion.Addon(
                                AddonStreamGroup(
                                    addonName = displayName,
                                    addonId = addon.addonId,
                                    streams = cwRows.orEmpty(),
                                    isLoading = false,
                                    error = if (cwRows == null) err.message else null,
                                ),
                            )
                        },
                    )
                    publishCompletion(group)
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
                            log.d { "Serving ${prefetched.size} prefetched streams from ${scraper.name}" }
                            publishCompletion(
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = prefetched,
                                    error = null,
                                    providerId = scraperProviderId,
                                    cacheableAnswer = prefetched,
                                ),
                            )
                            return@launch
                        }

                        val cwEntry = if (readCwCache) CwStreamCache.get(prefetchContentKey, scraperProviderId) else null
                        val cwRows = cwEntry?.streams
                            ?.reStampedFor(providerGroup.addonName, providerGroup.addonId)
                            ?.markedCachedAt(cwEntry.fetchedAtMs)
                            .orEmpty()
                        if (cwEntry != null) {
                            if (CwStreamCache.isFreshEnoughToSkipRefresh(cwEntry)) {
                                publishCompletion(
                                    StreamLoadCompletion.PluginScraper(
                                        addonId = providerGroup.addonId,
                                        streams = cwRows,
                                        error = null,
                                        providerId = scraperProviderId,
                                    ),
                                )
                                return@launch
                            }
                            if (cwRows.isNotEmpty()) publishCachedPluginRows(providerGroup.addonId, cwRows)
                        }

                        val pluginTarget = resolvedEpisode.pluginEpisodeTarget(animeTmdbLocation?.await())
                        val completion = PluginRepository.executeScraper(
                            scraper = scraper,
                            tmdbId = pluginTarget.contentId,
                            mediaType = type,
                            season = pluginTarget.season,
                            episode = pluginTarget.episode,
                        ).fold(
                            onSuccess = { results ->
                                val streams = results.map { result ->
                                    result.toStreamItem(
                                        scraper = scraper,
                                        addonName = providerGroup.addonName,
                                        addonId = providerGroup.addonId,
                                        includeScraperNameInSubtitle = includeScraperNameInSubtitle,
                                    )
                                }
                                StreamLoadCompletion.PluginScraper(
                                    addonId = providerGroup.addonId,
                                    streams = streams,
                                    error = null,
                                    providerId = scraperProviderId,
                                    cacheableAnswer = streams,
                                    replacesCachedRows = cwRows,
                                )
                            },
                            onFailure = { error ->
                                // Logged, not only shown: every plugin failing on the native build
                                // (a missing JNI registration, 2026-10-17) left no trace in the log.
                                log.w(error) { "plugin scraper failed: ${scraper.name}" }
                                if (cwEntry != null) {
                                    // Keep what the cache showed (tagged) rather than an error.
                                    StreamLoadCompletion.PluginScraper(
                                        addonId = providerGroup.addonId,
                                        streams = cwRows,
                                        error = null,
                                        providerId = scraperProviderId,
                                        replacesCachedRows = cwRows,
                                    )
                                } else {
                                    StreamLoadCompletion.PluginScraper(
                                        addonId = providerGroup.addonId,
                                        streams = emptyList(),
                                        error = error.message ?: getString(Res.string.streams_failed_to_load_scraper, scraper.name),
                                    )
                                }
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
                        offerToCwCache(result.addonId, completion.cacheableAnswer)
                        publishAddonGroupAfterCacheCheck(result)
                        autoSelectOnResponse()
                    }

                    is StreamLoadCompletion.PluginScraper -> {
                        val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                        pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                        if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                            pluginFirstErrorByAddonId[completion.addonId] = completion.error
                        }
                        offerToCwCache(completion.providerId, completion.cacheableAnswer)

                        _uiState.update { current ->
                            val updated = StreamAutoPlaySelector.orderAddonStreams(
                                groups = current.groups.map { group ->
                                    if (group.addonId != completion.addonId) {
                                        group
                                    } else {
                                        val withoutCached = if (completion.replacesCachedRows.isEmpty()) {
                                            group.streams
                                        } else {
                                            val replaced = completion.replacesCachedRows.toSet()
                                            group.streams.filterNot { it in replaced }
                                        }
                                        val mergedStreams = if (completion.streams.isEmpty()) {
                                            withoutCached
                                        } else {
                                            (withoutCached + completion.streams).sortedForGroupedDisplay()
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
                        PlaybackStartTrace.mark("plugin:${com.nuvio.app.core.network.redactAddonId(completion.addonId)} streams=${completion.streams.size}")
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
                    .autoPlayEligible()
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
                    preferredRelease = persistedRelease,
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
        preparationJob?.cancel()
        preparationJob = null
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
        preparationJob?.cancel()
        preparationJob = null
        requestedInputKey = null
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        _uiState.value = StreamsUiState()
    }

    fun setOverlayVisible(visible: Boolean, message: String? = null) {
        _uiState.update { it.copy(showDirectAutoPlayOverlay = visible, overlayMessage = message) }
    }
}
