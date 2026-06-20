package com.nuvio.app.features.home.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.TimeSource

/**
 * Gates the TV-mode hero trailer auto-play timer so it only counts down while the user is
 * genuinely dwelling on the home screen:
 *
 * - [focusNonce] increments on every focus move, so navigating to another item (even rows
 *   without an adaptive hero, like continue watching) restarts the dwell timer instead of
 *   accumulating toward an unwanted auto-play.
 * - [homeActive] is false whenever the home tab isn't the foreground screen, so leaving for
 *   settings/search/etc. resets the timer and it never fires the moment you return.
 * - [startupGraceRemainingMillis] swallows the dwell time accumulated during the app's first
 *   few seconds, so a trailer never auto-plays while continue-watching and other home assets
 *   are still loading and focus has settled on the hero by default rather than by intent.
 */
object HomeHeroTrailerGate {
    /** Dwell time during this window after startup does not count toward auto-play. */
    const val StartupGraceMillis = 5_000L

    // Anchored at first access, which is the home screen's first composition — i.e. when the
    // initial assets begin loading.
    private val startupMark = TimeSource.Monotonic.markNow()

    private val _focusNonce = MutableStateFlow(0)
    val focusNonce: StateFlow<Int> = _focusNonce.asStateFlow()

    private val _homeActive = MutableStateFlow(true)
    val homeActive: StateFlow<Boolean> = _homeActive.asStateFlow()

    fun notifyFocusChanged() {
        _focusNonce.value += 1
    }

    fun setHomeActive(active: Boolean) {
        _homeActive.value = active
    }

    /** Milliseconds the dwell timer must additionally wait out before counting, or 0 once past. */
    fun startupGraceRemainingMillis(): Long =
        (StartupGraceMillis - startupMark.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
}
