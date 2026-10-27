package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.Lyrics
import com.nuvio.app.features.music.LyricsRepository
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.formatMusicTime
import com.nuvio.app.features.music.identity.ArtworkPalette
import com.nuvio.app.features.music.identity.MotionProfile
import com.nuvio.app.features.music.identity.motionProfile

/**
 * Ladder phase 9: audition. Arrowing over a rung plays the artist from the hook ([findHook], phase 8) and the room
 * takes on the artist's palette and [MotionProfile]. This file is the logic; [MusicLadderScreen] and [LadderRoom] draw it.
 *
 * What plays: the artist's album as phase 6 picks it ([resolveRungAlbum]), and on it the first real song ([pickAuditionTrack]:
 * an intro, interlude or skit is passed over, because a hook found in a 40-second intro is not the sound of the artist).
 * Its synced lyric (LRCLIB, as Now Playing uses) gives the chorus time; with no synced lyric the fixed offset is used.
 * The song is opened at that time ([com.nuvio.app.features.music.MusicPlayer.play] with a start position), so the
 * listener never hears the intro first. After the hook the song simply carries on.
 */

/** The pause on a rung before it plays, so holding an arrow key does not open a song on every rung it passes. */
internal const val AUDITION_SETTLE_MS = 450L

/** A track shorter than this is taken for an intro or skit even when its title does not say so. */
internal const val AUDITION_MIN_TRACK_SECONDS = 90

private val FILLER_TITLE = Regex("\\b(intro|introduction|interlude|outro|skit|prelude|overture|instrumental|spoken)\\b", RegexOption.IGNORE_CASE)

/** True when a track title names a piece that is not a song to audition ("Intro", "Interlude (Part 2)", "Skit"). */
internal fun isFillerTrack(title: String): Boolean = FILLER_TITLE.containsMatchIn(title)

/**
 * Index in [tracks] of the song to audition: the first available track that is not filler and not a sliver
 * ([AUDITION_MIN_TRACK_SECONDS], when its length is known); failing that the first available song that is not filler;
 * failing that the first available track. -1 when nothing can play.
 */
internal fun pickAuditionTrack(tracks: List<MusicTrack>): Int {
    val available = tracks.indices.filter { tracks[it].isAvailable }
    return available.firstOrNull { i ->
        val t = tracks[i]
        !isFillerTrack(t.title) && (t.durationSeconds <= 0 || t.durationSeconds >= AUDITION_MIN_TRACK_SECONDS)
    }
        ?: available.firstOrNull { !isFillerTrack(tracks[it].title) }
        ?: available.firstOrNull()
        ?: -1
}

/**
 * The rung under the cursor after an arrow key: [delta] (+1 down, -1 up) steps from [current], stopping at the first and
 * last rung (it does not wrap, so a held key rests at the end). With nothing focused yet, either direction lands on the
 * first rung. -1 when there are no rungs.
 */
internal fun moveFocus(current: Int, delta: Int, count: Int): Int {
    if (count <= 0) return -1
    if (current !in 0 until count) return 0
    return (current + delta).coerceIn(0, count - 1)
}

internal sealed interface RungAudition {
    /** [hook] is where the song was opened ([HookSource.Chorus] when its synced lyric gave a chorus, else the fixed offset). */
    data class Playing(
        val albumTitle: String,
        val trackTitle: String,
        val artistId: String,
        val hook: Hook,
    ) : RungAudition

    /** No YouTube Music artist by that name, or no release or track that would open. */
    data object NotPlayable : RungAudition
}

/**
 * Starts the rung's artist at the hook of their first real song. [lyricsFor] looks the lyric up (the live one asks
 * LRCLIB through [LyricsRepository], which remembers misses); a lookup that throws counts as no lyric. [play] receives
 * the album, the track index and the start position (the live one hands them to the music player).
 */
internal suspend fun auditionRung(
    catalog: MusicCatalog,
    name: String,
    libraryArtists: List<LibraryArtist>,
    topAlbumId: (LibraryArtist) -> String? = { a -> selectTopAlbums(a).releases.firstNotNullOfOrNull { it.ytBrowseId } },
    lyricsFor: suspend (MusicTrack, MusicAlbum) -> Lyrics? = { track, album -> LyricsRepository.forTrack(track, album) },
    play: (album: MusicAlbum, trackIndex: Int, startMs: Long) -> Unit = { album, index, startMs ->
        MusicPlayback.player.play(album, trackIndex = index, startPositionMs = startMs)
    },
): RungAudition {
    val resolved = resolveRungAlbum(catalog, name, libraryArtists, topAlbumId) ?: return RungAudition.NotPlayable
    val album = resolved.album
    val index = pickAuditionTrack(album.tracks)
    if (index < 0) return RungAudition.NotPlayable
    val track = album.tracks[index]
    val lyrics = runCatching { lyricsFor(track, album) }.getOrNull()
    val hook = hookFor(lyrics, track.durationSeconds * 1000L)
    play(album, index, hook.startMs)
    return RungAudition.Playing(album.title, track.title, resolved.artist.browseId, hook)
}

/** The line under a rung while it plays or when it could not: what song, from when, and whether a chorus was found. */
internal fun auditionNote(result: RungAudition): String = when (result) {
    RungAudition.NotPlayable -> "No release to play here"
    is RungAudition.Playing -> {
        val from = formatMusicTime(result.hook.startMs)
        when (result.hook.source) {
            HookSource.Chorus -> "${result.trackTitle} · chorus from $from"
            HookSource.FixedOffset -> "${result.trackTitle} · from $from (no chorus found)"
        }
    }
}

// region the room

/** What the room wears while a rung is focused: the artist's colours (null = keep the app's) and how it moves. */
internal data class AuditionRoom(val palette: ArtworkPalette?, val motion: MotionProfile)

/** The rung's genres as readable names, most voted first, at most [limit]. */
internal fun topRungGenres(candidate: LadderCandidate, limit: Int = 4): List<String> =
    normalizeWeightedGenres(candidate.votes).keys.take(limit)

/**
 * How lively the room feels for a rung: from the candidate's mood and style when the source gave them, else from its
 * genres, joined with what the Library knows when the artist is saved. Same rules as an artist's own room
 * ([motionProfile]); no signal gives [MotionProfile.Steady].
 */
internal fun rungMotion(candidate: LadderCandidate, saved: LibraryArtist? = null): MotionProfile =
    motionProfile(
        mood = candidate.mood ?: saved?.mood,
        style = candidate.style ?: saved?.style,
        genres = topRungGenres(candidate) + saved?.genres.orEmpty(),
    )

/** The Library's palette for the artist called [name], when they are saved and one has been derived; matched by name. */
internal fun libraryPaletteFor(name: String, artists: List<LibraryArtist>): ArtworkPalette? =
    libraryArtistNamed(name, artists)?.palette

internal fun libraryArtistNamed(name: String, artists: List<LibraryArtist>): LibraryArtist? =
    artists.firstOrNull { sameArtistName(it.name, name) }

// endregion
