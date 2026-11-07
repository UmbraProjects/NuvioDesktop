package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.library.MusicLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music steps aside while something else on screen has sound (a film, a trailer, a theme song) and
 * comes back when it is done.
 *
 * Each source takes a hold while it plays. A hold pauses music that is playing and remembers that it
 * did; once the last hold is gone, and stays gone for [graceMs], the music it paused plays again. The
 * grace bridges one trailer handing over to the next as focus moves along a row, so the song does not
 * blip back in between. Music that was already paused when the hold came is left paused.
 */
internal class MusicAutoPauseController(
    private val isPlaying: () -> Boolean,
    private val isActive: () -> Boolean,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
    private val scope: CoroutineScope,
    private val graceMs: Long = MUSIC_AUTO_RESUME_GRACE_MS,
) {
    private val holders = mutableSetOf<Any>()
    private var resumeWhenFree = false
    private var pendingResume: Job? = null

    val isHeld: Boolean get() = holders.isNotEmpty()

    /**
     * Takes a hold for [owner]. [resumeAfter] false still pauses, but does not ask for the music back
     * (the "resume after playback" switch is off); a later hold that does ask can still set it.
     */
    fun hold(owner: Any, resumeAfter: Boolean = true) {
        pendingResume?.cancel()
        pendingResume = null
        holders.add(owner)
        // Checked on every hold, not only the first: music started by hand under one hold is paused
        // again by the next, and comes back with the rest when they are all gone.
        if (isActive() && isPlaying()) {
            pause()
            if (resumeAfter) resumeWhenFree = true
        }
    }

    fun release(owner: Any) {
        if (!holders.remove(owner) || holders.isNotEmpty()) return
        if (!resumeWhenFree) return
        pendingResume?.cancel()
        pendingResume = scope.launch {
            delay(graceMs)
            pendingResume = null
            if (holders.isNotEmpty()) return@launch
            resumeWhenFree = false
            // Closed, or started again by hand meanwhile: nothing to bring back.
            if (isActive() && !isPlaying()) resume()
        }
    }
}

/** Long enough for a trailer to hand over to the next one as focus moves, short enough to feel immediate. */
internal const val MUSIC_AUTO_RESUME_GRACE_MS = 1_500L

object MusicAutoPause {
    internal val controller: MusicAutoPauseController by lazy {
        val player = MusicPlayback.player
        MusicAutoPauseController(
            isPlaying = { player.state.value.playWhenReady },
            isActive = { player.state.value.isActive },
            pause = player::pause,
            resume = player::resume,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        )
    }

    fun hold(owner: Any, resumeAfter: Boolean = true) = controller.hold(owner, resumeAfter)

    fun release(owner: Any) = controller.release(owner)
}

/**
 * Holds music paused while a trailer (or a theme song) is [audible], when Settings > Music > "Pause
 * for trailers" is on. Call from the trailer surface itself, so every place that plays one is covered.
 */
@Composable
fun MusicPausedWhileTrailerPlays(audible: Boolean) {
    val state by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val wanted = audible && state.settings.pauseForTrailers
    DisposableEffect(wanted) {
        if (!wanted) return@DisposableEffect onDispose { }
        val owner = Any()
        MusicAutoPause.hold(owner)
        onDispose { MusicAutoPause.release(owner) }
    }
}
