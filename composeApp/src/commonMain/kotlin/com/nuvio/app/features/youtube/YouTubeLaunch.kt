package com.nuvio.app.features.youtube

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/*
 * fork:youtube-mode
 *
 * A YouTube video as the player opens it: its streams resolved (the music resolver's restricted-client
 * retry and probe), titled as the tab shows it. Shared by the tab and by a playlist moving on in the
 * player (YouTubePlayQueue).
 */

/** The title [video] shows: DeArrow's when it has one and DeArrow is on. */
internal fun youTubeDisplayTitle(video: YouTubeVideo): String =
    YouTubeDeArrow.cached(video.videoId)?.title?.takeIf { YouTubeFeedRepository.deArrow.value } ?: video.title

/** [video] ready for the player, or null when none of its streams would open. */
internal suspend fun youTubePlayerLaunch(video: YouTubeVideo): PlayerLaunch? {
    // Its title and channel, for this PC's history once it has played (YouTubeWatchHistory.noteWatched).
    YouTubeWatchHistory.noteOpened(video)
    val source = try {
        YouTubeStreamsAhead.open(video.videoId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        null
    } ?: return null
    val title = youTubeDisplayTitle(video)
    return PlayerLaunch(
        title = title,
        sourceUrl = source.videoUrl,
        sourceAudioUrl = source.audioUrl,
        sourceHeaders = source.headers,
        streamTitle = title,
        streamSubtitle = video.channel,
        providerName = "YouTube",
        poster = video.thumbnailUrl,
        background = video.thumbnailUrl,
        contentType = YOUTUBE_META_TYPE,
        videoId = video.videoId,
        parentMetaId = YOUTUBE_META_ID_PREFIX + video.videoId,
        parentMetaType = YOUTUBE_META_TYPE,
        // Picks up where YouTube says this account left off, unless it was finished. A source that
        // cannot be opened part way in starts from the top instead: mpv would wait on that seek for good.
        initialProgressFraction = video.watchedPercent
            ?.takeIf { it in 1..94 && source.openableMidway }
            ?.div(100f),
        // Kept out of Continue Watching and the trackers; YouTube's own history gets it
        // instead (PlayerYouTubeWatchTime.kt).
        disableProgressTracking = true,
    )
}

/**
 * fork:youtube-quick-start - a video's streams looked up while its card is under the cursor (or the
 * keyboard's focus), so a click hands the player streams that are already resolved and probed rather
 * than starting the lookup then (about a second). One lookup at a time; a card left before its turn
 * came is dropped. Kept a few minutes (the URLs last hours) and used once: a source that failed to
 * play is not handed out again.
 */
internal object YouTubeStreamsAhead {
    private const val KEEP_MS = 4 * 60_000L
    private const val MAX_KEPT = 6

    private class Entry(val atMs: Long, val source: Deferred<TrailerPlaybackSource?>) {
        @kotlin.concurrent.Volatile
        var started = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val running = Semaphore(1)
    private val entries = LinkedHashMap<String, Entry>()

    /** Starts looking up [videoId]'s streams unless that is already done or under way. */
    suspend fun warm(videoId: String) {
        lock.withLock {
            prune()
            if (videoId in entries) return
            // Only the latest card waits for its turn.
            entries.entries.removeAll { (_, entry) -> (!entry.started).also { if (it) entry.source.cancel() } }
            lateinit var entry: Entry
            val source = scope.async(start = CoroutineStart.LAZY) {
                running.withPermit {
                    entry.started = true
                    youTubeOpenVideo(videoId, ahead = true)
                }
            }
            entry = Entry(com.nuvio.app.features.streams.epochMs(), source)
            entries[videoId] = entry
            while (entries.size > MAX_KEPT) entries.remove(entries.keys.first())
            source.start()
        }
    }

    /** [videoId]'s streams: the ones looked up ahead when there are any (waiting for a lookup under way), else looked up now. */
    suspend fun open(videoId: String): TrailerPlaybackSource? {
        val ahead = lock.withLock {
            prune()
            val entry = entries[videoId]
            // Still waiting for its turn: looked up now instead, and not a second time behind that.
            if (entry != null && !entry.started) {
                entry.source.cancel()
                entries.remove(videoId)
            }
            entry?.takeIf { it.started }
        }
        if (ahead != null) {
            val source = try {
                ahead.source.await()
            } catch (error: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (error: Throwable) {
                null
            }
            lock.withLock { if (entries[videoId] === ahead) entries.remove(videoId) }
            if (source != null) return source
        }
        return youTubeOpenVideo(videoId, ahead = false)
    }

    private fun prune() {
        val now = com.nuvio.app.features.streams.epochMs()
        entries.entries.removeAll { (_, entry) -> now - entry.atMs > KEEP_MS }
    }
}
