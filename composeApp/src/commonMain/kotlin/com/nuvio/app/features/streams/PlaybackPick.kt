package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import com.nuvio.app.features.locallibrary.LocalTitlePlayback

/**
 * Everything one automatic stream pick needs, gathered once per load so every pick made for that load (the early pick,
 * the timeout pick, the per-response pick, the final pick) asks exactly the same question. Built from Settings >
 * Playback > What plays ([PlaybackPickSettings]); see `Docs/plans/playback-priority.md`.
 *
 * A pick runs four steps: the remembered release first (when [bingeGroup] is set), then the filters (each source's
 * auto-play switch, the regex filter, the score minimum), then the order ([mode]: the source priority order as the
 * stream list arrives, or best score), then the first stream that is ready.
 */
data class PlaybackPickRules(
    /** [StreamAutoPlayMode.FIRST_STREAM] (priority order), [StreamAutoPlayMode.SCORED] or MANUAL (no pick). */
    val mode: StreamAutoPlayMode,
    /** Only streams matching this are picked; blank = all. */
    val regexFilter: String = "",
    val installedAddonNames: Set<String> = emptySet(),
    /** Each source's auto-play switch; null lets every source auto-play (a local-file binge). */
    val sources: PlaybackPickSettings? = null,
    /** The binge group to try first, or null when "same release first" does not apply to this pick. */
    val bingeGroup: String? = null,
    /** Within [bingeGroup], the release watched last. */
    val release: BingeReleaseHint? = null,
    val debridEnabled: Boolean = true,
    val activeResolverProviderId: String? = null,
    val scoreProfile: StreamScoreProfile = StreamScoreProfile(),
    val scoreContext: StreamScoreContext = StreamScoreContext.MOVIE,
) {
    val preferBingeGroup: Boolean get() = !bingeGroup.isNullOrBlank()

    private val regexMatcher: ((StreamItem) -> Boolean)? by lazy {
        regexFilter.takeIf { StreamAutoPlayPolicy.isRegexSelectionConfigured(it) }?.let(StreamAutoPlaySelector::regexMatcher)
    }

    /** The streams this pick may choose from: auto-play switch on, regex matched, not under the score minimum. */
    fun allowed(streams: List<StreamItem>): List<StreamItem> {
        val minimum = scoreProfile.enabled && scoreProfile.minimumScore != null
        return streams.filter { stream ->
            (sources?.mayAutoPlay(PlaybackSources.keyFor(stream, installedAddonNames)) ?: true) &&
                (regexMatcher?.invoke(stream) ?: true) &&
                (!minimum || !StreamScorer.score(stream, scoreProfile, scoreContext).rejected)
        }
    }

    fun evaluate(streams: List<StreamItem>, bingeGroupOnly: Boolean = false): StreamAutoPlayEvaluation =
        StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = allowed(streams),
            mode = mode,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = installedAddonNames,
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = bingeGroup,
            preferBingeGroupInSelection = preferBingeGroup,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
            // Best score ranks with the profile whatever scoring's own switches say; priority order never ranks.
            scoreProfile = if (mode == StreamAutoPlayMode.SCORED) scoreProfile.copy(useForFirstStream = true) else StreamScoreProfile(),
            scoreContext = scoreContext,
            preferredRelease = release,
        )

    fun select(streams: List<StreamItem>, bingeGroupOnly: Boolean = false): StreamItem? =
        evaluate(streams, bingeGroupOnly).stream

    /** Which rule chose [stream], for the pick log. */
    fun reasonFor(stream: StreamItem): String {
        val group = bingeGroup?.trim().orEmpty()
        if (group.isNotEmpty() && stream.behaviorHints.bingeGroup == group) {
            return if (release != null && release.rank(stream) < 2) "same release" else "same binge group"
        }
        if (stream.streamType.equals("local", ignoreCase = true)) return "local file"
        return when (mode) {
            StreamAutoPlayMode.SCORED -> if (scoreProfile.enabled) "best score" else "priority order"
            StreamAutoPlayMode.MANUAL -> "manual"
            else -> "priority order"
        }
    }
}

/** A first click's pick, decided before the providers answer; [rules] needs the installed addons, known later. */
data class FirstPlayPick(
    val mode: StreamAutoPlayMode,
    val isAutoPlayEnabled: Boolean,
    val bingeGroup: String?,
    val release: BingeReleaseHint?,
    private val settings: PlaybackPickSettings,
    private val debridEnabled: Boolean,
    private val activeResolverProviderId: String?,
    private val scoreProfile: StreamScoreProfile,
    private val scoreContext: StreamScoreContext,
) {
    fun rules(installedAddonNames: Set<String>): PlaybackPickRules = PlaybackPickRules(
        mode = mode,
        regexFilter = settings.regexFilter,
        installedAddonNames = installedAddonNames,
        sources = settings,
        bingeGroup = bingeGroup,
        release = release,
        debridEnabled = debridEnabled,
        activeResolverProviderId = activeResolverProviderId,
        scoreProfile = scoreProfile,
        scoreContext = scoreContext,
    )
}

object PlaybackPick {
    private val log = Logger.withTag("PlaybackPick")

    /** The automatic mode the [PickBy] choice stands for. */
    fun autoMode(settings: PlaybackPickSettings): StreamAutoPlayMode =
        if (settings.pickBy == PickBy.BEST_SCORE) StreamAutoPlayMode.SCORED else StreamAutoPlayMode.FIRST_STREAM

    /**
     * The rules a first click (Details, Home, Continue Watching, Search) picks by. "Show me the sources" picks nothing,
     * except for a play Live TV armed to pick by itself ([StreamAutoPlayOverride]). With "same release first" the
     * release last played of this show goes first, and may play before the other sources answer.
     */
    fun firstPlay(
        settings: PlaybackPickSettings,
        debridSettings: DebridSettings,
        scoreProfile: StreamScoreProfile,
        scoreContext: StreamScoreContext,
        manualSelection: Boolean,
        forcedAuto: Boolean,
        parentMetaId: String?,
        season: Int?,
        episode: Int?,
        rememberedBingeGroup: (String) -> String? = BingeGroupCacheRepository::get,
        rememberedRelease: (String, Int?, Int?) -> BingeReleaseHint? = BingeGroupCacheRepository::releaseHint,
    ): FirstPlayPick {
        val mode = if (settings.onPlay == PlayClick.PLAY_FOR_ME || forcedAuto) autoMode(settings) else StreamAutoPlayMode.MANUAL
        val isAutoPlayEnabled = !manualSelection && mode != StreamAutoPlayMode.MANUAL
        val bingeGroup = if (isAutoPlayEnabled && settings.nextEpisode == NextEpisodePick.SAME_RELEASE_FIRST) {
            parentMetaId?.let(rememberedBingeGroup)
        } else {
            null
        }
        // The release watched last within that group (upstream d2cc0fb8a), aimed at this episode in the numbering
        // filenames use, so autoplay picks the same release rather than any row that happens to share the group.
        val release = if (bingeGroup != null) parentMetaId?.let { rememberedRelease(it, season, episode) } else null
        return FirstPlayPick(
            mode = mode,
            isAutoPlayEnabled = isAutoPlayEnabled,
            bingeGroup = bingeGroup,
            release = release,
            settings = settings,
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
            scoreProfile = scoreProfile,
            scoreContext = scoreContext,
        )
    }

    /**
     * The rules the next episode picks by. Playing a local file (local affinity), the next local file is taken as it
     * comes, from every source. Otherwise What plays applies, with the playing stream's binge group first when "same
     * release first" is set.
     */
    fun nextEpisode(
        settings: PlaybackPickSettings,
        localAffinity: Boolean,
        currentStreamBingeGroup: String?,
        release: BingeReleaseHint?,
        installedAddonNames: Set<String>,
        debridSettings: DebridSettings,
        scoreProfile: StreamScoreProfile,
        scoreContext: StreamScoreContext,
    ): PlaybackPickRules {
        val binge = currentStreamBingeGroup?.takeIf { !localAffinity && settings.nextEpisode == NextEpisodePick.SAME_RELEASE_FIRST }
        return PlaybackPickRules(
            mode = if (localAffinity) StreamAutoPlayMode.FIRST_STREAM else autoMode(settings),
            regexFilter = if (localAffinity) "" else settings.regexFilter,
            installedAddonNames = installedAddonNames,
            sources = settings.takeUnless { localAffinity },
            bingeGroup = binge,
            release = release.takeIf { binge != null },
            debridEnabled = debridSettings.canResolvePlayableLinks,
            activeResolverProviderId = debridSettings.activeResolverProviderId,
            scoreProfile = if (localAffinity) StreamScoreProfile() else scoreProfile,
            scoreContext = scoreContext,
        )
    }

    /** The rules the instant-playback debrid preparer uses to choose which debrid rows to resolve first. */
    fun debridPreparation(
        settings: PlaybackPickSettings,
        installedAddonNames: Set<String>,
        scoreProfile: StreamScoreProfile,
        scoreContext: StreamScoreContext,
    ): PlaybackPickRules = PlaybackPickRules(
        mode = autoMode(settings),
        regexFilter = settings.regexFilter,
        installedAddonNames = installedAddonNames,
        sources = settings,
        scoreProfile = scoreProfile,
        scoreContext = scoreContext,
    )

    /**
     * Whether a title's local copy is picked at its place among the online sources: the local files row is below one
     * of them, its switch is on, and the title has no override of its own.
     */
    fun localFilesAmongSources(contentId: String?, videoId: String?): Boolean {
        val title = LocalLibraryRepository.titleFor(contentId, videoId)
        val override = title?.let { LocalLibraryRepository.titlePlaybackFor(it.key) }
        return override == null && PlaybackSources.currentLocalPlacement() == LocalFilesPick.LAST
    }

    /**
     * Whether the next episode's local copy (a completed download, a library file) plays ahead of every online
     * source: the local files row at the top, or the title set to its local file. Null when it is never used (switched
     * off, or the title set to online); false when it is picked at its place lower down.
     */
    fun nextEpisodeLocalFirst(contentId: String?, videoId: String?): Boolean? {
        val title = LocalLibraryRepository.titleFor(contentId, videoId)
        when (title?.let { LocalLibraryRepository.titlePlaybackFor(it.key) }) {
            LocalTitlePlayback.LOCAL_FILE -> return true
            LocalTitlePlayback.ONLINE_SOURCE -> return null
            null -> Unit
        }
        return when (PlaybackSources.currentLocalPlacement()) {
            LocalFilesPick.FIRST -> true
            LocalFilesPick.LAST -> false
            LocalFilesPick.NEVER -> null
        }
    }

    /**
     * Whether the wait time (Settings > Playback, "Wait for sources") is over the moment a load starts, so any ready
     * stream may play as it arrives: only Instant. Unlimited waits for every source (the callers keep a hard fallback),
     * and a bounded wait lasts its seconds.
     */
    fun waitEndsAtOnce(timeoutSeconds: Int): Boolean = timeoutSeconds <= 0

    /**
     * The pick taken as a source answers. Once the wait is over, the full pick over [streams]. Before, only a pick no
     * later answer can change: the remembered release, or under priority order a stream that every source above it has
     * already answered for. Best score never picks early, since any source still answering may bring a higher score.
     * [streams] are the answered sources' streams; [groups] are all sources in priority order.
     */
    fun pickOnResponse(
        rules: PlaybackPickRules,
        groups: List<AddonStreamGroup>,
        streams: List<StreamItem>,
        waitOver: Boolean,
    ): StreamItem? {
        if (streams.isEmpty()) return null
        if (waitOver) return rules.select(streams)
        return rules.takeIf { it.preferBingeGroup }?.select(streams, bingeGroupOnly = true)
            // Not while a remembered release is sought: a lower source may still bring it.
            ?: rules.takeIf { it.mode == StreamAutoPlayMode.FIRST_STREAM && !it.preferBingeGroup }
                ?.select(streams)
                ?.takeIf { outranksPending(groups, it) }
    }

    /**
     * The streams a pick may choose from. Before the wait is over, only sources whose list is final: a source still
     * answering (a Usenet search with an indexer to go) may yet bring a better stream. Once [waitOver], everything that
     * has arrived, so one slow indexer no longer keeps the Usenet releases already in out of the pick.
     */
    fun answered(groups: List<AddonStreamGroup>, waitOver: Boolean = false): List<StreamItem> =
        (if (waitOver) groups else groups.filterNot { it.isLoading }).flatMap { it.streams }

    /**
     * Whether [stream] may play before the timeout under priority order: every source ranked above it has answered,
     * and none of them still has a debrid link being checked that could turn out ready. [groups] are in priority order.
     */
    fun outranksPending(groups: List<AddonStreamGroup>, stream: StreamItem): Boolean {
        val index = groups.indexOfFirst { group -> group.streams.any { it == stream } }
        if (index < 0) return false
        return groups.take(index).none { group ->
            group.isLoading || group.streams.any { it.isCheckingDebrid() }
        }
    }

    private fun StreamItem.isCheckingDebrid(): Boolean =
        isAddonDebridCandidate && needsLocalDebridResolve &&
            (debridCacheStatus?.state == null || debridCacheStatus.state == StreamDebridCacheState.CHECKING)

    /** One line per automatic pick: what was picked, from where, and which rule chose it. */
    fun logPick(trigger: String, rules: PlaybackPickRules, stream: StreamItem?, candidates: Int) {
        if (stream == null) {
            log.i { "(Pick) $trigger: nothing picked from $candidates streams (mode=${rules.mode})" }
            return
        }
        val label = (stream.behaviorHints.filename ?: stream.streamLabel).take(120)
        log.i { "(Pick) $trigger: ${stream.addonName} '$label', rule: ${rules.reasonFor(stream)} (of $candidates)" }
    }
}
