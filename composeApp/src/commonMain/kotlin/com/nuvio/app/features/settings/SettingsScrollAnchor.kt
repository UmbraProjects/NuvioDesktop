package com.nuvio.app.features.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val SettingsScrollAnchorHighlightMillis = 5000L

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
    const val InverseToneMapping = "inverse_tone_mapping"
    const val ColorProfile = "color_profile"
    const val DesktopRenderer = "desktop_renderer"
    const val MouseMove = "mouse_move"
    const val SourceNotch = "source_notch"
    const val SourceNotchHover = "source_notch_hover"
    const val NotificationPosition = "notification_position"
    const val DefaultSpeed = "default_speed"
    const val SpeedToggle = "speed_toggle"
    const val BingeMode = "binge_mode"
    const val ExtraLargePosters = "extra_large_posters"
    const val BufferPreset = "buffer_preset"
    const val SeekThumbnails = "seek_thumbnails"
    const val RateLimitRecovery = "rate_limit_recovery"
    const val AnimeEnhancements = "anime_enhancements"
    const val AnimeAutoApply = "anime_auto_apply"
    const val AnimeIncludeWesternAnimation = "anime_include_western_animation"
    const val AnimeSkipUltraHd = "anime_skip_ultra_hd"
    const val AnimeSvp = "anime_svp"
    const val AnimeSvpOverlay = "anime_svp_overlay"
    const val RtxHdr = "rtx_hdr"
    const val RtxHdrAnime = "rtx_hdr_anime"
    const val RtxHdrAnimeShaders = "rtx_hdr_anime_shaders"
    const val TmdbHeroImages = "tmdb_hero_images"
    const val TvdbApiKey = "tvdb_api_key"
    const val DiscordPresence = "discord_presence"
    const val DiscordEpisodeArtwork = "discord_episode_artwork"
    const val DiscordActivityStyle = "discord_activity_style"
    const val DiscordActivityName = "discord_activity_name"

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

    // Which anchors are composed right now, so a jump can tell at once whether its target is on
    // the page instead of waiting out timers for it to answer. UI thread only.
    //
    // "On the page" means placed where the list is now, not merely composed: a lazy list can hold
    // a section composed off-screen after a jump carried it away, and that section still reports
    // the position it last had. Each anchor registers a check that answers for its own element.
    private val mounted = HashMap<String, MutableList<() -> Boolean>>()

    internal fun markMounted(anchor: String, onPage: () -> Boolean) {
        mounted.getOrPut(anchor) { mutableListOf() }.add(onPage)
    }

    internal fun markUnmounted(anchor: String, onPage: () -> Boolean) {
        val checks = mounted[anchor] ?: return
        checks.remove(onPage)
        if (checks.isEmpty()) mounted.remove(anchor)
    }

    internal fun isMounted(anchor: String?): Boolean =
        anchor != null && mounted[anchor]?.any { it() } == true

    /**
     * True while the settings list walks itself to an anchor that is not composed yet. The list is
     * hidden for those few frames, so the jump reads as one quick move rather than a visible
     * page-by-page crawl.
     */
    internal var seeking by mutableStateOf(false)

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
 * The scroll viewport the settings anchors live in: its size, and the inset a heading lands at
 * when it is scrolled to the top. Provided by the desktop settings screen from its page list; null
 * where the page is not one scroll container (mobile), which leaves headings to the plain
 * bring-into-view.
 */
internal class SettingsAnchorViewport(
    val size: State<IntSize>,
    val topInset: Dp,
    /**
     * Scrolls the list by a pixel delta: with the settings jump's own quick ease, or at once when
     * `instant` (a landing at the end of a hidden seek, see [SettingsScrollAnchor.seeking]).
     */
    private val scrollBy: (suspend (delta: Float, instant: Boolean) -> Unit)? = null,
    /**
     * The list's own scope, which the landing scroll runs in. Not the anchor's: a heading that a
     * lazy list still holds composed off-screen (above a jump that went further down) is let go as
     * soon as the list starts moving, and a scroll running in that heading's effect was cancelled
     * with it, so the jump back to it did nothing.
     */
    private val scope: CoroutineScope? = null,
    /**
     * The list's scroll position as one number. An anchor stamps it each time it is placed; one
     * whose stamp is out of date was not placed in the current layout (see markMounted).
     */
    val scrollPosition: () -> Long = { 0L },
) {
    /** The list's layout, set as it is placed; what an anchor measures itself against. */
    var coordinates: LayoutCoordinates? = null

    /**
     * Moves [element] into view with [scrollBy]: to the top (less [topInset]) when [alignToTop],
     * otherwise just far enough. False when it cannot (no scroller, or either layout detached), for
     * the caller to fall back to plain bring-into-view.
     */
    suspend fun scrollIntoView(element: LayoutCoordinates?, alignToTop: Boolean, density: Density): Boolean {
        val scroll = scrollBy ?: return false
        val list = coordinates?.takeIf { it.isAttached } ?: return false
        val target = element?.takeIf { it.isAttached } ?: return false
        val bounds = list.localBoundingBoxOf(target, clipBounds = false)
        val inset = with(density) { topInset.toPx() }
        val viewportHeight = list.size.height.toFloat()
        val delta = when {
            alignToTop || bounds.top < inset -> bounds.top - inset
            bounds.bottom > viewportHeight -> minOf(bounds.bottom - viewportHeight + inset, bounds.top - inset)
            else -> 0f
        }
        if (delta != 0f) {
            // A target the seek had to walk to lands in one step while the list is still hidden:
            // the walk stops wherever the target first appears, often well short of where it
            // should sit, and gliding the rest of the way showed as the page overshooting and then
            // scrolling back. Only a target that was already on screen gets the short glide.
            val instant = SettingsScrollAnchor.seeking
            val listScope = scope
            if (listScope != null) listScope.launch { scroll(delta, instant) } else scroll(delta, instant)
        }
        return true
    }

    /**
     * A viewport-tall rect hung from the element's top edge (less [topInset]). Bringing it into
     * view scrolls the element to the top of the viewport whichever side of it the element starts
     * on, whereas the element's own bounds only ask for the nearest edge - a heading just below the
     * fold would land at the bottom with none of its rows showing. Being exactly viewport-tall,
     * the rect never trips the "larger than the viewport, leave it" rule.
     */
    fun headingRect(density: Density): Rect? {
        val size = size.value
        if (size.height <= 0) return null
        val inset = with(density) { topInset.toPx() }
        return Rect(0f, -inset, size.width.toFloat(), size.height - inset)
    }
}

internal val LocalSettingsAnchorViewport = staticCompositionLocalOf<SettingsAnchorViewport?> { null }

/**
 * The state produced by [rememberSettingsAnchorHighlight]: [modifier] must be applied to the
 * anchored element so it can be brought into view, and [highlighted] is true for ~5s after the
 * anchor is requested so the caller can tint its label with the accent colour.
 */
internal data class SettingsAnchorHighlight(
    val highlighted: Boolean,
    val modifier: Modifier,
)

/**
 * [alignToTop] scrolls the element to the top of the viewport (see [SettingsAnchorViewport]) rather
 * than just far enough to show it - for a section heading, whose rows sit beneath it and are what
 * the jump was for. No effect without a [LocalSettingsAnchorViewport].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSettingsAnchorHighlight(
    anchor: String,
    alignToTop: Boolean = false,
): SettingsAnchorHighlight {
    val requester = remember { BringIntoViewRequester() }
    val viewport = LocalSettingsAnchorViewport.current
    // Where this element sits, for the viewport's own scroll. Not state: read only when landing.
    val placement = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val density = LocalDensity.current
    val requested by SettingsScrollAnchor.requested.collectAsStateWithLifecycle()
    // Bump a local token (and consume the request) without keying the highlight timer on the
    // shared flow — otherwise consuming would cancel the in-flight highlight.
    var highlightToken by remember { mutableStateOf(0) }
    var highlighted by remember { mutableStateOf(false) }
    // The list position this element was last placed at; see SettingsScrollAnchor.markMounted.
    val placedAt = remember { longArrayOf(Long.MIN_VALUE) }
    val onPage = remember(viewport) {
        {
            placement[0]?.isAttached == true &&
                (viewport == null || placedAt[0] == viewport.scrollPosition())
        }
    }
    DisposableEffect(anchor, onPage) {
        if (anchor.isNotEmpty()) SettingsScrollAnchor.markMounted(anchor, onPage)
        onDispose { if (anchor.isNotEmpty()) SettingsScrollAnchor.markUnmounted(anchor, onPage) }
    }
    LaunchedEffect(requested) {
        val request = requested ?: return@LaunchedEffect
        if (request.anchor == anchor) {
            // Composed but off-screen: leave the request for the seek, which walks the list until
            // this element is placed again and it can claim the jump with a true position. One
            // frame first, for an element that has only just been composed.
            if (!onPage()) withFrameNanos {}
            if (!onPage()) return@LaunchedEffect
            if (!SettingsScrollAnchor.consume(anchor, request.sequence)) return@LaunchedEffect
            highlightToken++
        } else if (request.fallbackAnchor == anchor) {
            // Give the exact destination a frame to mount and consume the request. If it is not
            // on the page (conditionally hidden, or not anchored), this fallback takes over. A frame,
            // not a fixed wait: rows mount with their section heading, so one frame is enough to
            // know, and the old 120ms showed as a pause before every search jump.
            withFrameNanos {}
            if (SettingsScrollAnchor.isMounted(request.anchor)) return@LaunchedEffect
            if (!SettingsScrollAnchor.consume(anchor, request.sequence, allowFallback = true)) {
                return@LaunchedEffect
            }
            request.fallbackTitle?.let(SettingsScrollAnchor::highlightTitle)
            highlightToken++
        }
    }
    LaunchedEffect(highlightToken) {
        if (highlightToken == 0) return@LaunchedEffect
        highlighted = true
        // A target that has only just mounted may not be placed until the next frame.
        if (placement[0] == null) withFrameNanos {}
        val landed = viewport?.scrollIntoView(placement[0], alignToTop, density) == true
        if (!landed) {
            runCatching { requester.bringIntoView(if (alignToTop) viewport?.headingRect(density) else null) }
        }
        delay(SettingsScrollAnchorHighlightMillis)
        highlighted = false
    }
    return SettingsAnchorHighlight(
        highlighted = highlighted,
        modifier = Modifier
            .bringIntoViewRequester(requester)
            .onPlaced {
                placement[0] = it
                placedAt[0] = viewport?.scrollPosition?.invoke() ?: 0L
            },
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
