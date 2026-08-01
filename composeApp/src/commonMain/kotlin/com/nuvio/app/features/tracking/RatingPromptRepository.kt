package com.nuvio.app.features.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A finished title waiting to be offered for rating. */
data class RatingPromptRequest(
    val contentId: String,
    val contentType: String,
    val title: String,
    val reason: RatingPromptReason,
    val seasonNumber: Int? = null,
    val releaseInfo: String? = null,
) {
    /**
     * Identity for "have we already asked about this?".
     *
     * Includes the season so finishing two season finales of the same show in one sitting asks
     * twice, which is right — they are two separate things to rate.
     */
    internal val dedupeKey: String
        get() = "${contentType.trim().lowercase()}:${contentId.trim()}:${reason.name}:${seasonNumber ?: -1}"

    /**
     * Ratings are title-level on every provider that accepts them, so a season finale rates the
     * show. Built without episode coordinates for that reason.
     */
    val media: TrackingMediaReference
        get() = buildTrackingMediaReference(
            contentType = contentType,
            parentMetaId = contentId,
            title = title,
            releaseInfo = releaseInfo,
        )
}

/**
 * Holds the "rate what you just finished" prompt and the preference that governs it.
 *
 * The prompt is *queued* at completion and shown once the user is out of the player, rather than
 * drawn over playback. Two reasons: the player is a native surface that Compose overlays sit on
 * awkwardly, and a season finale in the middle of a binge would otherwise interrupt the auto-play
 * into the next season. Only the most recent request is kept — finishing a second thing before
 * answering means the older prompt is stale.
 */
object RatingPromptRepository {
    private val _pendingRequest = MutableStateFlow<RatingPromptRequest?>(null)
    val pendingRequest: StateFlow<RatingPromptRequest?> = _pendingRequest.asStateFlow()

    private val _isEnabled = MutableStateFlow(DEFAULT_ENABLED)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private var hasLoaded = false
    private var lastOfferedKey: String? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        _pendingRequest.value = null
        lastOfferedKey = null
        loadFromDisk()
    }

    fun clearLocalState() {
        _pendingRequest.value = null
        lastOfferedKey = null
        _isEnabled.value = DEFAULT_ENABLED
        hasLoaded = true
        ContinueWatchingSourceStorage.saveRatingPromptEnabled(DEFAULT_ENABLED.toString())
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_isEnabled.value == enabled) return
        _isEnabled.value = enabled
        if (!enabled) _pendingRequest.value = null
        ContinueWatchingSourceStorage.saveRatingPromptEnabled(enabled.toString())
    }

    /**
     * Queues a prompt, if one is wanted at all.
     *
     * Dropped when the preference is off or nothing connected can accept a rating — asking someone
     * to rate into a provider that will refuse the write is worse than staying quiet.
     */
    fun offer(request: RatingPromptRequest) {
        ensureLoaded()
        if (!_isEnabled.value) return
        if (activeTrackingRatingProvider() == null) return
        // The player flushes progress more than once as a title ends, so the same completion
        // arrives repeatedly. Without this, answering the prompt would be followed by it
        // reappearing for the thing that was just rated.
        if (request.dedupeKey == lastOfferedKey) return
        lastOfferedKey = request.dedupeKey
        _pendingRequest.value = request
    }

    fun dismiss() {
        _pendingRequest.value = null
    }

    private fun loadFromDisk() {
        hasLoaded = true
        _isEnabled.value = ContinueWatchingSourceStorage.loadRatingPromptEnabled()
            ?.trim()
            ?.toBooleanStrictOrNull()
            ?: DEFAULT_ENABLED
    }

    private const val DEFAULT_ENABLED = true
}
