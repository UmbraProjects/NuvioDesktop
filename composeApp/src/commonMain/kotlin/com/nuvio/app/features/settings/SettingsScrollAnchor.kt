package com.nuvio.app.features.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val SettingsScrollAnchorHighlightMillis = 5000L
private const val SettingsScrollAnchorFallbackDelayMillis = 120L

/**
 * Lets a deep-link (e.g. from the Fork Enhancements overview, the settings search, or a pinned
 * favorite) request that a specific setting be scrolled into view when its page opens. An element
 * marks itself with [settingsScrollAnchor]; the navigation requests an anchor id, and the matching
 * element brings itself into view. To highlight itself it briefly tints its own label with the
 * accent colour (via [rememberSettingsAnchorHighlight]) rather than drawing a box around itself,
 * so the cue never overlaps neighbouring rows or card corners.
 */
internal object SettingsScrollAnchor {
    const val DisplayMode = "display_mode"
    const val AdaptiveHeroPosition = "adaptive_hero_position"
    const val AdaptiveHeroHeight = "adaptive_hero_height"
    const val HeroBadgeCount = "hero_badge_count"
    const val HeroBadgePosition = "hero_badge_position"
    const val HeroBadgeSize = "hero_badge_size"
    const val HeroBadgePriority = "hero_badge_priority"
    const val HeroReleaseStatus = "hero_release_status"
    const val AutoPlayTrailer = "auto_play_trailer"
    const val TrailerDelay = "trailer_delay"
    const val TrailerSound = "trailer_sound"
    const val TrailerFullscreen = "trailer_fullscreen"
    const val TrailerSearch = "trailer_search"
    const val HdrMode = "hdr_mode"
    const val ColorProfile = "color_profile"
    const val DesktopRenderer = "desktop_renderer"
    const val MouseMove = "mouse_move"
    const val SourceNotch = "source_notch"
    const val DefaultSpeed = "default_speed"
    const val BingeMode = "binge_mode"
    const val ExtraLargePosters = "extra_large_posters"
    const val BufferPreset = "buffer_preset"
    const val AnimeEnhancements = "anime_enhancements"
    const val AnimeAutoApply = "anime_auto_apply"
    const val AnimeSvp = "anime_svp"
    const val AnimeSvpOverlay = "anime_svp_overlay"
    const val RtxHdr = "rtx_hdr"
    const val TmdbHeroImages = "tmdb_hero_images"
    const val TvdbApiKey = "tvdb_api_key"
    const val DiscordPresence = "discord_presence"

    fun searchKey(key: String): String = "settings_search_$key"
    fun section(title: String): String = "settings_section_$title"

    internal data class Request(
        val anchor: String,
        val fallbackAnchor: String?,
        val fallbackTitle: String?,
        val sequence: Long,
    )
    internal data class TitleHighlight(val title: String, val sequence: Long)
    private val _requested = MutableStateFlow<Request?>(null)
    val requested: StateFlow<Request?> = _requested.asStateFlow()
    private val _titleHighlight = MutableStateFlow<TitleHighlight?>(null)
    val titleHighlight: StateFlow<TitleHighlight?> = _titleHighlight.asStateFlow()
    private var requestSequence = 0L
    private var titleHighlightSequence = 0L

    fun request(
        anchor: String,
        fallbackAnchor: String? = null,
        fallbackTitle: String? = null,
    ) {
        // A StateFlow does not emit equal values. Give every click a new identity so retrying a
        // search result can recover even if the prior target never mounted or consumed it.
        _requested.value = Request(
            anchor = anchor,
            fallbackAnchor = fallbackAnchor,
            fallbackTitle = fallbackTitle,
            sequence = ++requestSequence,
        )
    }

    internal fun consume(anchor: String, sequence: Long, allowFallback: Boolean = false): Boolean {
        val request = _requested.value ?: return false
        val matches = request.anchor == anchor ||
            (allowFallback && request.fallbackAnchor == anchor)
        if (!matches || request.sequence != sequence) return false
        _requested.value = null
        return true
    }

    fun expire(sequence: Long) {
        if (_requested.value?.sequence == sequence) _requested.value = null
    }

    fun highlightTitle(title: String) {
        _titleHighlight.value = TitleHighlight(title, ++titleHighlightSequence)
    }

    fun expireTitleHighlight(sequence: Long) {
        if (_titleHighlight.value?.sequence == sequence) _titleHighlight.value = null
    }

    // Back-destination override — set by Fork Enhancements so back from a deep-linked page
    // returns to Fork Enhancements rather than Root. Consumed once on the first back press.
    private val _backToPage = MutableStateFlow<String?>(null)
    val backToPage: StateFlow<String?> = _backToPage.asStateFlow()

    fun setBackTo(page: SettingsPage) {
        _backToPage.value = page.name
    }

    fun consumeBackTo(): SettingsPage? {
        val raw = _backToPage.value ?: return null
        _backToPage.value = null
        return runCatching { SettingsPage.valueOf(raw) }.getOrNull()
    }
}

/**
 * The state produced by [rememberSettingsAnchorHighlight]: [modifier] must be applied to the
 * anchored element so it can be brought into view, and [highlighted] is true for ~5s after the
 * anchor is requested so the caller can tint its label with the accent colour.
 */
internal data class SettingsAnchorHighlight(
    val highlighted: Boolean,
    val modifier: Modifier,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSettingsAnchorHighlight(anchor: String): SettingsAnchorHighlight {
    val requester = remember { BringIntoViewRequester() }
    val requested by SettingsScrollAnchor.requested.collectAsStateWithLifecycle()
    // Bump a local token (and consume the request) without keying the highlight timer on the
    // shared flow — otherwise consuming would cancel the in-flight highlight.
    var highlightToken by remember { mutableStateOf(0) }
    var highlighted by remember { mutableStateOf(false) }
    LaunchedEffect(requested) {
        val request = requested ?: return@LaunchedEffect
        if (request.anchor == anchor) {
            if (!SettingsScrollAnchor.consume(anchor, request.sequence)) return@LaunchedEffect
            highlightToken++
        } else if (request.fallbackAnchor == anchor) {
            // Give the exact destination a frame to mount and consume the request. If it is
            // conditionally hidden (or not anchored yet), the nearest visible fallback takes over.
            delay(SettingsScrollAnchorFallbackDelayMillis)
            if (!SettingsScrollAnchor.consume(anchor, request.sequence, allowFallback = true)) {
                return@LaunchedEffect
            }
            request.fallbackTitle?.let(SettingsScrollAnchor::highlightTitle)
            highlightToken++
        }
    }
    LaunchedEffect(highlightToken) {
        if (highlightToken == 0) return@LaunchedEffect
        runCatching { requester.bringIntoView() }
        highlighted = true
        delay(SettingsScrollAnchorHighlightMillis)
        highlighted = false
    }
    return SettingsAnchorHighlight(
        highlighted = highlighted,
        modifier = Modifier.bringIntoViewRequester(requester),
    )
}

/**
 * Marks an element as a scroll anchor. Brings itself into view when its id is requested; the
 * transient accent highlight is opt-in via [rememberSettingsAnchorHighlight] for callers that
 * control their own label colour (e.g. [SettingsSection]).
 */
@Composable
internal fun Modifier.settingsScrollAnchor(anchor: String): Modifier =
    this.then(rememberSettingsAnchorHighlight(anchor).modifier)

@Composable
internal fun Modifier.settingsSearchAnchors(vararg keys: String): Modifier =
    keys.fold(this) { modifier, key ->
        modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey(key))
    }
