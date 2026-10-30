package com.nuvio.app.features.music.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import coil3.PlatformContext
import com.nuvio.app.core.ui.LocalNuvioThemeTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.music.StageGlow
import com.nuvio.app.features.music.identity.ArtworkPalette
import com.nuvio.app.features.music.identity.ArtworkPaletteMath
import com.nuvio.app.features.music.identity.MotionProfile
import com.nuvio.app.features.music.identity.MusicPaletteResolver
import com.nuvio.app.features.music.identity.stageMotionSpec
import com.nuvio.app.features.music.identity.withAccent
import com.nuvio.app.features.music.rememberMusicReducedMotion
import kotlinx.coroutines.CancellationException

/**
 * Ladder phase 9: the room around the ladder while a rung is focused. It takes on the artist's palette (the ground
 * fades to the artist's near-black, everything that reads `colors.accent` below takes their accent, as in
 * [com.nuvio.app.features.music.identity.MusicAccentScope]) and moves at the pace of their [MotionProfile] (the same
 * glow and the same numbers as Now Playing's stage: [stageMotionSpec], [StageGlow]). Reduced motion holds it still.
 *
 * With no [room], or a light theme, the app's own colours stay. The logic that decides what to wear is
 * [AuditionRoom] and [rungMotion]; this is only the drawing.
 */
@Composable
internal fun LadderRoom(
    room: AuditionRoom?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val reduced = rememberMusicReducedMotion()
    // The palettes are built to stand out on a dark surface; on a light theme the app's colours stay.
    val darkSurface = tokens.colors.background.luminance() < 0.5f
    val palette = room?.palette?.takeIf { darkSurface }
    val spec = remember(room?.motion, reduced) { stageMotionSpec(room?.motion ?: MotionProfile.Steady, reduced) }

    val fade = tween<Color>(if (reduced) 0 else FADE_MS)
    val ground by animateColorAsState(palette?.let { Color(it.ground) } ?: tokens.colors.background, fade, label = "roomGround")
    val glow by animateColorAsState(palette?.let { Color(it.accent) } ?: Color.Transparent, fade, label = "roomGlow")

    val themed = remember(tokens, palette) {
        if (palette == null) tokens else tokens.withAccent(Color(palette.accent), Color(palette.onAccent))
    }
    CompositionLocalProvider(LocalNuvioThemeTokens provides themed) {
        Box(modifier.fillMaxSize().background(ground)) {
            if (palette != null) StageGlow(glow, spec)
            content()
        }
    }
}

private const val FADE_MS = 600

/**
 * The colours for a rung's room. A rung is a MusicBrainz artist with no picture of its own, so: the Library's palette when
 * the artist is saved and has one; otherwise the colour sampled from their Deezer picture ([LadderArtwork], the picture
 * the row shows) the same way an artist's own palette is derived. Kept for the session, including "no colour".
 */
internal object LadderRoomPalettes {
    private val palettes = HashMap<String, ArtworkPalette?>()

    suspend fun forRung(context: PlatformContext, name: String, libraryArtists: List<LibraryArtist>): ArtworkPalette? {
        libraryPaletteFor(name, libraryArtists)?.let { return it }
        val key = genreKey(name)
        if (palettes.containsKey(key)) return palettes[key]
        val derived = try {
            LadderArtwork.picture(name)?.let { url ->
                MusicPaletteResolver.sampleOne(context, url)?.let { seed -> ArtworkPaletteMath.derive(seed, forUrl = url) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            null
        }
        palettes[key] = derived
        return derived
    }
}

/** What the room wears for [rung]: their colours (when they can be found) and the pace of their genres or mood. */
internal suspend fun roomFor(context: PlatformContext, rung: LadderRung, libraryArtists: List<LibraryArtist>): AuditionRoom =
    AuditionRoom(
        palette = LadderRoomPalettes.forRung(context, rung.candidate.name, libraryArtists),
        motion = rungMotion(rung.candidate, libraryArtistNamed(rung.candidate.name, libraryArtists)),
    )
