package com.nuvio.app.features.music.library
// FORK UI (Spyder): Nick's album page lenses, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.music.booklet.FactsPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * fork:credits - every album's credits from MusicBrainz, kept for good (a "no such release" answer for 30 days; a failure
 * not at all, so it is asked again). The album page asks when the Who made it tab opens; the facts filler asks for every
 * library album in the background (no AI, about two paced MusicBrainz calls each), so the "elsewhere" panel can search
 * the whole library.
 */
internal object PeopleCreditsBook {
    private val log = Logger.withTag("PeopleCredits")
    private val lock = Mutex()
    private val _changes = MutableStateFlow(0)

    /** Goes up each time an album's credits are kept, so the lens reads them again. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun fresh(a: AlbumCredits?): AlbumCredits? = a?.takeIf { it.found || now() - it.atMs < CREDITS_NONE_TTL_MS }

    /** What is kept for the album, never making a request. */
    fun peek(artist: String, album: String): AlbumCredits? = fresh(decodeAlbumCredits(CreditsStorage.get(creditsKey(artist, album))))

    /** True when the album has nothing kept (or a stale "none"), so a lookup is due. */
    fun missing(artist: String, album: String): Boolean = peek(artist, album) == null

    /** The album's credits, asking MusicBrainz when nothing is kept; null when it could not be reached. */
    suspend fun load(artist: String, album: String, albumId: String?): AlbumCredits? = FactsPriority.foreground {
        lock.withLock {
            peek(artist, album)?.let { return@withLock it }
            val credits = runCatching { MusicBrainzClient.peopleCredits(artist, album) }.getOrNull() ?: run {
                log.w { "credits for $artist - $album: MusicBrainz could not be reached" }
                return@withLock null
            }
            val kept = AlbumCredits(now(), albumId, artist, album, credits, found = credits.isNotEmpty())
            CreditsStorage.put(creditsKey(artist, album), encodeAlbumCredits(kept))
            _changes.update { it + 1 }
            log.i { "credits for $artist - $album: ${credits.size} credits" }
            kept
        }
    }

    /** Every album's kept credits that were found, by key. Reads the whole store: call it off the main thread. */
    fun allKept(): Map<String, AlbumCredits> = CreditsStorage.keys().filter { it.startsWith("people2|") }
        .mapNotNull { k -> decodeAlbumCredits(CreditsStorage.get(k))?.takeIf { it.found }?.let { k to it } }.toMap()
}
