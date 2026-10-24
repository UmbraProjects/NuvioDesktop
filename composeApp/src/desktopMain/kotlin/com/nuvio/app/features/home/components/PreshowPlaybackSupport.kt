package com.nuvio.app.features.home.components

import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.TRAILER_AUDIO_NORMALIZATION_FILTER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * fork:preshow
 *
 * Desktop helpers for HomeHeroTrailerSurface.desktop.kt: the pre-show audio chain, the stall
 * watchdog and the end-of-item fade. They live here so the upstream surface file only carries
 * short tagged hooks and merges cleanly.
 */

/** Pre-show items play at one fixed, full level; the loudness filter evens them out. */
internal const val PRESHOW_VOLUME = 100

/** Length of the pre-show fade out at the end of an item. */
internal const val PRESHOW_FADE_MS = 1_000L

/** Fade in at the start of an item: just long enough to avoid a click, short enough not to be a dip. */
internal const val PRESHOW_FADE_IN_MS = 120L

internal const val TRAILER_STALL_MS = 10_000L
internal const val TRAILER_START_STALL_MS = 20_000L

/**
 * mpv init options for a hero trailer / pre-show item. Volume, mute and the loudness filter go in
 * as init options so the very first audio frame already has them. As runtime properties they
 * replay after create(), which rebuilds the audio chain once playback has begun (an audible dip
 * at clip start).
 */
internal fun heroTrailerAudioInitOptions(
    muted: Boolean,
    volume: Int,
    normalizeAudio: Boolean,
    preshow: Boolean,
): List<String> = buildList {
    add("mute=${if (muted) "yes" else "no"}")
    add("volume=$volume")
    // A very short fade-in sits after the normaliser, which would otherwise lift the quiet start
    // back up and undo it. It only has to take the click off the first sample: a long one is heard
    // as every item opening quietly. It is a filter rather than a volume ramp so it is
    // sample-accurate and cannot be missed if the UI thread is busy at clip start.
    val chain = buildList {
        if (normalizeAudio) add(TRAILER_AUDIO_NORMALIZATION_FILTER)
        if (preshow) add("afade=t=in:st=0:d=${PRESHOW_FADE_IN_MS / 1000.0}")
    }
    when (chain.size) {
        0 -> Unit
        1 -> add("af=${chain.single()}")
        else -> add("af=lavfi=[${chain.joinToString(",")}]")
    }
}

/**
 * A googlevideo connection reset can leave mpv "reconnecting" forever with no error and no
 * end-of-file, which holds a pre-show on a frozen frame. No progress for a while counts as a
 * failed playback, so the caller retries the fallback source or moves on.
 */
internal class TrailerStallWatchdog {
    private var stallReported = false
    private var lastPositionMs = -1L
    private var lastProgressAtMs = System.currentTimeMillis()

    /** Returns how long playback has been stuck the first time it counts as stalled, else null. */
    fun poll(positionMs: Long, playWhenReady: Boolean, ready: Boolean, ended: Boolean): Long? {
        val nowMs = System.currentTimeMillis()
        if (positionMs != lastPositionMs) {
            lastPositionMs = positionMs
            lastProgressAtMs = nowMs
        }
        val limitMs = if (ready) TRAILER_STALL_MS else TRAILER_START_STALL_MS
        val stuckMs = nowMs - lastProgressAtMs
        if (playWhenReady && !stallReported && !ended && stuckMs > limitMs) {
            stallReported = true
            return stuckMs
        }
        return null
    }
}

/**
 * Pre-show only: eases the last second out instead of cutting the audio at end of file. It needs
 * the duration, which a live stream may not report; then it simply ends as before.
 */
internal class PreshowFadeOut(private val controller: NativePlayerController) {
    private var started = false

    fun maybeStart(
        scope: CoroutineScope,
        ready: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (started || !ready) return
        if (durationMs <= PRESHOW_FADE_MS * 3 || positionMs < durationMs - PRESHOW_FADE_MS) return
        started = true
        scope.launch {
            val startedNs = System.nanoTime()
            while (true) {
                val progress = ((System.nanoTime() - startedNs) / 1_000_000.0 / PRESHOW_FADE_MS).coerceIn(0.0, 1.0)
                // Squared so the steps stay small where the ear is most sensitive.
                val gain = (1.0 - progress) * (1.0 - progress)
                controller.setMpvProperty("volume", (PRESHOW_VOLUME * gain).roundToInt().toString())
                if (progress >= 1.0) break
                delay(25L)
            }
        }
    }
}
