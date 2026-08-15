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

    // Canonicalize both sides, as the fetch itself does. An addon declaring "tv" rather than
    // "series" used to be invisible here while still being requested, which left the gating logic
    // believing no addon subtitles were coming for it.
    val requestType = canonicalSubtitleRequestType(type)
    val typeMatches = types.isEmpty() ||
        types.any { canonicalSubtitleRequestType(it).equals(requestType, ignoreCase = true) }
    if (!typeMatches) return false

    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

internal fun canonicalSubtitleRequestType(type: String): String =
    if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

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
        // Exact regional match first, then the loose one — see [languageMatchesPreferenceExactly].
        for (exactOnly in listOf(true, false)) {
            val matchIndex = tracks.indexOfFirst { track ->
                !isRejected(track) &&
                    languageMatches(
                        trackLanguage = language(track),
                        targetLanguage = target,
                        exactOnly = exactOnly,
                    )
            }
            if (matchIndex >= 0) {
                return matchIndex
            }
        }
    }
    return -1
}

private fun languageMatches(trackLanguage: String?, targetLanguage: String, exactOnly: Boolean): Boolean =
    if (exactOnly) {
        languageMatchesPreferenceExactly(trackLanguage, targetLanguage)
    } else {
        languageMatchesPreference(trackLanguage, targetLanguage)
    }

/** See [findPreferredTrackIndex] for why [isRejected] is a predicate rather than a pre-filter. */
internal fun findPreferredSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    targets: List<String>,
    isRejected: (SubtitleTrack) -> Boolean = { false },
    preferHearingImpaired: Boolean = false,
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

        fun candidates(exactOnly: Boolean) = tracks.withIndex().filter { (_, track) ->
            !isRejected(track) &&
                languageMatches(
                    trackLanguage = track.language,
                    targetLanguage = normalizedTarget,
                    exactOnly = exactOnly,
                )
        }
        val matchingTracks = candidates(exactOnly = true).ifEmpty { candidates(exactOnly = false) }
        // The SDH preference cuts both ways: releases routinely list the SDH track first, so taking
        // the list order when the option is off hands captions to someone who did not ask for them.
        val match = if (preferHearingImpaired) {
            matchingTracks.firstOrNull { (_, track) -> track.isHearingImpairedSubtitle() }
                ?: matchingTracks.firstOrNull()
        } else {
            matchingTracks.firstOrNull { (_, track) -> !track.isHearingImpairedSubtitle() }
                ?: matchingTracks.firstOrNull()
        }
        if (match != null) return match.index
    }

    return -1
}

/**
 * The addon subtitle to use for [targets], applying the same tie-breaks as the built-in pass:
 * targets in priority order, the exact regional variant before the loose one, and the SDH
 * preference in whichever direction the viewer set it.
 */
internal fun findPreferredAddonSubtitle(
    subtitles: List<AddonSubtitle>,
    targets: List<String>,
    isRejected: (AddonSubtitle) -> Boolean = { false },
    preferHearingImpaired: Boolean = false,
): AddonSubtitle? {
    for (target in targets) {
        fun candidates(exactOnly: Boolean) = subtitles.filter { subtitle ->
            !isRejected(subtitle) &&
                languageMatches(
                    trackLanguage = subtitle.language,
                    targetLanguage = target,
                    exactOnly = exactOnly,
                )
        }
        val matching = candidates(exactOnly = true).ifEmpty { candidates(exactOnly = false) }
        val match = if (preferHearingImpaired) {
            matching.firstOrNull(AddonSubtitle::isHearingImpairedSubtitle) ?: matching.firstOrNull()
        } else {
            matching.firstOrNull { !it.isHearingImpairedSubtitle() } ?: matching.firstOrNull()
        }
        if (match != null) return match
    }
    return null
}

private val HearingImpairedSubtitlePhrase = Regex(
    "(?:^|[^a-z0-9])(?:sdh|cc|hoh|closed[ _.-]*captions?|hearing[ _.-]*impaired|deaf)(?:$|[^a-z0-9])",
    RegexOption.IGNORE_CASE,
)

/**
 * Release names that say they are *not* captioned, which the phrase above would otherwise read the
 * wrong way round: "Blackout.DVDRip.NonHI.cc.srt" carries a "cc" that only exists to be negated.
 */
private val NotHearingImpairedSubtitlePhrase = Regex(
    "(?:^|[^a-z0-9])(?:non?)[ _.-]*(?:sdh|cc|hi|hoh)(?:$|[^a-z0-9])",
    RegexOption.IGNORE_CASE,
)

internal fun subtitleLooksHearingImpaired(vararg values: String?): Boolean {
    if (values.any { value -> NotHearingImpairedSubtitlePhrase.containsMatchIn(value.orEmpty()) }) {
        return false
    }
    return values.any { value -> HearingImpairedSubtitlePhrase.containsMatchIn(value.orEmpty()) }
}

internal fun SubtitleTrack.isHearingImpairedSubtitle(): Boolean =
    subtitleLooksHearingImpaired(label, id)

internal fun AddonSubtitle.isHearingImpairedSubtitle(): Boolean =
    subtitleLooksHearingImpaired(display, id)

/**
 * The addon subtitle a saved selection refers to, or null when nothing identifies one.
 *
 * Two different questions, answered in that order. Replaying the *same* video, the persisted id and
 * URL name one specific result, so an exact hit settles it. Across episodes those change every time
 * and only the descriptive fields carry over, so identity becomes the compound of everything that
 * does not change — language, display name, addon, SDH status — scored together.
 *
 * Scored rather than checked field by field: display names are not unique ("English" is every
 * addon's most common label), so taking the first name hit picked by list order, and a bare
 * addon-name hit used to win before the exact persisted id was ever consulted. A tie means the saved
 * selection genuinely does not identify one result, which the preferred-language fallback handles
 * better than an arbitrary pick.
 */
internal fun findPersistedAddonSubtitle(
    subtitles: List<AddonSubtitle>,
    preference: PersistedPlayerTrackPreference,
): AddonSubtitle? {
    if (subtitles.isEmpty()) return null
    val languageMatches = preference.subtitleLanguage
        ?.takeIf(String::isNotBlank)
        ?.let { language -> subtitles.filter { languageMatchesPreference(it.language, language) } }
        .orEmpty()
    val candidates = languageMatches.ifEmpty { subtitles }

    preference.addonSubtitleId?.takeIf(String::isNotBlank)?.let { id ->
        candidates.singleOrNull { it.id == id }?.let { return it }
    }
    preference.addonSubtitleUrl?.takeIf(String::isNotBlank)?.let { url ->
        candidates.singleOrNull { it.url == url }?.let { return it }
    }

    val wantedName = preference.subtitleName?.takeIf(String::isNotBlank)
    val wantedAddonName = preference.addonSubtitleAddonName?.takeIf(String::isNotBlank)
    if (wantedName == null && wantedAddonName == null) return languageMatches.firstOrNull()
    val wantedHearingImpaired = wantedName != null && subtitleLooksHearingImpaired(wantedName)

    val scored = candidates.map { subtitle ->
        var score = 0
        if (wantedName != null && subtitle.display.equals(wantedName, ignoreCase = true)) score += 4
        if (wantedAddonName != null && subtitle.addonName.equals(wantedAddonName, ignoreCase = true)) score += 2
        if (subtitle.isHearingImpairedSubtitle() == wantedHearingImpaired) score += 1
        score to subtitle
    }
    val best = scored.maxOf { (score, _) -> score }
    // A lone SDH agreement (1) says nothing about which result this is — every plain track scores it.
    if (best <= 1) return languageMatches.firstOrNull()
    return scored.filter { (score, _) -> score == best }
        .singleOrNull()
        ?.second
        ?: languageMatches.firstOrNull()
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
    // No preferred language resolves — "None" with no secondary, forced-only mode, an unresolved
    // "Original". Skip the language filter exactly as [filterBuiltInSubtitlesForSettings] does:
    // hiding every fetched subtitle on an ill-defined filter reads as a broken list, and the
    // keyword rejections still apply because those name a track kind rather than a language.
    if (targets.isEmpty()) {
        return subtitles.filter { isSelected(it) || !settings.rejectsAddonSubtitle(it) }
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
 *
 * [includeSecondary] is what tells the two kinds of caller apart while dual subtitles are on. The
 * list filters want the secondary language included, or the track the viewer wants *as* the
 * secondary is hidden from them; whoever is choosing the single primary track must leave it out,
 * because that language is already spoken for.
 */
internal fun preferredSubtitleTargetsForSettings(
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
    includeSecondary: Boolean = true,
): List<String> {
    val preferredLanguage = if (settings.subtitleStyle.useForcedSubtitles) {
        SubtitleLanguageOption.FORCED
    } else {
        settings.preferredSubtitleLanguage
    }
    return resolvePreferredSubtitleLanguageTargets(
        preferredSubtitleLanguage = preferredLanguage,
        secondaryPreferredSubtitleLanguage = settings.secondaryPreferredSubtitleLanguage
            .takeIf { includeSecondary },
        deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        originalLanguage = originalLanguage,
    ).filterNot { it == SubtitleLanguageOption.FORCED }
}

/**
 * The concrete language the dual-subtitle secondary track should use, or null when the preference
 * names none.
 *
 * The secondary picker offers the same sentinels the primary one does, so "Original" has to be
 * resolved against the title exactly as it is for the primary preference — comparing the literal
 * string "original" against track languages matches nothing and turns the whole feature into a
 * silent no-op. FORCED is deliberately passed through: [findPreferredSubtitleTrackIndex] knows how
 * to answer it.
 */
internal fun resolveSecondarySubtitleLanguage(
    language: String?,
    originalLanguage: String?,
    deviceLanguages: List<String>,
): String? = when (val normalized = normalizeLanguageCode(language)) {
    null,
    SubtitleLanguageOption.NONE,
    AudioLanguageOption.DEFAULT,
    -> null
    ORIGINAL_LANGUAGE_OPTION -> normalizeLanguageCode(originalLanguage)
    SubtitleLanguageOption.DEVICE -> deviceLanguages.firstNotNullOfOrNull(::normalizeLanguageCode)
    else -> normalized
}

/** The languages automatic selection may choose the single primary subtitle track from. */
internal fun primarySubtitleTargetsForSettings(
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
): List<String> = preferredSubtitleTargetsForSettings(
    settings = settings,
    originalLanguage = originalLanguage,
    includeSecondary = !settings.dualSubtitlesEnabled,
)

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
