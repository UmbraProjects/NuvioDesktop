package com.nuvio.app.features.streams

import com.nuvio.app.features.player.AnimeContentCache
import com.nuvio.app.features.player.DeviceLanguagePreferences
import com.nuvio.app.features.player.OriginalLanguageCache
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.resolvePreferredAudioLanguageTargets

/**
 * Builds a [StreamScoreContext] from live app settings.
 *
 * Kept apart from [StreamScoreContext] itself so the data class and [StreamScorer] stay pure and
 * testable — tests construct the context directly. Only this factory reads repositories.
 */
object StreamScoreContexts {

    /**
     * Context for scoring something the user is about to watch or download.
     *
     * Audio language preference is read from Playback settings rather than stored on the score
     * profile: it is already configured there, and a second copy would be two places to set one
     * thing with no way to tell which one is winning.
     *
     * [contentId] and [contentType] are what identify animation, reusing the same detection that
     * decides whether to auto-apply the anime enhancement preset. Both are optional: a caller with
     * no id in hand simply scores against the live-action size floors, which is the old behaviour.
     */
    fun forPlayback(
        isEpisode: Boolean,
        runtimeMinutes: Int? = null,
        contentId: String? = null,
        contentType: String? = null,
    ): StreamScoreContext {
        val settings = runCatching {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState.value
        }.getOrNull()
        val languages = settings?.let {
            runCatching {
                resolvePreferredAudioLanguageTargets(
                    preferredAudioLanguage = it.preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = it.secondaryPreferredAudioLanguage,
                    deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
                    // Scoring runs before playback, so the cache is read by id rather than through
                    // its "currently playing" slot, which still points at the previous title.
                    originalLanguage = OriginalLanguageCache.languageFor(contentId),
                )
            }.getOrNull()
        }.orEmpty()
        return StreamScoreContext(
            isEpisode = isEpisode,
            runtimeMinutes = runtimeMinutes,
            preferredLanguages = languages,
            isAnimation = isAnimationContent(contentId = contentId, contentType = contentType),
        )
    }

    /**
     * Animation — anime and Western cartoons alike — is detected exactly the way the player detects
     * it for Anime4K/SVP: an anime-native id namespace, an explicit "anime" content type, or the
     * genre snapshot [AnimeContentCache] captured when the detail screen loaded.
     */
    private fun isAnimationContent(contentId: String?, contentType: String?): Boolean {
        if (contentType?.trim()?.equals("anime", ignoreCase = true) == true) return true
        return runCatching { AnimeContentCache.isAnime(contentId) }.getOrDefault(false)
    }
}
