package com.nuvio.app.features.music.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Item 4 of the remaining-work list: the artist page's "Where next?" ladder is built lazily and kept on disk.
 *
 * A built ladder is kept per profile, per artist and direction ([WhereNextCache.key]) for [WHERE_NEXT_TTL_MS], so a
 * return visit is instant and costs no MusicBrainz call. The book is a plain class over load and save functions and an
 * injected clock, so the rules are unit-tested; [WhereNextLadders] is the live wiring (memory first, then disk).
 */

/** A saved ladder stays good for a week. Refresh on the strip rebuilds it sooner. */
internal const val WHERE_NEXT_TTL_MS = 7L * 24 * 60 * 60 * 1000

/** Ladders kept on disk at once; the oldest are dropped past this. */
internal const val MAX_STORED_WHERE_NEXT = 150

@Serializable
internal data class StoredWhereNext(val savedAtMs: Long, val ready: ArtistLadderOutcome.Ready)

@Serializable
internal data class StoredWhereNextBook(val entries: Map<String, StoredWhereNext> = emptyMap())

internal class WhereNextBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val now: () -> Long = { LibraryClock.nowEpochMs() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun read(): StoredWhereNextBook =
        load()?.let { runCatching { json.decodeFromString<StoredWhereNextBook>(it) }.getOrNull() } ?: StoredWhereNextBook()

    /** The saved ladder under [key], or null when there is none, it is older than a week, or the file is unreadable. */
    fun get(key: String): ArtistLadderOutcome.Ready? {
        val entry = read().entries[key] ?: return null
        return if (now() - entry.savedAtMs in 0..WHERE_NEXT_TTL_MS) entry.ready else null
    }

    fun put(key: String, ready: ArtistLadderOutcome.Ready) {
        val at = now()
        val all = read().entries.filterValues { at - it.savedAtMs in 0..WHERE_NEXT_TTL_MS }.toMutableMap()
        all.remove(key)
        all[key] = StoredWhereNext(at, ready)
        val kept = all.entries.sortedBy { it.value.savedAtMs }.takeLast(MAX_STORED_WHERE_NEXT).associate { it.key to it.value }
        save(json.encodeToString(StoredWhereNextBook(kept)))
    }

    fun remove(key: String) {
        val all = read().entries
        if (key !in all) return
        save(json.encodeToString(StoredWhereNextBook(all - key)))
    }
}

/** Where a ladder comes from for the artist page and the Stage view: memory, then disk, then a build. */
internal object WhereNextLadders {
    private fun book(): WhereNextBook {
        val profile = ProfileRepository.activeProfileId
        return WhereNextBook(
            load = { MusicLibraryStorage.loadWhereNext(profile) },
            save = { MusicLibraryStorage.saveWhereNext(profile, it) },
        )
    }

    /** The ladder kept in memory this session, or null. Cheap enough to call while composing. */
    fun peek(artistId: String, destination: String?): ArtistLadderOutcome.Ready? = WhereNextCache.get(artistId, destination)

    /** Memory, then disk (a hit is brought into memory). Reads the disk, so call it off the main thread. */
    suspend fun cached(artistId: String, destination: String?): ArtistLadderOutcome.Ready? =
        peek(artistId, destination) ?: withContext(Dispatchers.Default) {
            runCatching { book().get(WhereNextCache.key(artistId, destination)) }.getOrNull()
        }?.also { WhereNextCache.put(artistId, destination, it) }

    /**
     * Builds the ladder out from [artist] and keeps a good one in memory and on disk. A [background] build (nobody asked
     * for it yet) queues behind every ordinary MusicBrainz call; one the person asked for does not.
     */
    suspend fun build(artist: LibraryArtist, destination: String?, background: Boolean): ArtistLadderOutcome {
        MusicLibraryRepository.ensureLoaded()
        val result = withContext(if (background) BackgroundWork() else EmptyCoroutineContext) {
            LadderArtistTargets.ladderOutFrom(artist.name, MusicLibraryRepository.state.value.artists, destination)
        }
        if (result is ArtistLadderOutcome.Ready) {
            Logger.withTag("LadderDebug").i { ladderDebugLines(result.ladder, "Where next from ${artist.name}").joinToString("\n") }
            WhereNextCache.put(artist.id, destination, result)
            withContext(Dispatchers.Default) {
                runCatching { book().put(WhereNextCache.key(artist.id, destination), result) }
            }
        }
        return result
    }

    /** Drops the kept ladder so the next [build] starts from scratch (the strip's Refresh). */
    suspend fun forget(artistId: String, destination: String?) {
        WhereNextCache.remove(artistId, destination)
        withContext(Dispatchers.Default) { runCatching { book().remove(WhereNextCache.key(artistId, destination)) } }
    }
}
