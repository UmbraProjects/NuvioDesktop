package com.nuvio.app.features.home.components

import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One request to play a trailer on demand — the `T` shortcut, or the hover preview's trailer
 * button — independent of the auto-play setting.
 *
 * [item] names what to play. Null means "whatever the hero is currently showing", which is the
 * only sensible answer in Adaptive and TV Mode, where the hero already follows the focused item.
 * Basic's hero does not follow focus, so there it carries the poster the user actually asked
 * about, and the hero plays that instead of the trending title it happens to be displaying.
 */
data class HomeHeroTrailerRequest(
    val token: Int,
    val item: MetaPreview? = null,
)

object HomeHeroTrailerManualTrigger {
    private var nextToken = 0
    private val _requests = MutableSharedFlow<HomeHeroTrailerRequest>(extraBufferCapacity = 1)
    val requests: SharedFlow<HomeHeroTrailerRequest> = _requests.asSharedFlow()

    /** Whether a hero trailer is currently showing, so the key handler can map Escape to dismiss. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun trigger(item: MetaPreview? = null) {
        nextToken += 1
        _requests.tryEmit(HomeHeroTrailerRequest(token = nextToken, item = item))
    }

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /**
     * Whether any hero is currently willing to play a trailer for a title it is not itself showing.
     * The hover preview's trailer button reads this: everywhere else the request would be accepted
     * by nobody, and the button would be one that visibly does nothing.
     *
     * A count rather than a flag because Home, Search and Library heroes can be in composition at
     * the same time, and with a flag one instance's teardown clears another instance's claim — the
     * same race the mute shortcut had to work around. Only ever touched from composition effects,
     * which run on the main dispatcher.
     */
    private var targetedHostCount = 0
    private val _acceptsTargetedRequests = MutableStateFlow(false)
    val acceptsTargetedRequests: StateFlow<Boolean> = _acceptsTargetedRequests.asStateFlow()

    fun addTargetedHost() {
        targetedHostCount += 1
        _acceptsTargetedRequests.value = true
    }

    fun removeTargetedHost() {
        targetedHostCount = (targetedHostCount - 1).coerceAtLeast(0)
        _acceptsTargetedRequests.value = targetedHostCount > 0
    }
}
