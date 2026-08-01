package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.enabledAddons

internal fun buildAddonSubtitleFetchKey(
    addons: List<ManagedAddon>,
    type: String?,
    videoId: String?,
): String? {
    val normalizedType = type?.takeIf { it.isNotBlank() } ?: return null
    val normalizedVideoId = videoId?.takeIf { it.isNotBlank() } ?: return null
    val compatibleSubtitleAddons = addons.enabledAddons().mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        val supportsSubtitles = manifest.resources.any { resource ->
            resource.isCompatibleSubtitleResource(
                type = normalizedType,
                videoId = normalizedVideoId,
            )
        }
        if (!supportsSubtitles) return@mapNotNull null
        "${manifest.id}:${manifest.transportUrl}"
    }

    if (compatibleSubtitleAddons.isEmpty()) return null
    return buildString {
        append(normalizedType)
        append('|')
        append(normalizedVideoId)
        append('|')
        append(compatibleSubtitleAddons.sorted().joinToString("|"))
    }
}

internal fun AddonResource.isCompatibleSubtitleResource(type: String, videoId: String): Boolean {
    val isSubtitleResource = name.equals("subtitles", ignoreCase = true) ||
        name.equals("subtitle", ignoreCase = true)
    if (!isSubtitleResource) return false

    val requestType = if (type.equals("tv", ignoreCase = true)) "series" else type
    val typeMatches = types.isEmpty() || types.any { it.equals(requestType, ignoreCase = true) }
    if (!typeMatches) return false

    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

/**
 * [isRejected] skips candidates without removing them from [tracks]. The list must stay intact
 * because callers read the returned value as a track index as well as a list position — filtering
 * the input would shift one against the other.
 */
internal fun <T> findPreferredTrackIndex(
    tracks: List<T>,
    targets: List<String>,
    language: (T) -> String?,
    isRejected: (T) -> Boolean = { false },
): Int {
    if (targets.isEmpty()) return -1
    for (target in targets) {
        val matchIndex = tracks.indexOfFirst { track ->
            !isRejected(track) &&
                languageMatchesPreference(
                    trackLanguage = language(track),
                    targetLanguage = target,
                )
        }
        if (matchIndex >= 0) {
            return matchIndex
        }
    }
    return -1
}

/** See [findPreferredTrackIndex] for why [isRejected] is a predicate rather than a pre-filter. */
internal fun findPreferredSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    targets: List<String>,
    isRejected: (SubtitleTrack) -> Boolean = { false },
): Int {
    if (targets.isEmpty()) return -1

    for ((targetPosition, target) in targets.withIndex()) {
        val normalizedTarget = normalizeLanguageCode(target) ?: continue
        if (normalizedTarget == SubtitleLanguageOption.FORCED) {
            val forcedIndex = tracks.indexOfFirst { it.isForced && !isRejected(it) }
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }

        val matchIndex = tracks.indexOfFirst { track ->
            !isRejected(track) &&
                languageMatchesPreference(
                    trackLanguage = track.language,
                    targetLanguage = normalizedTarget,
                )
        }
        if (matchIndex >= 0) return matchIndex
    }

    return -1
}

/**
 * The subtitle rejections that actually apply.
 *
 * "Use Forced Subtitles" is an explicit request for forced tracks, so it overrides a blanket
 * rejection of them rather than cancelling out with it and leaving no subtitles at all.
 */
internal fun PlayerSettingsUiState.effectiveRejectedSubtitleKeywords(): Set<SubtitleRejectKeyword> =
    if (subtitleStyle.useForcedSubtitles) {
        rejectedSubtitleKeywords - SubtitleRejectKeyword.FORCED
    } else {
        rejectedSubtitleKeywords
    }

internal fun PlayerSettingsUiState.rejectsSubtitleTrack(track: SubtitleTrack): Boolean =
    effectiveRejectedSubtitleKeywords().rejectsSubtitleTrack(track)

internal fun PlayerSettingsUiState.rejectsAddonSubtitle(subtitle: AddonSubtitle): Boolean =
    effectiveRejectedSubtitleKeywords().rejectsAddonSubtitle(subtitle)

internal fun PlayerSettingsUiState.rejectsAudioTrack(track: AudioTrack): Boolean =
    rejectedAudioKeywords.rejectsAudioTrack(track)

internal fun filterAddonSubtitlesForSettings(
    subtitles: List<AddonSubtitle>,
    settings: PlayerSettingsUiState,
    selectedAddonSubtitleId: String?,
): List<AddonSubtitle> {
    fun isSelected(subtitle: AddonSubtitle) =
        subtitle.id == selectedAddonSubtitleId || subtitle.url == selectedAddonSubtitleId

    val shouldFilter = settings.subtitleStyle.showOnlyPreferredLanguages ||
        settings.addonSubtitleStartupMode == AddonSubtitleStartupMode.PREFERRED_ONLY
    if (!shouldFilter) {
        return subtitles.filter { isSelected(it) || !settings.rejectsAddonSubtitle(it) }
    }

    val targets = preferredSubtitleTargetsForSettings(settings)
    if (targets.isEmpty()) {
        return subtitles.filter { isSelected(it) }
    }

    val filtered = subtitles.filter { subtitle ->
        isSelected(subtitle) ||
            (
                !settings.rejectsAddonSubtitle(subtitle) &&
                    targets.any { target ->
                        languageMatchesPreference(
                            trackLanguage = subtitle.language,
                            targetLanguage = target,
                        )
                    }
                )
    }
    return filtered
}

/**
 * Drops audio tracks the viewer rejected by keyword (commentary, audio description). The current
 * selection is always kept so an option turned on mid-playback cannot hide the track that is
 * playing, and the list is never emptied — if every track is rejected the rejection is the
 * mis-detection, and showing them all is the recoverable outcome.
 */
internal fun filterAudioTracksForSettings(
    tracks: List<AudioTrack>,
    settings: PlayerSettingsUiState,
    selectedIndex: Int,
): List<AudioTrack> {
    if (settings.rejectedAudioKeywords.isEmpty()) return tracks
    val filtered = tracks.filter { track ->
        track.index == selectedIndex || track.isSelected || !settings.rejectsAudioTrack(track)
    }
    return filtered.ifEmpty { tracks }
}

/**
 * Applies the "Show Only Preferred Languages" setting and the subtitle keyword rejections to the
 * built-in (embedded) subtitle list shown in the player. Mirrors [filterAddonSubtitlesForSettings]
 * but for on-disc tracks:
 *  - The currently selected track is always kept, so the user is never locked out of their choice.
 *  - Forced tracks are kept while forced-subtitle mode is on, since that is a preference in itself.
 *  - When no preferred languages resolve at all the language filter is skipped — hiding every
 *    embedded track on an ill-defined filter would be worse than showing them — but the keyword
 *    rejections still apply, since those name a track kind rather than a language.
 * This is a display-only concern; automatic track selection applies the rejections separately.
 */
internal fun filterBuiltInSubtitlesForSettings(
    tracks: List<SubtitleTrack>,
    settings: PlayerSettingsUiState,
    selectedIndex: Int,
): List<SubtitleTrack> {
    val rejected = settings.effectiveRejectedSubtitleKeywords()
    if (!settings.subtitleStyle.showOnlyPreferredLanguages && rejected.isEmpty()) return tracks

    fun isKept(track: SubtitleTrack) =
        track.index == selectedIndex || !rejected.rejectsSubtitleTrack(track)

    if (!settings.subtitleStyle.showOnlyPreferredLanguages) return tracks.filter(::isKept)

    val targets = preferredSubtitleTargetsForSettings(settings)
    if (targets.isEmpty()) return tracks.filter(::isKept)

    return tracks.filter { track ->
        track.index == selectedIndex ||
            (
                !rejected.rejectsSubtitleTrack(track) &&
                    (
                        (settings.subtitleStyle.useForcedSubtitles && track.isForced) ||
                            targets.any { target ->
                                languageMatchesPreference(
                                    trackLanguage = track.language,
                                    targetLanguage = target,
                                )
                            }
                        )
                )
    }
}

/**
 * [originalLanguage] defaults to whatever is playing now ([OriginalLanguageCache.current]) because
 * every caller of this overload — the two list filters above, and mpv's `slang` options — is about
 * the active title but has no meta id in hand to name it with.
 */
internal fun preferredSubtitleTargetsForSettings(
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
): List<String> {
    val preferredLanguage = if (settings.subtitleStyle.useForcedSubtitles) {
        SubtitleLanguageOption.FORCED
    } else {
        settings.preferredSubtitleLanguage
    }
    return resolvePreferredSubtitleLanguageTargets(
        preferredSubtitleLanguage = preferredLanguage,
        secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage,
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        originalLanguage = originalLanguage,
    ).filterNot { it == SubtitleLanguageOption.FORCED }
}

/**
 * Whether any of the four language preferences is set to "Original", and therefore whether it is
 * worth fetching a meta purely to learn this title's own language.
 */
internal fun PlayerSettingsUiState.usesOriginalLanguagePreference(): Boolean = listOf(
    preferredAudioLanguage,
    secondaryPreferredAudioLanguage,
    preferredSubtitleLanguage,
    secondaryPreferredSubtitleLanguage,
).any { it?.equals(ORIGINAL_LANGUAGE_OPTION, ignoreCase = true) == true }

internal fun findPersistedAudioTrackIndex(
    tracks: List<AudioTrack>,
    preference: PersistedPlayerTrackPreference,
): Int {
    // Track ids are usually positional and can identify a different language in the next episode.
    // Match stable language/name metadata first; use the id only for legacy entries without it.
    preference.audioLanguage?.takeIf { it.isNotBlank() }?.let { language ->
        val languageTracks = tracks.filter { languageMatchesPreference(it.language, language) }
        preference.audioName?.takeIf { it.isNotBlank() }?.let { name ->
            languageTracks.firstOrNull { it.label.equals(name, ignoreCase = true) }?.let { return it.index }
        }
        languageTracks.firstOrNull()?.let { return it.index }
    }
    preference.audioName?.takeIf { it.isNotBlank() }?.let { name ->
        tracks.firstOrNull { it.label.equals(name, ignoreCase = true) }?.let { return it.index }
    }
    preference.audioTrackId?.takeIf { it.isNotBlank() }?.let { trackId ->
        tracks.firstOrNull { it.id == trackId }?.let { return it.index }
    }
    return -1
}

internal fun findPersistedSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    preference: PersistedPlayerTrackPreference,
): Int {
    preference.subtitleLanguage?.takeIf { it.isNotBlank() }?.let { language ->
        val languageTracks = tracks.filter { languageMatchesPreference(it.language, language) }
        preference.subtitleName?.takeIf { it.isNotBlank() }?.let { name ->
            languageTracks.firstOrNull { it.label.equals(name, ignoreCase = true) }?.let { return it.index }
        }
        languageTracks.firstOrNull()?.let { return it.index }
    }
    preference.subtitleName?.takeIf { it.isNotBlank() }?.let { name ->
        tracks.firstOrNull { it.label.equals(name, ignoreCase = true) }?.let { return it.index }
    }
    preference.subtitleTrackId?.takeIf { it.isNotBlank() }?.let { trackId ->
        tracks.firstOrNull { it.id == trackId }?.let { return it.index }
    }
    return -1
}
