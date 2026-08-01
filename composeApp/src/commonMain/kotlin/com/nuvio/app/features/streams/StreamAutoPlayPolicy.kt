package com.nuvio.app.features.streams

import com.nuvio.app.features.player.PlayerSettingsUiState

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(settings: PlayerSettingsUiState): Boolean {
        if (settings.streamReuseLastLinkEnabled) return true
        if (settings.streamAutoPlayReuseBingeGroup && settings.streamAutoPlayPreferBingeGroup) return true

        return when (settings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(settings.streamAutoPlayRegex)
            // Scoring always yields a pick — an inactive profile just leaves the order alone — so
            // this is enabled whenever it is selected, exactly like FIRST_STREAM.
            StreamAutoPlayMode.SCORED -> true
        }
    }

    /**
     * The mode actually used for selection, after stream scoring has its say.
     *
     * "Apply scoring to → choosing the first stream" means: whenever the app picks a source for you,
     * pick the highest-scoring one. Without this, that toggle did nothing on its own — you also had
     * to find and set Playback → stream selection to "Auto-play best score", which is two switches
     * in two pages for one outcome and reads as a bug.
     *
     * MANUAL is left alone deliberately: it means "never pick for me", and scoring has no business
     * overriding that.
     */
    fun effectiveMode(
        configured: StreamAutoPlayMode,
        scoreProfile: StreamScoreProfile,
    ): StreamAutoPlayMode =
        if (configured != StreamAutoPlayMode.MANUAL && scoreProfile.appliesToFirstStream()) {
            StreamAutoPlayMode.SCORED
        } else {
            configured
        }

    /**
     * Whether a score-based pick replaces binge-group affinity outright.
     *
     * Binge preference is otherwise absolute: a matching `behaviorHints.bingeGroup` goes to the head
     * of the candidate list ahead of the scored order, so the addon's grouping decides and the
     * profile is only consulted when nothing matches. This makes that a user choice.
     *
     * Gated on [mode] already being SCORED rather than on the profile alone, so it only fires where
     * scoring is genuinely making the pick. MANUAL therefore never overrides: a persisted binge group
     * is the only thing that auto-plays in that mode, and scoring has no pick to contribute.
     */
    fun scoreOverridesBingeGroup(
        mode: StreamAutoPlayMode,
        scoreProfile: StreamScoreProfile,
    ): Boolean = mode == StreamAutoPlayMode.SCORED && scoreProfile.appliesToBingeGroupOverride()

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
