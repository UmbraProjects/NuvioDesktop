package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.nuvio.app.features.trailer.TrailerPlaybackSource

/**
 * fork:innertubex — a second way to find a track's audio stream, tried only after the in-app
 * extractor has given up on it. The implementation lives in its own source folder that is compiled
 * in only with `-Pnuvio.innertubex=true` (it needs a library from JitPack), so [MusicResolver] reaches
 * it through this interface and finds it by name at runtime. A build without it simply has none.
 */
internal interface MusicStreamFallback {
    /** Shown in the log. */
    val name: String

    /** An audio stream for [videoId], or null when this resolver cannot find one either. */
    suspend fun resolve(videoId: String): TrailerPlaybackSource?
}

internal object MusicStreamFallbacks {
    private const val IMPLEMENTATION = "com.nuvio.app.features.music.InnerTubeXFallback"

    val active: MusicStreamFallback? by lazy { load(IMPLEMENTATION) }

    internal fun load(className: String): MusicStreamFallback? =
        try {
            Class.forName(className).getField("INSTANCE").get(null) as? MusicStreamFallback
        } catch (_: ClassNotFoundException) {
            null
        } catch (error: Throwable) {
            Logger.withTag("MusicStreamFallbacks").w { "could not load $className: ${error.message}" }
            null
        }
}
