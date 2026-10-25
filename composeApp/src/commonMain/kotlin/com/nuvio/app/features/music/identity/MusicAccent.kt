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
 * Whose colour: on an artist or album page ([viewedFirst]) the artist being viewed wins, so a room
 * is never painted in the colours of whatever happens to be playing; the mini player and the stage
 * keep their own scope, where the artist who is *playing* wins. With nothing to go by (no viewed
 * artist, nothing playing), an artist with no palette yet, or a light theme, the app's own accent is
 * left alone. The viewed artist is [viewedArtistId], or the owner of [viewedAlbumBrowseId]. The palette is derived in the background the first time it is
 * needed and then cached in the library (an artist only previewed from Search keeps it for the session).
 */
@Composable
fun MusicAccentScope(
    viewedArtistId: String? = null,
    viewedAlbumBrowseId: String? = null,
    viewedFirst: Boolean = false,
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

    val identity = remember(nowPlaying, library.artists, library.preview, viewedArtistId, viewedAlbumBrowseId, viewedFirst) {
        accentArtist(
            playing = playingArtist(nowPlaying.track, nowPlaying.album, library),
            viewed = viewedArtist(library, viewedArtistId, viewedAlbumBrowseId),
            viewedFirst = viewedFirst,
        )?.identity()
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

/** The artist (saved, else previewed) of the track or album now playing, matched by channel id and then by name. */
internal fun playingArtist(track: MusicTrack?, album: MusicAlbum?, library: MusicLibraryState): LibraryArtist? {
    val refs = track?.artists.orEmpty() + album?.artists.orEmpty()
    refs.forEach { ref ->
        ref.browseId?.let(library::artistOrPreview)?.let { return it }
    }
    refs.forEach { ref ->
        (library.artists + library.preview.values).firstOrNull { it.name.equals(ref.name, ignoreCase = true) }?.let { return it }
    }
    return null
}

/** Whose accent a scope wears: the viewed artist first on a page ([viewedFirst]), the playing one first elsewhere. */
internal fun accentArtist(playing: LibraryArtist?, viewed: LibraryArtist?, viewedFirst: Boolean): LibraryArtist? =
    if (viewedFirst) viewed ?: playing else playing ?: viewed

internal fun viewedArtist(library: MusicLibraryState, artistId: String?, albumBrowseId: String?): LibraryArtist? =
    artistId?.let(library::artistOrPreview) ?: albumBrowseId?.let(library::artistOfAlbum)

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
