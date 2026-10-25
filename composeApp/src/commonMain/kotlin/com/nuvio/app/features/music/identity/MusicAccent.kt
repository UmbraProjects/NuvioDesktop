package com.nuvio.app.features.music.identity

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.NuvioThemeTokens
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.library.LibraryArtist
import com.nuvio.app.features.music.library.MusicLibraryRepository
import com.nuvio.app.features.music.library.MusicLibraryState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The identity whose colours the surrounding [MusicAccentScope] is wearing; null when it is wearing the app's own. */
val LocalArtistIdentity = staticCompositionLocalOf<ArtistIdentity?> { null }

/**
 * Re-tints everything inside with one artist's accent: every `colors.accent`, `accentFill` and
 * `onAccent` read below this point (primary buttons, the mini player's progress line and active
 * icons, links) takes the artist's colour, with no change at the call sites.
 *
 * Whose colour: the artist who is *playing* wins, so the mini player and the page buttons always
 * agree; with nothing playing it is the artist being viewed ([viewedArtistId], or the owner of
 * [viewedAlbumBrowseId]). With neither, or an artist with no palette yet, or a light theme, the
 * app's own accent is left alone. The palette is derived in the background the first time it is
 * needed and then cached in the library.
 */
@Composable
fun MusicAccentScope(
    viewedArtistId: String? = null,
    viewedAlbumBrowseId: String? = null,
    content: @Composable () -> Unit,
) {
    // Only the track and album matter here; collecting the whole state would recompose on every position tick.
    val nowPlaying by remember {
        MusicPlayback.player.state
            .map { if (it.isActive) NowPlaying(it.track, it.album) else NowPlaying(null, null) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(NowPlaying(null, null))
    val library by MusicLibraryRepository.state.collectAsStateWithLifecycle()
    val context = LocalPlatformContext.current

    LaunchedEffect(Unit) { MusicLibraryRepository.ensureLoaded() }

    val identity = remember(nowPlaying, library.artists, viewedArtistId, viewedAlbumBrowseId) {
        (playingArtist(nowPlaying.track, nowPlaying.album, library) ?: viewedArtist(library, viewedArtistId, viewedAlbumBrowseId))?.identity()
    }

    LaunchedEffect(identity?.artistId, identity?.needsPalette) {
        if (identity != null) MusicPaletteResolver.ensure(context, identity)
    }

    val tokens = MaterialTheme.nuvio
    val palette = identity?.palette
    // The palette is built to stand out on a dark surface; on a light theme the app's accent stays.
    val darkSurface = tokens.colors.background.luminance() < 0.5f
    val themed = remember(tokens, palette, darkSurface) {
        if (palette == null || !darkSurface) tokens else tokens.withAccent(Color(palette.accent), Color(palette.onAccent))
    }
    CompositionLocalProvider(
        LocalNuvioThemeTokens provides themed,
        LocalArtistIdentity provides identity.takeIf { themed !== tokens },
        content = content,
    )
}

private data class NowPlaying(val track: MusicTrack?, val album: MusicAlbum?)

/** The library artist of the track or album now playing, matched by channel id and then by name. */
internal fun playingArtist(track: MusicTrack?, album: MusicAlbum?, library: MusicLibraryState): LibraryArtist? {
    val refs = track?.artists.orEmpty() + album?.artists.orEmpty()
    refs.forEach { ref ->
        ref.browseId?.let(library::artist)?.let { return it }
    }
    refs.forEach { ref ->
        library.artists.firstOrNull { it.name.equals(ref.name, ignoreCase = true) }?.let { return it }
    }
    return null
}

internal fun viewedArtist(library: MusicLibraryState, artistId: String?, albumBrowseId: String?): LibraryArtist? =
    artistId?.let(library::artist) ?: albumBrowseId?.let(library::artistOfAlbum)

/** These tokens with the accent family swapped for one artist's colours; everything else, focus ring included, is untouched. */
internal fun NuvioThemeTokens.withAccent(accent: Color, onAccent: Color): NuvioThemeTokens = copy(
    colors = colors.copy(
        accent = accent,
        accentStrong = accent,
        accentFill = SolidColor(accent),
        accentGradientEnd = null,
        onAccent = onAccent,
        selectionTextFallback = null,
        borderSelected = accent.copy(alpha = NuvioTokens.Opacity.strong),
        playerTimelineFill = accent,
        playerBuffering = accent,
    ),
)
