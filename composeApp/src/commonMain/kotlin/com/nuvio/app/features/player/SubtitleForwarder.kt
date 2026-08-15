package com.nuvio.app.features.player

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
     * Fetches addon subtitles for the given content and keeps the ones matching [targets]. Returns
     * null on failure or timeout for graceful degradation (external player launches without
     * subtitles).
     *
     * [targets] are resolved language codes in priority order — the same list the internal player's
     * automatic selection works from — not raw preference values: "device"/"original" are sentinels
     * that match no track's language, so passing them through would silently forward nothing.
     *
     * The result is de-duplicated and ordered so that the cap above is spent on the tracks most
     * likely to be wanted: earlier targets first, the exact regional variant ahead of a loose
     * match, and the viewer's SDH preference ahead of its opposite.
     */
    suspend fun fetchForExternalPlayer(
        type: String,
        videoId: String,
        targets: List<String>,
        isRejected: (AddonSubtitle) -> Boolean = { false },
        preferHearingImpaired: Boolean = false,
        timeoutMs: Long = 10_000L,
    ): List<SubtitleInput>? {
        if (targets.isEmpty()) return null
        return try {
            withTimeoutOrNull(timeoutMs) {
                // Joining the fetch's own job is what makes this deterministic: the loading flag is
                // published from inside that coroutine, so sampling it races the launch.
                SubtitleRepository.fetchAddonSubtitles(type, videoId).join()

                SubtitleRepository.addonSubtitles.value
                    .mapNotNull { subtitle ->
                        if (isRejected(subtitle)) return@mapNotNull null
                        val targetRank = targets.indexOfFirst { target ->
                            languageMatchesPreference(subtitle.language, target)
                        }
                        if (targetRank < 0) return@mapNotNull null
                        val isExact = targets.any { target ->
                            languageMatchesPreferenceExactly(subtitle.language, target)
                        }
                        val matchesSdhPreference =
                            subtitle.isHearingImpairedSubtitle() == preferHearingImpaired
                        subtitle to intArrayOf(
                            targetRank,
                            if (isExact) 0 else 1,
                            if (matchesSdhPreference) 0 else 1,
                        )
                    }
                    // Stable, so addons' own "best match first" ordering survives inside each tier.
                    .sortedWith(
                        compareBy({ it.second[0] }, { it.second[1] }, { it.second[2] }),
                    )
                    .map { (subtitle, _) -> subtitle }
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
