package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's Shelf redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicHistoryItem
import com.nuvio.app.features.music.MusicPlayer
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * fork:shelf - the play log: what was listened to, kept on this computer per profile, so the Shelf can show Recently played,
 * Most played and the albums gathering dust. A track counts once it has played for [PLAY_COUNTS_AFTER_MS] (or half of a
 * shorter track); skipping on before that leaves no trace. The newest [PLAY_LOG_MAX] plays are kept.
 */

private const val PLAY_COUNTS_AFTER_MS = 30_000L
private const val PLAY_LOG_MAX = 3_000

/** One play: the track, its album when it has one, and what it was played from (an album, a playlist, a radio). */
@Serializable
internal data class PlayEntry(
    val atMs: Long,
    val videoId: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    /** The YouTube Music album (`MPREb_...`) the track is on, when known. */
    val albumBrowseId: String? = null,
    val albumTitle: String? = null,
    val coverUrl: String? = null,
    /** What it was played from: the album's id, or a queue's ("list:<playlistId>", "radio:<videoId>", "artist-radio:<id>"). */
    val sourceId: String? = null,
    val sourceTitle: String? = null,
    /** "Album", "Single", "Playlist", "Radio", "Mix"... as the queue was named. */
    val sourceKind: String? = null,
)

internal object MusicPlayLog {
    private val log = Logger.withTag("MusicPlayLog")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _entries = MutableStateFlow<List<PlayEntry>>(emptyList())
    private var loadedProfile: Int? = null
    private var watching = false

    /** Every play kept for the active profile, newest first. */
    val entries: StateFlow<List<PlayEntry>>
        get() {
            ensureLoaded()
            return _entries.asStateFlow()
        }

    private fun ensureLoaded() {
        val profile = ProfileRepository.activeProfileId
        if (loadedProfile == profile) return
        synchronized(this) {
            if (loadedProfile == profile) return
            loadedProfile = profile
            _entries.value = runCatching { MusicLibraryStorage.loadPlayLog(profile)?.let { json.decodeFromString<List<PlayEntry>>(it) } }
                .onFailure { log.w { "could not read the play log: ${it.message}" } }
                .getOrNull().orEmpty()
        }
    }

    /** Follows [player]: each track that plays long enough is added once per load. */
    fun watch(player: MusicPlayer) {
        synchronized(this) {
            if (watching) return
            watching = true
        }
        scope.launch {
            var counted = -1L
            player.state
                .map { s -> Triple(s.loadId, s.track != null && s.playWhenReady && !s.isLoading, s.positionMs) }
                .distinctUntilChanged()
                .collect { (loadId, playing, position) ->
                    if (!playing || loadId == counted) return@collect
                    val s = player.state.value
                    val track = s.track ?: return@collect
                    val duration = s.durationMs
                    val threshold = if (duration in 1 until PLAY_COUNTS_AFTER_MS * 2) duration / 2 else PLAY_COUNTS_AFTER_MS
                    if (position < threshold) return@collect
                    counted = loadId
                    val album = s.album
                    val albumId = track.albumBrowseId ?: album?.browseId?.takeIf { it.startsWith("MPREb_") }
                    record(
                        PlayEntry(
                            atMs = LibraryClock.nowEpochMs(),
                            videoId = track.videoId,
                            title = track.title,
                            artist = track.artists.firstOrNull()?.name ?: album?.artists?.firstOrNull()?.name,
                            artistId = track.artists.firstOrNull()?.browseId ?: album?.artists?.firstOrNull()?.browseId,
                            albumBrowseId = albumId,
                            albumTitle = track.albumTitle ?: album?.title?.takeIf { albumId == album.browseId },
                            coverUrl = track.coverUrl ?: album?.thumbnailUrl,
                            sourceId = album?.browseId,
                            sourceTitle = album?.title,
                            sourceKind = album?.kind,
                        ),
                    )
                }
        }
    }

    /** Forgets every play for the active profile; the Shelf's played shelves start over. */
    fun clear() {
        ensureLoaded()
        val profile = loadedProfile ?: return
        _entries.value = emptyList()
        runCatching { MusicLibraryStorage.savePlayLog(profile, "[]") }
            .onFailure { log.w { "could not clear the play log: ${it.message}" } }
    }

    /**
     * Adds what YouTube Music's History lists for the signed-in account (about three pages), dated as well as its headings say.
     * Plays already in the log are not added twice. Returns how many were added, or null when YouTube Music could not be read.
     */
    suspend fun importHistory(catalog: MusicCatalog): Int? {
        val items = runCatching { catalog.accountHistory() }.getOrNull() ?: return null
        ensureLoaded()
        val added = historyEntries(items, LibraryClock.nowEpochMs(), _entries.value)
        if (added.isEmpty()) return 0
        val profile = loadedProfile ?: return 0
        val next = (added + _entries.value).sortedByDescending { it.atMs }.take(PLAY_LOG_MAX)
        _entries.value = next
        runCatching { MusicLibraryStorage.savePlayLog(profile, json.encodeToString(next)) }
            .onFailure { log.w { "could not keep the play log: ${it.message}" } }
        return added.size
    }

    private fun record(entry: PlayEntry) {
        ensureLoaded()
        val profile = loadedProfile ?: return
        val next = (listOf(entry) + _entries.value).take(PLAY_LOG_MAX)
        _entries.value = next
        runCatching { MusicLibraryStorage.savePlayLog(profile, json.encodeToString(next)) }
            .onFailure { log.w { "could not keep the play log: ${it.message}" } }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** About when a History heading's songs were played: the heading only says how long ago. */
internal fun historyHeadingAgeMs(heading: String): Long = when (heading.trim().lowercase()) {
    "today" -> 0L
    "yesterday" -> DAY_MS
    "this week" -> 3 * DAY_MS
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday" -> 4 * DAY_MS
    "last week" -> 10 * DAY_MS
    else -> 21 * DAY_MS
}

/**
 * Plays for the songs of YouTube Music's History (newest first within each heading), dated by their heading and four minutes
 * apart so their order is kept. A song already in [existing] within a day and a half of that date is skipped, so importing
 * twice adds nothing.
 */
internal fun historyEntries(items: List<MusicHistoryItem>, nowMs: Long, existing: List<PlayEntry>): List<PlayEntry> {
    val byVideo = existing.groupBy { it.videoId }
    val seen = HashSet<String>()
    val index = HashMap<String, Int>()
    val out = ArrayList<PlayEntry>()
    for (item in items) {
        val n = index.merge(item.section, 1, Int::plus)!! - 1
        val at = nowMs - historyHeadingAgeMs(item.section) - n * 4 * 60_000L
        val t = item.track
        val duplicate = byVideo[t.videoId].orEmpty().any { kotlin.math.abs(it.atMs - at) < DAY_MS * 3 / 2 }
        if (duplicate || !seen.add("${t.videoId}@${item.section}")) continue
        out += PlayEntry(
            atMs = at,
            videoId = t.videoId,
            title = t.title,
            artist = t.artists.firstOrNull()?.name,
            artistId = t.artists.firstOrNull()?.browseId,
            albumBrowseId = t.albumBrowseId?.takeIf { it.startsWith("MPREb_") },
            albumTitle = t.albumTitle,
            coverUrl = t.coverUrl,
        )
    }
    return out
}

// region what the Shelf reads from the log (pure, tested)

/** When each YouTube Music album was last played, by album id. */
internal fun lastPlayedByAlbum(entries: List<PlayEntry>): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (e in entries) {
        val id = e.albumBrowseId ?: continue
        if ((out[id] ?: 0L) < e.atMs) out[id] = e.atMs
    }
    return out
}

/** How many tracks of each YouTube Music album were played, by album id. */
internal fun playsByAlbum(entries: List<PlayEntry>): Map<String, Int> =
    entries.mapNotNull { it.albumBrowseId }.groupingBy { it }.eachCount()

/** Something played lately, for the Recently played shelf: an album, or a playlist, mix or radio it was played from. */
internal sealed interface RecentPlay {
    val key: String
    val atMs: Long

    data class Album(val browseId: String, val title: String, val artist: String?, val artistId: String?, val coverUrl: String?, override val atMs: Long) : RecentPlay {
        override val key: String get() = "album:$browseId"
    }

    data class Queue(val sourceId: String, val title: String, val kind: String?, val coverUrl: String?, override val atMs: Long) : RecentPlay {
        override val key: String get() = "queue:$sourceId"

        /** The YouTube Music list id behind a "list:<id>" queue (a playlist or a mix), else null (a song's or artist's radio). */
        val playlistId: String? get() = sourceId.removePrefix("list:").takeIf { sourceId.startsWith("list:") }
    }
}

/**
 * What was played lately, newest first, each once: a track played from an album counts for its album; one played from a
 * playlist, mix or radio counts for that queue. Single songs (from search, a video) count for their album when they have one.
 */
internal fun recentPlays(entries: List<PlayEntry>, limit: Int = 60): List<RecentPlay> {
    val seen = HashSet<String>()
    val out = ArrayList<RecentPlay>()
    for (e in entries) {
        val source = e.sourceId
        val queue = source != null && (source.startsWith("list:") || source.startsWith("radio:") || source.startsWith("artist-radio:"))
        val item: RecentPlay = when {
            queue -> RecentPlay.Queue(source!!, e.sourceTitle ?: e.title, e.sourceKind, e.coverUrl, e.atMs)
            e.albumBrowseId != null -> RecentPlay.Album(e.albumBrowseId, e.albumTitle ?: e.sourceTitle ?: e.title, e.artist, e.artistId, e.coverUrl, e.atMs)
            else -> continue
        }
        if (seen.add(item.key)) out += item
        if (out.size >= limit) break
    }
    return out
}

// endregion
