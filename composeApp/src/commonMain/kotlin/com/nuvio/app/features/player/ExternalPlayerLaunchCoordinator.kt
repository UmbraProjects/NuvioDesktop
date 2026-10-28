package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/**
 * The languages an external player launch should be handed subtitles for, in priority order.
 *
 * A preferred language of "None" (the default) means "don't turn subtitles on in the internal
 * player", not "never want subtitles" — someone who switched forwarding on wants some. With no
 * language set at all, forwarding falls back to the device languages rather than silently
 * passing nothing.
 */
fun externalPlayerSubtitleTargets(
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
    deviceLanguages: () -> List<String> = DeviceLanguagePreferences::preferredLanguageCodes,
): List<String> = preferredSubtitleTargetsForSettings(
    settings = settings,
    originalLanguage = originalLanguage,
).ifEmpty {
    deviceLanguages().mapNotNull(::normalizeLanguageCode).distinct()
}

/**
 * Orchestrates the full external player launch flow:
 * fetches subtitles if forwarding is enabled, downloads them to local cache,
 * then returns an enriched request for the caller to dispatch.
 *
 * [loadedSubtitles] is the in-player hand-off's already-fetched addon list; when it is non-empty
 * it is filtered instead of asking the addons again. [pinnedSubtitle] (the track the viewer had on)
 * goes first whatever the language settings say, so the external player opens on it.
 */
suspend fun prepareExternalPlayerLaunch(
    request: ExternalPlayerPlaybackRequest,
    type: String,
    videoId: String,
    forwardSubtitles: Boolean,
    settings: PlayerSettingsUiState,
    originalLanguage: String? = OriginalLanguageCache.current,
    loadedSubtitles: List<AddonSubtitle>? = null,
    pinnedSubtitle: AddonSubtitle? = null,
    onOverlayMessage: (String?) -> Unit,
): ExternalPlayerPlaybackRequest {
    val targets = if (forwardSubtitles) externalPlayerSubtitleTargets(settings, originalLanguage) else emptyList()
    // The keyword rejections name a track kind, not a language, so they apply here exactly as
    // they do to the internal player's own lists.
    val isRejected = { subtitle: AddonSubtitle -> settings.rejectsAddonSubtitle(subtitle) }

    val forwarded = when {
        targets.isEmpty() -> null
        !loadedSubtitles.isNullOrEmpty() -> SubtitleForwarder.selectForExternalPlayer(
            subtitles = loadedSubtitles,
            targets = targets,
            isRejected = isRejected,
            trackKind = settings.preferredSubtitleTrackKind,
        )
        type.isBlank() || videoId.isBlank() -> null
        else -> {
            onOverlayMessage(localizedString(Res.string.player_external_loading_subtitles_from_addons))
            SubtitleForwarder.fetchForExternalPlayer(
                type = type,
                videoId = videoId,
                targets = targets,
                isRejected = isRejected,
                trackKind = settings.preferredSubtitleTrackKind,
            )
        }
    }

    val subtitles = (listOfNotNull(pinnedSubtitle?.toSubtitleInput()) + forwarded.orEmpty())
        .distinctBy { it.url }
        .take(SubtitleForwarder.MAX_FORWARDED_SUBTITLES)
    if (subtitles.isEmpty()) return request

    onOverlayMessage(localizedString(Res.string.player_external_downloading_subtitles))
    // Players such as MPC-HC and PotPlayer only take subtitle paths; fall back to the original
    // URLs if caching fails.
    val cachedSubtitles = SubtitleCacheProvider.cacheForExternalPlayer(subtitles)
    return request.copy(subtitles = cachedSubtitles ?: subtitles)
}
