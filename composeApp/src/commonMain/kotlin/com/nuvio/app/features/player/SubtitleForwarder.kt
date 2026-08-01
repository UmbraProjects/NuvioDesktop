package com.nuvio.app.features.player

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object SubtitleForwarder {

    /**
     * Upper bound on how many subtitle tracks are handed to an external player.
     *
     * A popular episode can return several hundred addon subtitles in a single language, and every
     * downstream consumer degrades badly on that: each track is a separate file the player has to
     * open before playback starts, the command line approaches the Windows 32 KB argument limit,
     * and the resulting track menu is unusable anyway. Addons return their best matches first, so
     * truncating costs little.
     */
    const val MAX_FORWARDED_SUBTITLES = 12

    /**
     * Fetches addon subtitles for the given content and filters them by the user's
     * preferred and secondary language. Returns null on failure or timeout for
     * graceful degradation (external player launches without subtitles).
     *
     * The result is de-duplicated, ordered preferred-language first, and capped at
     * [MAX_FORWARDED_SUBTITLES].
     */
    suspend fun fetchForExternalPlayer(
        type: String,
        videoId: String,
        preferredLanguage: String,
        secondaryLanguage: String?,
        timeoutMs: Long = 10_000L,
    ): List<SubtitleInput>? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                SubtitleRepository.fetchAddonSubtitles(type, videoId)

                // Give the internal coroutine a chance to start and set isLoading = true
                kotlinx.coroutines.delay(50)

                // Wait for loading to complete (isLoading goes from true back to false)
                // If it's already false (fetch completed very quickly or never started), skip waiting
                if (SubtitleRepository.isLoading.value) {
                    SubtitleRepository.isLoading.first { !it }
                }

                val allSubtitles = SubtitleRepository.addonSubtitles.value

                val filtered = allSubtitles.filter { subtitle ->
                    languageMatchesPreference(subtitle.language, preferredLanguage) ||
                        (secondaryLanguage != null &&
                            languageMatchesPreference(subtitle.language, secondaryLanguage))
                }

                filtered
                    // Preferred language first, so the cap below never spends its budget on
                    // secondary-language tracks while preferred ones go undelivered.
                    .sortedBy { subtitle ->
                        if (languageMatchesPreference(subtitle.language, preferredLanguage)) 0 else 1
                    }
                    .distinctBy { it.url }
                    .take(MAX_FORWARDED_SUBTITLES)
                    .map { subtitle ->
                        SubtitleInput(
                            url = subtitle.url,
                            name = subtitle.display,
                            lang = subtitle.language,
                        )
                    }
            }
        } catch (_: Exception) {
            null
        }
    }
}
