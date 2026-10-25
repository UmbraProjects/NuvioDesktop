package com.nuvio.app.features.music.identity

import androidx.compose.ui.graphics.toArgb
import co.touchlab.kermit.Logger
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.nuvio.app.features.home.extractHeroAccentColor
import com.nuvio.app.features.music.library.MusicLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Derives an artist's [ArtworkPalette] once and caches it in the stored library.
 *
 * The colour is sampled from a small decode of the artist's art by the app's existing hue-bucket
 * sampler ([extractHeroAccentColor], the one Home's hero uses), then clamped into a readable accent
 * by [ArtworkPaletteMath]. Images go through the shared Coil loader, so a picture the screen already
 * showed costs no second download. Nothing here blocks the UI: until it finishes the app simply
 * keeps its own accent.
 */
object MusicPaletteResolver {
    private val log = Logger.withTag("MusicPalette")
    private val lock = Mutex()

    /** Artist + source pairs already tried this session, so a failure is not retried on every recomposition. */
    private val attempted = HashSet<String>()

    private const val SAMPLE_PX = 128

    /** Derives and stores the palette for [identity] if it needs one. Safe to call often and from several places at once. */
    suspend fun ensure(context: PlatformContext, identity: ArtistIdentity) {
        if (!identity.needsPalette) return
        val primary = identity.paletteSources.first()
        val key = "${identity.artistId}|$primary"
        if (!lock.withLock { attempted.add(key) }) return
        try {
            val seed = withContext(Dispatchers.Default) { sample(context, identity.paletteSources) } ?: return
            MusicLibraryRepository.setArtistPalette(identity.artistId, ArtworkPaletteMath.derive(seed, forUrl = primary))
        } catch (cancelled: CancellationException) {
            // Left half-done, so the next call may try again.
            lock.withLock { attempted.remove(key) }
            throw cancelled
        } catch (error: Throwable) {
            log.w { "Palette for ${identity.name} failed: ${error.message}" }
        }
    }

    /** The first source that yields a colour, as `0xRRGGBB`; null when none does (greyscale art, load errors). */
    private suspend fun sample(context: PlatformContext, sources: List<String>): Int? {
        val loader = SingletonImageLoader.get(context)
        for (url in sources) {
            val result = loader.execute(
                ImageRequest.Builder(context).data(url).size(SAMPLE_PX, SAMPLE_PX).build(),
            )
            val image = (result as? SuccessResult)?.image ?: continue
            val color = extractHeroAccentColor(image) ?: continue
            return color.toArgb() and 0xFFFFFF
        }
        return null
    }
}
