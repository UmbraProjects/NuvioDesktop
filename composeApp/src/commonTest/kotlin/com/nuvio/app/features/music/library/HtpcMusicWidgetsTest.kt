package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtist
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicCatalog
import com.nuvio.app.features.music.MusicTrack
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pure helpers behind the HTPC layout: artist page widgets, the "Where next?" strip, and the ladder playlist. */
class HtpcMusicWidgetsTest {
    private fun release(key: String) = LibraryRelease(key = key, title = key, kind = ReleaseKind.Album, year = "2020", ytBrowseId = "MPREb_$key")

    // region ArtistWidgets

    @Test
    fun ratingShowsOneDecimalAndNothingWithoutOne() {
        assertNull(ratingText(null))
        assertNull(ratingText(0.0))
        assertEquals("7.3", ratingText(7.26))
        assertEquals("8.0", ratingText(8.0))
    }

    @Test
    fun topReleasesFollowTheRankSkipMissingKeysAndStopAtTheLimit() {
        val artist = LibraryArtist(
            id = "a", name = "a",
            releases = listOf(release("r1"), release("r2"), release("r3"), release("r4")),
            topAlbumKeys = listOf("r3", "gone", "r1", "r3", "r2", "r4"),
        )
        assertEquals(listOf("r3", "r1", "r2"), artistTopReleases(artist, limit = 3).map { it.key })
        assertEquals(listOf("r3", "r1", "r2", "r4"), artistTopReleases(artist).map { it.key })
    }

    @Test
    fun noRankedAlbumsGivesNoTopReleases() {
        assertTrue(artistTopReleases(LibraryArtist(id = "a", name = "a", releases = listOf(release("r1")))).isEmpty())
    }

    @Test
    fun topAlbumPlayLabelSaysPlayOrWhyNot() {
        assertEquals("Play", topAlbumPlayLabel(release("r1")))
        assertEquals("Not on YouTube Music", topAlbumPlayLabel(release("r1").copy(ytBrowseId = null)))
    }

    // endregion

    // region WhereNextStrip

    @Test
    fun similarInLibraryListsTheClosestSoundsAndNotTheArtistItself() {
        val a = LibraryArtist(id = "a", name = "a", genres = listOf("shoegaze", "dream pop"))
        val b = LibraryArtist(id = "b", name = "b", genres = listOf("shoegaze", "ambient"))
        val c = LibraryArtist(id = "c", name = "c", genres = listOf("death metal", "thrash metal"))
        assertEquals(listOf("b"), similarInLibrary(a, listOf(a, b, c)).map { it.id })
        assertTrue(similarInLibrary(c, listOf(a, b, c)).isEmpty())
    }

    @Test
    fun whereNextCacheKeepsOneLadderPerArtistAndDirectionIgnoringSpelling() {
        val target = ArtistTarget(LadderCandidate(id = "a", name = "a", votes = emptyMap()), "shoegaze", listOf("shoegaze"))
        val ready = ArtistLadderOutcome.Ready(target, Ladder("jazz", emptyList(), true), LadderMode.OutFrom, "jazz")
        assertNull(WhereNextCache.get("cache-artist", "jazz"))
        WhereNextCache.put("cache-artist", "jazz", ready)
        assertSame(ready, WhereNextCache.get("cache-artist", "Jazz"))
        assertNull(WhereNextCache.get("cache-artist", "soul"))
        assertNull(WhereNextCache.get("cache-artist", null))
        assertNull(WhereNextCache.get("other-artist", "jazz"))
    }

    // endregion

    // region playRungsAsPlaylist

    private fun track() = MusicTrack(videoId = "v", title = "t", artists = emptyList(), durationSeconds = 1, trackNumber = 1, isAvailable = true)

    private fun album(id: String) =
        MusicAlbum(browseId = id, title = "title $id", artists = emptyList(), year = null, kind = "Album", thumbnailUrl = null, tracks = listOf(track()), audioPlaylistId = null)

    private class Catalog(
        val artists: List<MusicArtistSummary>,
        val releases: Map<String, List<MusicAlbumSummary>>,
        val albums: Map<String, MusicAlbum>,
    ) : MusicCatalog {
        override suspend fun searchAlbums(query: String) = emptyList<MusicAlbumSummary>()
        override suspend fun searchArtists(query: String) = artists
        override suspend fun album(browseId: String): MusicAlbum? = albums[browseId]
        override suspend fun artist(browseId: String): MusicArtist? = null
        override suspend fun artistReleases(browseId: String) = releases[browseId].orEmpty()
    }

    private fun summary(id: String) = MusicAlbumSummary(browseId = id, title = id, artists = emptyList(), year = null, kind = "Album", thumbnailUrl = null)

    private val catalog = Catalog(
        artists = listOf(MusicArtistSummary("UC1", "Ride", null), MusicArtistSummary("UC2", "Slowdive", null), MusicArtistSummary("UC3", "Lush", null)),
        releases = mapOf("UC1" to listOf(summary("alb1")), "UC2" to listOf(summary("alb2")), "UC3" to emptyList()),
        albums = mapOf("alb1" to album("alb1"), "alb2" to album("alb2")),
    )

    @Test
    fun theLadderPlaysAsOnePlaylistInRungOrderSkippingRungsWithNothingToPlay() = runBlocking {
        var played: List<MusicAlbum>? = null
        val queued = playRungsAsPlaylist(catalog, listOf("Slowdive", "Lush", "Nobody", "Ride"), emptyList()) { played = it }
        assertEquals(2, queued)
        assertEquals(listOf("alb2", "alb1"), played?.map { it.browseId })
    }

    @Test
    fun aLadderWithNothingPlayableStartsNothing() = runBlocking {
        var called = false
        val queued = playRungsAsPlaylist(catalog, listOf("Lush", "Nobody"), emptyList()) { called = true }
        assertEquals(0, queued)
        assertFalse(called)
    }

    // endregion
}
