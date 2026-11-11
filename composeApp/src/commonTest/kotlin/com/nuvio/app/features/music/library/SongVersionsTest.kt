package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongVersionsTest {
    private val titles = listOf(
        "Just Another Story", "Space Cowboy", "Light Years",
        "Space Cowboy (David Morales Mix)", "Space Cowboy (Demo Version)", "Light Years (Live)",
    )
    private val durations = listOf(530, 387, 355, 471, 259, 358)

    private fun release(title: String, kind: ReleaseKind, deezer: Long? = null) =
        LibraryRelease(key = title, title = title, kind = kind, ytBrowseId = "MPREb_$title", deezerId = deezer)

    @Test
    fun kindsComeFromTheSuffix() {
        assertEquals(VersionKind.Remix, versionKind("Space Cowboy (David Morales Mix)"))
        assertEquals(VersionKind.Demo, versionKind("Space Cowboy (Demo Version)"))
        assertEquals(VersionKind.Live, versionKind("Light Years - Live"))
        assertEquals(VersionKind.Instrumental, versionKind("Morning Glory (Instrumental)"))
        assertEquals(VersionKind.Album, versionKind("Space Cowboy"))
        assertEquals(VersionKind.Album, versionKind("Song (feat. Someone)"))
    }

    @Test
    fun theAlbumsOwnCopiesComeFirstInTrackOrder() {
        val v = songVersions(1, titles, durations, "The Return of the Space Cowboy", emptyList(), emptyList())
        assertEquals(listOf(3, 4), v.map { it.trackIndex })
        assertEquals(listOf(VersionKind.Remix, VersionKind.Demo), v.map { it.kind })
        assertEquals(471, v[0].durationSeconds)
    }

    @Test
    fun aRemixFindsThePlainSongToo() {
        val v = songVersions(3, titles, durations, "Album", emptyList(), emptyList())
        assertEquals(listOf(1, 4), v.map { it.trackIndex })
        assertEquals(VersionKind.Version, v[0].kind) // the plain song, seen from its remix
    }

    @Test
    fun singlesAndOtherRecordsFollowOncePerRecord() {
        val hits = release("High Times: Singles 1992-2006", ReleaseKind.Album, deezer = 7)
        val single = release("Space Cowboy", ReleaseKind.Single)
        val top = listOf(
            DeezerTopSong("Space Cowboy", "High Times: Singles 1992-2006", 7, 900),
            DeezerTopSong("Space Cowboy", "High Times: Singles 1992-2006", 7, 400),
            DeezerTopSong("Space Cowboy", "The Return of the Space Cowboy", 3, 800),
            DeezerTopSong("Virtual Insanity", "Travelling Without Moving", 9, 999),
        )
        val v = songVersions(1, titles, durations, "The Return of the Space Cowboy", top, listOf(hits, single))
        assertEquals(4, v.size)
        assertEquals(VersionKind.Single, v[2].kind)
        assertEquals(hits, v[3].release)
        assertEquals(VersionKind.Album, v[3].kind)
        val counts = versionCounts(v)
        assertEquals(VersionCounts(found = 4, onThisAlbum = 2, elsewhere = 2, reworks = 2), counts)
    }

    @Test
    fun aSongWithNoOtherCopiesHasNone() {
        assertTrue(songVersions(0, titles, durations, "Album", emptyList(), emptyList()).isEmpty())
        assertTrue(songVersions(99, titles, durations, "Album", emptyList(), emptyList()).isEmpty())
    }

    // fork:lenses - "2001": the explicit album lists its censored edition under YouTube Music's "Other versions".
    private val clean = AlbumEditionTracks(
        browseId = "MPREb_clean", title = "2001", explicit = false,
        trackTitles = listOf("Lolo (Intro)", "The Watcher", "Still D.R.E. (feat. Snoop Dogg)"), trackDurations = listOf(40, 206, 274),
    )

    @Test
    fun theCleanEditionOffersTheSameSong() {
        val tracks = listOf("Lolo (Intro)", "The Watcher", "Still D.R.E.")
        val v = songVersions(2, tracks, listOf(40, 206, 270), "2001", emptyList(), emptyList(), editions = listOf(clean), thisExplicit = true)
        val copy = v.single()
        assertEquals(VersionKind.Clean, copy.kind)
        assertEquals("MPREb_clean", copy.editionId)
        assertEquals(2, copy.editionTrack)
        assertEquals(274, copy.durationSeconds)
        assertTrue(copy.recordTitle!!.startsWith("Clean edition"))
        assertEquals(VersionCounts(found = 1, onThisAlbum = 0, elsewhere = 1, reworks = 0), versionCounts(v))
    }

    @Test
    fun editionKindsFollowTheBadgeAndTitle() {
        assertEquals(VersionKind.Explicit, editionKind(clean.copy(explicit = true), thisExplicit = false))
        assertEquals(VersionKind.Edition, editionKind(clean.copy(title = "2001 (Deluxe)", explicit = true), thisExplicit = true))
        assertEquals(VersionKind.Clean, editionKind(clean.copy(title = "2001 (Edited)", explicit = true), thisExplicit = true))
        assertEquals(VersionKind.Edition, editionKind(clean, thisExplicit = false))
    }

    @Test
    fun anEditionWithoutTheSongAddsNothing() {
        val v = songVersions(0, listOf("Forgot About Dre"), listOf(222), "2001", emptyList(), emptyList(), editions = listOf(clean), thisExplicit = true)
        assertTrue(v.isEmpty())
    }
}
