package com.nuvio.app.features.youtube

import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.trailer.TrailerUnavailableReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * fork:youtube-unplayable - why YouTube last refused a video's streams, by id, so the YouTube tab can say
 * so ("age-restricted") instead of guessing at throttling, and so a video YouTube will not serve is not
 * asked about again on every hover and click. Noted by the stream lookup (MusicResolver), for a while.
 */
internal object YouTubeUnplayable {
    /** A refusal is remembered this long; a sign-in or an uploader's change can lift it. */
    private const val KEEP_MS = 30 * 60_000L

    /** [youTubeReason]: YouTube's own wording ("This video may be inappropriate for some users."), when it gave one. */
    data class Refusal(val reason: TrailerUnavailableReason, val youTubeReason: String?, val atMs: Long) {
        /** An age gate, as opposed to the other sign-in walls (a private video, "confirm you're not a bot"). */
        val ageGated: Boolean
            get() = reason == TrailerUnavailableReason.AGE_RESTRICTED && isAgeGateText(youTubeReason)

        /**
         * A verdict that asking again will not change: an age gate (the in-app lookup cannot open those even
         * signed in), a removed or private video, a region block. A bot check or an unknown failure is not.
         */
        val definitive: Boolean
            get() = ageGated ||
                reason == TrailerUnavailableReason.REMOVED_OR_PRIVATE ||
                reason == TrailerUnavailableReason.REGION_BLOCKED
    }

    private val known = MutableStateFlow<Map<String, Refusal>>(emptyMap())

    /** fork:youtube-age-gate - this build can open age-gated videos (the InnerTubeX resolver); set by the desktop lookup. */
    @kotlin.concurrent.Volatile
    var ageGateFallbackAvailable = false

    fun note(videoId: String, reason: TrailerUnavailableReason, youTubeReason: String?) {
        val now = epochMs()
        known.update { map ->
            map.filterValues { now - it.atMs < KEEP_MS } + (videoId to Refusal(reason, youTubeReason?.trim()?.ifEmpty { null }, now))
        }
    }

    fun clear(videoId: String) {
        if (videoId in known.value) known.update { it - videoId }
    }

    fun refusal(videoId: String): Refusal? =
        known.value[videoId]?.takeIf { epochMs() - it.atMs < KEEP_MS }

    /** The tab's line for a video that would not open, from what YouTube said about it (if anything). */
    fun openErrorMessage(title: String, refusal: Refusal?, paused: Boolean): String {
        val quoted = "\"$title\""
        return when {
            refusal == null || refusal.reason == TrailerUnavailableReason.UNKNOWN ->
                if (paused) {
                    "$quoted couldn't be opened: YouTube is limiting requests. Try again shortly."
                } else {
                    "$quoted couldn't be opened. Try again shortly."
                }
            refusal.ageGated && ageGateFallbackAvailable ->
                "$quoted is age-restricted and couldn't be opened. YouTube needs a signed-in, age-verified account " +
                    "(Settings > YouTube account); it opens on youtube.com."
            refusal.ageGated ->
                "$quoted is age-restricted, and this build of Nuvio can't play age-restricted videos. It opens on youtube.com."
            refusal.reason == TrailerUnavailableReason.REGION_BLOCKED ->
                "$quoted isn't available in your country."
            refusal.reason == TrailerUnavailableReason.REMOVED_OR_PRIVATE ->
                "$quoted can't be played: ${refusal.youTubeReason ?: "it is private or has been removed."}"
            // Another sign-in wall: a private video, or YouTube asking to confirm this isn't a bot.
            else -> "$quoted couldn't be opened. YouTube says: ${refusal.youTubeReason ?: "sign in to watch it."}"
        }
    }
}

/** Lower-case fragments of the ways YouTube words an age gate. */
private val AGE_GATE_PHRASES = listOf("your age", "age-restricted", "age restricted", "inappropriate for some users")

internal fun isAgeGateText(text: String?): Boolean {
    val lower = text?.lowercase() ?: return false
    return AGE_GATE_PHRASES.any { it in lower }
}
