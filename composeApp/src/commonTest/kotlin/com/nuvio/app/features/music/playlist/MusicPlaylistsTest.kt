package com.nuvio.app.features.music.playlist

import com.nuvio.app.features.music.MusicAlbum
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicTrack
import com.nuvio.app.features.music.parsePlaylistPage
import com.nuvio.app.features.music.parseSongSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicPlaylistsTest {
    private fun song(id: String, seconds: Int = 200) = PlaylistTrack(videoId = id, title = "Song $id", durationSeconds = seconds)
    private val list = MusicPlaylist(id = "p", name = "Mix", tracks = listOf(song("a"), song("b"), song("c")))

    @Test
    fun addingSkipsSongsAlreadyThereAndRepeats() {
        val (next, added) = list.withAdded(listOf(song("b"), song("d"), song("d")), nowMs = 5)
        assertEquals(1, added)
        assertEquals(listOf("a", "b", "c", "d"), next.tracks.map { it.videoId })
        assertEquals(5, next.updatedAtMs)
        assertEquals(0, list.withAdded(listOf(song("a")), 6).second)
    }

    @Test
    fun movingAndRemoving() {
        assertEquals(listOf("b", "c", "a"), list.withMoved(0, 9, 1).tracks.map { it.videoId })
        assertEquals(listOf("c", "a", "b"), list.withMoved(2, 0, 1).tracks.map { it.videoId })
        assertEquals(list, list.withMoved(5, 0, 1))
        assertEquals(listOf("a", "c"), list.withoutTrack(1, 1).tracks.map { it.videoId })
        assertEquals(list, list.withoutTrack(7, 1))
    }

    @Test
    fun freshNamesDoNotRepeat() {
        assertEquals("New playlist", freshPlaylistName(emptyList()))
        val taken = listOf(list.copy(name = "New playlist"), list.copy(name = "new playlist 2"))
        assertEquals("New playlist 3", freshPlaylistName(taken))
    }

    @Test
    fun aPlaylistPlaysAsAnAlbumWithEachTracksOwnCover() {
        val p = list.copy(tracks = listOf(song("a").copy(coverUrl = "x", albumTitle = "First"), song("b").copy(coverUrl = "y")))
        val album = p.asAlbum()
        assertEquals("playlist:p", album.browseId)
        assertEquals("x", album.thumbnailUrl)
        assertEquals(listOf("x", "y"), album.tracks.map { it.coverUrl })
        assertEquals(listOf(1, 2), album.tracks.map { it.trackNumber })
        assertEquals("First", album.tracks.first().albumTitle)
    }

    @Test
    fun anAlbumTrackTakesItsAlbumsCoverAndName() {
        val album = MusicAlbum("MPREb_x", "Record", listOf(MusicArtistRef("Band", "UCb")), "2020", "Album", "cover", emptyList(), null)
        val t = MusicTrack("v1", "Opener", emptyList(), 180, 1, true).toPlaylistTrack(album)
        assertEquals("Record", t.albumTitle)
        assertEquals("MPREb_x", t.albumBrowseId)
        assertEquals("cover", t.coverUrl)
        assertEquals(listOf("Band"), t.artists.map { it.name })
    }

    @Test
    fun recognisesLinksAndLists() {
        assertEquals(ImportSource.YouTube("PLabc_1"), recogniseImport("https://music.youtube.com/playlist?list=PLabc_1"))
        assertEquals(ImportSource.YouTube("PLx"), recogniseImport("https://www.youtube.com/watch?v=abc&list=PLx"))
        assertEquals(ImportSource.Spotify("playlist", "37i9dQ"), recogniseImport("https://open.spotify.com/intl-de/playlist/37i9dQ?si=1"))
        assertEquals(ImportSource.Spotify("album", "4aaw"), recogniseImport("spotify:album:4aaw"))
        assertIs<ImportSource.AppleMusic>(recogniseImport("https://music.apple.com/gb/playlist/hits/pl.abc"))
        val lines = assertIs<ImportSource.TextList>(recogniseImport("1. CHVRCHES - The Mother We Share 3:12\nGlass Animals – Heat Waves\n\nJust A Title"))
        assertEquals(WantedSong("The Mother We Share", "CHVRCHES", 192), lines.lines[0])
        assertEquals(WantedSong("Heat Waves", "Glass Animals"), lines.lines[1])
        assertEquals(WantedSong("Just A Title", ""), lines.lines[2])
        assertNull(recogniseImport("   "))
    }

    @Test
    fun readsSpotifysEmbedPage() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Road Trip","trackList":[
            {"title":"Housewife","subtitle":"Tate McRae","duration":208810},
            {"title":"Duet","subtitle":"A, B","duration":100000}]}}}}}}</script></html>"""
        val list = parseSpotifyEmbed(html)!!
        assertEquals("Road Trip", list.name)
        assertEquals(listOf(WantedSong("Housewife", "Tate McRae", 208), WantedSong("Duet", "A", 100)), list.songs)
    }

    @Test
    fun readsApplesPage() {
        val html = """<meta property="og:title" content="Today&#39;s Hits by Apple Music"><script type="application/json" id="serialized-server-data">
            [{"data":{"sections":[{"items":[{"title":"Man I Need","artistName":"Olivia Dean","duration":184000,"tertiaryLinks":[{"title":"The Art of Loving"}]}]}]}}]</script>"""
        val list = parseAppleMusicPage(html)!!
        assertEquals("Today's Hits", list.name)
        assertEquals(WantedSong("Man I Need", "Olivia Dean", 184, "The Art of Loving"), list.songs.single())
    }

    @Test
    fun matchesTheRightSongOrNone() {
        fun hit(id: String, title: String, artist: String, seconds: Int) = MusicTrack(id, title, listOf(MusicArtistRef(artist, "UC$id")), seconds, 1, true)
        val hits = listOf(
            hit("cover", "The Mother We Share", "Some Cover Band", 190),
            hit("live", "The Mother We Share (Live)", "CHVRCHES", 260),
            hit("real", "The Mother We Share", "CHVRCHES", 192),
        )
        assertEquals("real", pickSongMatch(WantedSong("The Mother We Share", "Chvrches", 191), hits)?.videoId)
        assertNull(pickSongMatch(WantedSong("Something Else", "CHVRCHES"), hits))
        assertEquals("real", pickSongMatch(WantedSong("The Mother We Share (feat. Nobody)", "CHVRCHES"), hits)?.videoId)
    }

    @Test
    fun parsesSongSearchAndPlaylistRows() {
        val row = """{"musicResponsiveListItemRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"small"},{"url":"big"}]}}},
            "flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"The Mother We Share","navigationEndpoint":{"watchEndpoint":{"videoId":"FtwwAwi1C48"}}}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"CHVRCHES","navigationEndpoint":{"browseEndpoint":{"browseId":"UCvIn"}}},{"text":" • "},{"text":"The Bones Of What You Believe","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_yc0"}}},{"text":" • "},{"text":"3:12"}]}}}
            ],"playlistItemData":{"videoId":"FtwwAwi1C48"}}}"""
        val song = parseSongSearch("""{"contents":[$row]}""").single()
        assertEquals("FtwwAwi1C48", song.videoId)
        assertEquals(listOf("CHVRCHES"), song.artists.map { it.name })
        assertEquals("The Bones Of What You Believe", song.albumTitle)
        assertEquals("MPREb_yc0", song.albumBrowseId)
        assertEquals(192, song.durationSeconds)
        assertEquals("big", song.coverUrl)

        val page = parsePlaylistPage(
            """{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"'80s Pop"}]}}},"contents":[$row],
               "continuations":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}""",
        )!!
        assertEquals("'80s Pop", page.title)
        assertEquals(1, page.tracks.size)
        assertEquals("NEXT", page.continuation)
        assertTrue(parsePlaylistPage("""{"nothing":1}""") == null)
    }
}
