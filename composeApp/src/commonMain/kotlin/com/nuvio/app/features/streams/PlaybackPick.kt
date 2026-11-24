package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.debrid.DebridSettings
import com.nuvio.app.features.player.PlayerSettingsUiState

/**
 * Everything one automatic stream pick needs, gathered once per load so every pick made for that load (the early
 * binge-group pick, the timeout pick, the per-response pick, the final pick) asks exactly the same question.
 *
 * Step 1 of `Docs/plans/playback-priority.md`: this wraps today's settings and [StreamAutoPlaySelector] without
 * changing what plays. The later steps swap the settings behind it for the source priority list.
 */
data class PlaybackPickRules(
    val mode: StreamAutoPlayMode,
    val regex: String = "",
    val source: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
    val installedAddonNames: Set<String> = emptySet(),
    val selectedAddons: Set<String> = emptySet(),
    val selectedPlugins: Set<String> = emptySet(),
    /** The binge group to try first, or null when binge affinity does not apply to this pick. */
    val bingeGroup: String? = null,
    /** Within [bingeGroup], the release watched last. */
    val release: BingeReleaseHint? = null,
    val preferBingeGroup: Boolean = bingeGroup != null,
    val debridEnabled: Boolean = true,
    val activeResolverProviderId: String? = null,
    val scoreProfile: StreamScoreProfile = StreamScoreProfile(),
    val scoreContext: StreamScoreContext = StreamScoreContext.MOVIE,
) {
    fun evaluate(streams: List<StreamItem>, bingeGroupOnly: Boolean = false): StreamAutoPlayEvaluation =
        StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regex,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = bingeGroup,
            preferBingeGroupInSelection = preferBingeGroup,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
            scoreProfile = scoreProfile,
            scoreContext = scoreContext,
            preferredRelease = release,
        )

    fun select(streams: List<StreamItem>, bingeGroupOnly: Boolean = false): StreamItem? =
        evaluate(streams, bingeGroupOnly).stream

    /**
     * The next episode's second try when the configured pick finds nothing: the first stream the source scope
     * allows, with the regex and the score profile dropped (binge affinity kept).
     */
    fun firstStreamFallback(): PlaybackPickRules = copy(
        mode = StreamAutoPlayMode.FIRST_STREAM,
        regex = "",
        scoreProfile = StreamScoreProfile(),
        scoreContext = StreamScoreContext.MOVIE,
    )

    /** Which rule chose [stream], for the pick log. */
    fun reasonFor(stream: StreamItem): String {
        val group = bingeGroup?.trim().orEmpty()
        if (preferBingeGroup && group.isNotEmpty() && stream.behaviorHints.bingeGroup == group) {
            return if (release != null && release.rank(stream) < 2) "same release" else "same binge group"
        }
        if (stream.serverTarget != null) return "media server"
        return when (mode) {
            StreamAutoPlayMode.MANUAL -> "manual"
            StreamAutoPlayMode.FIRST_STREAM -> "first stream"
            StreamAutoPlayMode.REGEX_MATCH -> "regex"
            StreamAutoPlayMode.SCORED -> if (scoreProfile.appliesToFirstStream()) "best score" else "first stream"
        }
    }
}

/** A first click's pick, decided before the providers answer; [rules] needs the installed addons, known later. */
data class FirstPlayPick(
    val mode: StreamAutoPlayMode,
    val isAutoPlayEnabled: Boolean,
    val bingeGroup: String?,
    val release: BingeReleaseHint?,
    private val settings: PlayerSettingsUiState,
    private val debridEnabled: Boolean,
    private val activeResolverProviderId: String?,
    private val scoreProfile: StreamScoreProfile,
    private val scoreContext: StreamScoreContext,
) {
    fun rules(installedAddonNames: Set<String>): PlaybackPickRules = PlaybackPickRules(
        mode = mode,
        regex = settings.streamAutoPlayRegex,
        source = settings.streamAutoPlaySource,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.streamAutoPlaySelectedAddons,
        selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        bingeGroup = bingeGroup,
        release = release,
        preferBingeGroup = bingeGroup != null,
        debridEnabled = debridEnabled,
        activeResolverProviderId = activeResolverProviderId,
        scoreProfile = scoreProfile,
        scoreContext = scoreContext,
    )
}

object PlaybackPick {
    private val log = Logger.withTag("PlaybackPick")

    /**
     * The rules a first click (Details, Home, Continue Watching, Search) picks by.
     *
     * - Scoring turns any automatic mode into a best-score pick ([StreamAutoPlayPolicy.effectiveMode]).
     * - A play armed by Live TV picks even when the mode says Manual ([StreamAutoPlayOverride]).
     * - The binge group remembered for the show applies only with both Prefer and Reuse binge group on, and not when
     *   the score profile overrides binge affinity.
     */
    fun firstPlay(
        settings: PlayerSettingsUiState,
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
        val regexConfigured = StreamAutoPlayPolicy.isRegexSelectionConfigured(settings.streamAutoPlayRegex)
        val mode = StreamAutoPlayPolicy.effectiveMode(settings.streamAutoPlayMode, scoreProfile).let { mode ->
            if (forcedAuto) StreamAutoPlayOverride.modeFor(mode, regexConfigured) else mode
        }
        val isAutoPlayEnabled = !manualSelection && mode != StreamAutoPlayMode.MANUAL &&
            !(mode == StreamAutoPlayMode.REGEX_MATCH && !regexConfigured)
        val bingeGroup = if (
            settings.streamAutoPlayPreferBingeGroup &&
            settings.streamAutoPlayReuseBingeGroup &&
            !StreamAutoPlayPolicy.scoreOverridesBingeGroup(mode, scoreProfile)
        ) {
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
     * The rules the instant-playback debrid preparer uses to choose which debrid rows to resolve first. It reads the
     * configured mode as is (no scoring promotion) and no binge group, as it always has.
     */
    fun debridPreparation(
        settings: PlayerSettingsUiState,
        installedAddonNames: Set<String>,
        scoreProfile: StreamScoreProfile,
        scoreContext: StreamScoreContext,
    ): PlaybackPickRules = PlaybackPickRules(
        mode = settings.streamAutoPlayMode,
        regex = settings.streamAutoPlayRegex,
        source = settings.streamAutoPlaySource,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.streamAutoPlaySelectedAddons,
        selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        scoreProfile = scoreProfile,
        scoreContext = scoreContext,
    )

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
