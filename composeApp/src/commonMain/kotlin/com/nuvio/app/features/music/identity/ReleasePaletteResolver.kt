package com.nuvio.app.features.music.identity

import co.touchlab.kermit.Logger
import coil3.PlatformContext
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.needsPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Derives a palette for each release cover of an artist, once, and stores them in one write. Uses the
 * same sampler as [MusicPaletteResolver]. Call it from the room / shelf for the releases on screen;
 * it is safe to call often. Pass [keys] to limit it to the releases actually on screen. Covers are done one after another so a big catalogue never floods the loader.
 */
object ReleasePaletteResolver {
    private val log = Logger.withTag("ReleasePalette")
    private val lock = Mutex()
    private val attempted = HashSet<String>()

    suspend fun ensure(context: PlatformContext, artist: LibraryArtist, keys: Set<String>? = null, limit: Int = 60) {
        val todo = artist.releases.filter { it.needsPalette && (keys == null || it.key in keys) }.take(limit)
        if (todo.isEmpty()) return
        val found = LinkedHashMap<String, ArtworkPalette>()
        try {
            for (release in todo) {
                val cover = release.art.cover ?: continue
                if (!lock.withLock { attempted.add("${artist.id}|${release.key}|$cover") }) continue
                val seed = withContext(Dispatchers.Default) { MusicPaletteResolver.sampleOne(context, cover) } ?: continue
                found[release.key] = ArtworkPaletteMath.derive(seed, forUrl = cover)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log.w { "Release palettes for ${artist.name} failed: ${error.message}" }
        } finally {
            if (found.isNotEmpty()) MusicLibraryRepository.setReleasePalettes(artist.id, found)
        }
    }
}
