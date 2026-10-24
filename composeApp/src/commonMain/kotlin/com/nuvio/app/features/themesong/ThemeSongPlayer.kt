package com.nuvio.app.features.themesong

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.components.HomeHeroTrailerSurface
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.delay

/** Fade length both ways. Callers that tear the player down right after clearing [source] wait this long. */
const val THEME_SONG_FADE_MS = 600L

private const val FADE_STEP_MS = 40L
/**
 * Safety stop for a source that keeps ending: a whole track loops this many times before the music
 * fades out. Generous on purpose; a pre-show or a details visit ends long before a few hours of music.
 */
private const val MAX_LOOPS = 40

/** A stream that ends having played less than this share of the upload's length was cut off. */
private const val CUT_SHORT_PERCENT = 60

/**
 * Plays [source] as background music: audio only, on a one-pixel native surface, faded in to
 * [volume] and faded out when [source] becomes null (the surface is dropped once silent).
 *
 * It reuses the hero-trailer player, so it plays wherever that does (desktop Windows and macOS) and
 * quietly does nothing elsewhere. A source that fails to open falls back along
 * [TrailerPlaybackSource.fallback]; when that runs out, or the track has looped [MAX_LOOPS] times,
 * the music fades out and [onFinished] is called. The whole track loops until then.
 *
 * Put it somewhere that does not take layout space or focus; it is invisible and passive.
 */
@Composable
fun ThemeSongPlayer(
    source: TrailerPlaybackSource?,
    volume: Int,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    var mounted by remember { mutableStateOf<TrailerPlaybackSource?>(null) }
    var level by remember { mutableIntStateOf(0) }
    var loops by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<TrailerPlaybackSource?>(null) }

    // `source` changing from one track to another is a new start, so a new fade in from silence.
    LaunchedEffect(source) {
        if (source != null) {
            mounted = source
            level = 0
            loops = 0
            failed = null
        }
    }
    LaunchedEffect(source, volume, failed) {
        val target = if (source == null || failed != null) 0 else volume.coerceIn(0, 100)
        val start = level
        if (start != target) {
            val steps = (THEME_SONG_FADE_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                level = start + (target - start) * i / steps
                delay(FADE_STEP_MS)
            }
            level = target
        }
        if (target == 0) {
            mounted = null
            if (failed != null) onFinished()
        }
    }

    val playing = mounted ?: return
    // Each fallback is tried in turn; `attempt` is the one currently on the surface.
    var attempt by remember(playing) { mutableStateOf(playing) }
    // When this pass over the track began, to tell a stream that was cut off early (some uploads stop
    // serving after a few seconds) from one that played to its end. Includes the time spent opening
    // it, so it only ever overestimates how much played: only a clear cut-off is caught.
    val startedAtMs = remember(playing, loops, attempt) { LibraryClock.nowEpochMs() }
    key(playing, loops) {
        Box(modifier = modifier.size(1.dp)) {
            HomeHeroTrailerSurface(
                // Adaptive uploads give a separate audio-only stream; play just that one. A muxed
                // or HLS source has no such stream and plays as it is.
                sourceUrl = attempt.audioUrl?.takeIf { it.isNotBlank() } ?: attempt.videoUrl,
                sourceAudioUrl = null,
                playWhenReady = true,
                muted = false,
                volume = level,
                backgroundColor = Color.Black,
                logoUrl = null,
                title = "",
                meta = "",
                description = "",
                modifier = Modifier.size(1.dp),
                onReady = {},
                onEnded = {
                    val expectedMs = playing.durationMs
                    val playedMs = LibraryClock.nowEpochMs() - startedAtMs
                    val next = attempt.fallback
                    if (expectedMs > 0L && playedMs * 100 < expectedMs * CUT_SHORT_PERCENT && next != null) {
                        // Ended well before the track's real length: play the same track from its
                        // other stream instead of repeating the first few seconds of this one.
                        attempt = next
                    } else if (loops + 1 >= MAX_LOOPS) {
                        failed = playing
                    } else {
                        loops += 1
                    }
                },
                onError = {
                    val next = attempt.fallback
                    if (next != null) attempt = next else failed = playing
                },
                normalizeAudio = true,
                preshow = false,
            )
        }
    }
}
