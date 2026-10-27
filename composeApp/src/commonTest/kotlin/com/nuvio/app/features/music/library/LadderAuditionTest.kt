package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.LyricLine
import com.nuvio.app.features.music.Lyrics
import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtist
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.identity.ArtworkPalette
import com.nuvio.app.features.music.identity.MotionProfile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderAuditionTest {
    private fun track(id: String, title: String, seconds: Int = 200, available: Boolean = true) =
        MusicTrack(videoId = id, title = title, artists = emptyList(), durationSeconds = seconds, trackNumber = 1, isAvailable = available)

    private fun album(tracks: List<MusicTrack>, id: String = "a2", title: String = "Souvlaki") =
        MusicAlbum(browseId = id, title = title, artists = emptyList(), year = null, kind = "Album", thumbnailUrl = null, tracks = tracks, audioPlaylistId = null)

    // region the track

    @Test
    fun introsInterludesAndSkitsAreFiller() {
        assertTrue(isFillerTrack("Intro"))
        assertTrue(isFillerTrack("Interlude (Part 2)"))
        assertTrue(isFillerTrack("the skit"))
        assertTrue(isFillerTrack("Dawn - Instrumental"))
        assertTrue(!isFillerTrack("Alison"))
        // Whole words only.
        assertTrue(!isFillerTrack("Introvert"))
        assertTrue(!isFillerTrack("Outrun"))
    }

    @Test
    fun theFirstRealSongIsPicked() {
        val tracks = listOf(track("1", "Intro", 45), track("2", "Alison", 220), track("3", "Machine Gun", 270))
        assertEquals(1, pickAuditionTrack(tracks))
    }

    @Test
    fun aShortTrackWithAPlainTitleIsPassedOver() {
        val tracks = listOf(track("1", "Waking", 40), track("2", "Souvlaki Space Station", 360))
        assertEquals(1, pickAuditionTrack(tracks))
    }

    @Test
    fun anUnknownLengthIsNotHeldAgainstATrack() {
        assertEquals(0, pickAuditionTrack(listOf(track("1", "Alison", 0), track("2", "Other", 200))))
    }

    @Test
    fun unavailableTracksAreNeverPicked() {
        val tracks = listOf(track("1", "Alison", 220, available = false), track("2", "Machine Gun", 270))
        assertEquals(1, pickAuditionTrack(tracks))
    }

    @Test
    fun whenOnlyFillerPlaysItIsUsedAndWhenNothingPlaysItIsMinusOne() {
        // All short and all filler: the first available track still plays, rather than nothing.
        assertEquals(0, pickAuditionTrack(listOf(track("1", "Intro", 30), track("2", "Outro", 30))))
        // A real song that is only short beats filler.
        assertEquals(1, pickAuditionTrack(listOf(track("1", "Intro", 30), track("2", "Tiny Song", 60))))
        assertEquals(-1, pickAuditionTrack(listOf(track("1", "Alison", 220, available = false))))
        assertEquals(-1, pickAuditionTrack(emptyList()))
    }

    // endregion

    // region keys

    @Test
    fun arrowsStepOneRungAndStopAtTheEnds() {
        assertEquals(1, moveFocus(0, +1, 5))
        assertEquals(0, moveFocus(1, -1, 5))
        assertEquals(4, moveFocus(4, +1, 5))
        assertEquals(0, moveFocus(0, -1, 5))
    }

    @Test
    fun withNothingFocusedEitherArrowLandsOnTheFirstRung() {
        assertEquals(0, moveFocus(-1, +1, 5))
        assertEquals(0, moveFocus(-1, -1, 5))
        assertEquals(0, moveFocus(9, +1, 5))
    }

    @Test
    fun noRungsMeansNoFocus() {
        assertEquals(-1, moveFocus(-1, +1, 0))
        assertEquals(-1, moveFocus(0, -1, 0))
    }

    // endregion

    // region audition

    private class Fake(
        val artists: List<MusicArtistSummary> = emptyList(),
        val releases: List<MusicAlbumSummary> = emptyList(),
        val albums: Map<String, MusicAlbum> = emptyMap(),
    ) : MusicCatalog {
        override suspend fun searchAlbums(query: String) = emptyList<MusicAlbumSummary>()
        override suspend fun searchArtists(query: String) = artists
        override suspend fun album(browseId: String): MusicAlbum? = albums[browseId]
        override suspend fun artist(browseId: String): MusicArtist? = null
        override suspend fun artistReleases(browseId: String) = releases
    }

    private val slowdive = MusicArtistSummary("UC1", "Slowdive", null)
    private fun summary(id: String) = MusicAlbumSummary(browseId = id, title = id, artists = emptyList(), year = null, kind = "Album", thumbnailUrl = null)

    private val chorusLyrics = Lyrics.Synced(
        listOf(
            LyricLine(10_000L, "A verse that opens the song"),
            LyricLine(20_000L, "Never let go of the light"),
            LyricLine(24_000L, "A bridge between the two"),
            LyricLine(40_000L, "Never let go of the light"),
            LyricLine(60_000L, "Never let go of the light"),
        ),
    )

    private class Played(var album: MusicAlbum? = null, var index: Int = -1, var startMs: Long = -1L)

    @Test
    fun theSongOpensAtTheChorusOfItsSyncedLyric() = runBlocking<Unit> {
        val tracks = listOf(track("1", "Intro", 45), track("2", "Alison", 220))
        val cat = Fake(listOf(slowdive), listOf(summary("a2")), mapOf("a2" to album(tracks)))
        val played = Played()
        var lyricFor: String? = null
        val out = auditionRung(
            cat, "Slowdive", emptyList(),
            lyricsFor = { t, _ -> lyricFor = t.videoId; chorusLyrics },
            play = { a, i, ms -> played.album = a; played.index = i; played.startMs = ms },
        )
        val playing = assertIs<RungAudition.Playing>(out)
        assertEquals("2", lyricFor)
        assertEquals(1, played.index)
        assertEquals(HookSource.Chorus, playing.hook.source)
        assertEquals(20_000L - HOOK_LEAD_IN_MS, played.startMs)
        assertEquals(playing.hook.startMs, played.startMs)
        assertEquals("Alison", playing.trackTitle)
        assertEquals("Souvlaki", playing.albumTitle)
        assertEquals("UC1", playing.artistId)
    }

    @Test
    fun noLyricFallsBackToTheFixedOffsetOfTheTrack() = runBlocking<Unit> {
        val cat = Fake(listOf(slowdive), listOf(summary("a2")), mapOf("a2" to album(listOf(track("1", "Alison", 200)))))
        val played = Played()
        val out = auditionRung(cat, "Slowdive", emptyList(), lyricsFor = { _, _ -> null }, play = { _, i, ms -> played.index = i; played.startMs = ms })
        val playing = assertIs<RungAudition.Playing>(out)
        assertEquals(HookSource.FixedOffset, playing.hook.source)
        // 30% of 200 s.
        assertEquals(60_000L, played.startMs)
    }

    @Test
    fun plainLyricsAreNotSearchedForAChorus() = runBlocking<Unit> {
        val cat = Fake(listOf(slowdive), listOf(summary("a2")), mapOf("a2" to album(listOf(track("1", "Alison", 200)))))
        val out = auditionRung(
            cat, "Slowdive", emptyList(),
            lyricsFor = { _, _ -> Lyrics.Plain(listOf("Hold on", "Hold on", "Hold on")) },
            play = { _, _, _ -> },
        )
        assertEquals(HookSource.FixedOffset, assertIs<RungAudition.Playing>(out).hook.source)
    }

    @Test
    fun aLyricLookupThatThrowsStillAuditions() = runBlocking<Unit> {
        val cat = Fake(listOf(slowdive), listOf(summary("a2")), mapOf("a2" to album(listOf(track("1", "Alison", 200)))))
        val played = Played()
        val out = auditionRung(cat, "Slowdive", emptyList(), lyricsFor = { _, _ -> error("offline") }, play = { _, _, ms -> played.startMs = ms })
        assertEquals(HookSource.FixedOffset, assertIs<RungAudition.Playing>(out).hook.source)
        assertEquals(60_000L, played.startMs)
    }

    @Test
    fun aSavedArtistAuditionsItsTopAlbum() = runBlocking<Unit> {
        val cat = Fake(
            listOf(slowdive), listOf(summary("a2")),
            mapOf("top" to album(listOf(track("9", "Catch the Breeze", 300)), "top", "Just for a Day"), "a2" to album(listOf(track("1", "Alison", 200)))),
        )
        val saved = LibraryArtist(id = "UC1", name = "Slowdive")
        val played = Played()
        val out = auditionRung(cat, "Slowdive", listOf(saved), topAlbumId = { "top" }, lyricsFor = { _, _ -> null }, play = { a, _, _ -> played.album = a })
        assertEquals("Just for a Day", assertIs<RungAudition.Playing>(out).albumTitle)
        assertEquals("top", played.album?.browseId)
    }

    @Test
    fun noArtistOrNoPlayableTrackIsNotPlayableAndNothingPlays() = runBlocking<Unit> {
        var plays = 0
        val none = auditionRung(Fake(), "Slowdive", emptyList(), lyricsFor = { _, _ -> null }, play = { _, _, _ -> plays++ })
        assertEquals(RungAudition.NotPlayable, none)
        val greyed = Fake(listOf(slowdive), listOf(summary("a2")), mapOf("a2" to album(listOf(track("1", "Alison", 200, available = false)))))
        assertEquals(RungAudition.NotPlayable, auditionRung(greyed, "Slowdive", emptyList(), lyricsFor = { _, _ -> null }, play = { _, _, _ -> plays++ }))
        assertEquals(0, plays)
    }

    @Test
    fun theNoteSaysWhatPlaysFromWhenAndHowTheTimeWasFound() {
        val chorus = RungAudition.Playing("Souvlaki", "Alison", "UC1", Hook(18_800L, 12_000L, HookSource.Chorus, lineIndex = 1, lineCount = 1))
        assertEquals("Alison · chorus from 0:18", auditionNote(chorus))
        val fixed = RungAudition.Playing("Souvlaki", "Alison", "UC1", Hook(72_000L, 20_000L, HookSource.FixedOffset))
        assertEquals("Alison · from 1:12 (no chorus found)", auditionNote(fixed))
        assertEquals("No release to play here", auditionNote(RungAudition.NotPlayable))
    }

    // endregion

    // region the room

    private fun candidate(votes: Map<String, Int>, mood: String? = null, style: String? = null) =
        LadderCandidate(id = "c", name = "Candidate", votes = votes, mood = mood, style = style)

    @Test
    fun theRoomMovesFromTheCandidatesMoodThenItsGenres() {
        assertEquals(MotionProfile.Driven, rungMotion(candidate(mapOf("shoegaze" to 5), mood = "Aggressive")))
        assertEquals(MotionProfile.Calm, rungMotion(candidate(mapOf("ambient" to 9, "drone" to 4))))
        assertEquals(MotionProfile.Driven, rungMotion(candidate(mapOf("post-punk" to 3, "hardcore" to 8))))
        assertEquals(MotionProfile.Steady, rungMotion(candidate(mapOf("shoegaze" to 5, "dream pop" to 4))))
    }

    @Test
    fun theSavedArtistsMoodFillsAGapInTheCandidate() {
        val saved = LibraryArtist(id = "UC1", name = "Candidate", mood = "Melancholy")
        assertEquals(MotionProfile.Calm, rungMotion(candidate(mapOf("shoegaze" to 5)), saved))
        // The candidate's own mood wins over the saved one.
        assertEquals(MotionProfile.Driven, rungMotion(candidate(mapOf("shoegaze" to 5), mood = "Furious"), saved))
    }

    @Test
    fun theLibraryPaletteIsFoundByNameAndOnlyWhenOneWasDerived() {
        val palette = ArtworkPalette(accent = 0xFFAA66CC, onAccent = 0xFF000000, tint = 0xFF332244, ground = 0xFF0E0A12)
        val artists = listOf(LibraryArtist(id = "1", name = "Slowdive", palette = palette), LibraryArtist(id = "2", name = "Ride"))
        assertEquals(palette, libraryPaletteFor("slowdive", artists))
        assertNull(libraryPaletteFor("Ride", artists))
        assertNull(libraryPaletteFor("Lush", artists))
    }

    @Test
    fun topGenresAreReadableAndOrderedByVotes() {
        val c = candidate(mapOf("shoegaze" to 3, "Dream-Pop" to 9, "seen live" to 50, "noise pop" to 1))
        assertEquals(listOf("dream pop", "shoegaze", "noise pop"), topRungGenres(c))
        assertEquals(listOf("dream pop"), topRungGenres(c, limit = 1))
    }

    // endregion
}
