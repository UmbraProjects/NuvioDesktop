package com.nuvio.app.features.music.home

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtist
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.MusicArtistSummary
import com.nuvio.app.features.music.MusicSearchEverything
import com.nuvio.app.features.music.MusicTopResult
import com.nuvio.app.features.music.MusicTopSong
import com.nuvio.app.features.music.MusicTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicHomeSearchTest {
    private val library = listOf("Yelawolf", "2Pac", "Dr. Dre", "The Notorious B.I.G.", "Guns N' Roses")

    private fun track(id: String, title: String, artist: String, seconds: Int) =
        MusicTrack(id, title, listOf(MusicArtistRef(artist)), seconds, 1, true)

    // Sentence chips

    @Test
    fun aKindWordBecomesAChipAndTheRestIsTheArtist() {
        val q = parseQuery("yelawolf remix", library)
        assertEquals("yelawolf", q.terms)
        assertEquals("Yelawolf", q.artist)
        assertEquals(QueryKind.Remix, q.kind)
        assertEquals(listOf("Yelawolf", "remix"), q.chips.map { (it as? QueryChip.Artist)?.name ?: it.word })
        assertEquals("yelawolf remix", q.searchText)
    }

    @Test
    fun aYearAndAnArtistWithMoreWordsAfterIt() {
        val q = parseQuery("2pac changes 1998", library)
        assertEquals("2Pac", q.artist)
        assertEquals(1998, q.year)
        assertEquals("2pac changes", q.terms)
    }

    @Test
    fun albumsOnlyFiltersButIsNotSearchedFor() {
        val q = parseQuery("dr dre albums", library)
        assertEquals(QueryKind.Albums, q.kind)
        assertEquals("Dr. Dre", q.artist)
        assertEquals("dr dre", q.searchText)
    }

    @Test
    fun aKindWordOnItsOwnIsAName() {
        val q = parseQuery("Live", library)
        assertNull(q.kind)
        assertEquals("Live", q.terms)
        assertNull(parseQuery("1999", library).year)
    }

    @Test
    fun namesFoldTheAndPunctuation() {
        assertEquals("notorious b i g", foldName("The Notorious B.I.G."))
        assertEquals("The Notorious B.I.G.", libraryArtistIn("notorious b i g", library))
        assertEquals("Guns N' Roses", libraryArtistIn("guns n roses", library))
        assertNull(libraryArtistIn("yelawolfish", library))
    }

    @Test
    fun kindWordsInTitles() {
        assertTrue(titleMatchesKind("Till It's Gone (Remix)", QueryKind.Remix))
        assertTrue(titleMatchesKind("Daddy's Lambo - Bass Boosted Edit", QueryKind.Remix))
        assertFalse(titleMatchesKind("Remixing Life", QueryKind.Remix))
        assertTrue(titleMatchesKind("Outer Space (LIVE) at Sturgis", QueryKind.Live))
        assertFalse(titleMatchesKind("Delivery", QueryKind.Live))
    }

    @Test
    fun officialUploadsByChannel() {
        assertTrue(isOfficialUpload("Yelawolf", listOf("Yelawolf")))
        assertTrue(isOfficialUpload("YelawolfVEVO", listOf("Yelawolf")))
        assertTrue(isOfficialUpload("Yelawolf - Topic", listOf("Yelawolf")))
        assertTrue(isOfficialUpload("Slumerican Records", listOf("Yelawolf")))
        assertFalse(isOfficialUpload("JBL Bass Testers", listOf("Yelawolf")))
        assertFalse(isOfficialUpload(null, listOf("Yelawolf")))
    }

    // Merged rows

    @Test
    fun aSongAndItsVideoBecomeOneRow() {
        val songs = listOf(track("a", "Till It's Gone", "Yelawolf", 240), track("b", "Box Chevy V", "Yelawolf", 252))
        val videos = listOf(
            track("v1", "Yelawolf - Till It's Gone (Official Music Video)", "YelawolfVEVO", 243),
            track("v2", "Box Chevy V (Extended Mix)", "Yelawolf", 400),
            track("v3", "Interview", "Some Radio", 700),
        )
        val merged = mergeSourceRows(songs, videos)
        assertEquals(listOf("v1", null), merged.rows.map { it.video?.videoId })
        assertEquals(listOf("v2", "v3"), merged.otherVideos.map { it.videoId })
    }

    @Test
    fun aVideoPairsOnceAndAnotherArtistNever() {
        val songs = listOf(track("a", "Changes", "2Pac", 269), track("b", "Changes", "2Pac", 269))
        val videos = listOf(track("v", "Changes", "2Pac", 270), track("w", "Changes", "Black Sabbath", 270))
        val merged = mergeSourceRows(songs, videos)
        assertEquals(listOf("v", null), merged.rows.map { it.video?.videoId })
        assertEquals(listOf("w"), merged.otherVideos.map { it.videoId })
    }

    @Test
    fun songKeysDropVideoWords() {
        assertEquals("till it s gone", songKey("Yelawolf - Till It's Gone (Official Music Video)"))
        assertEquals("best friend", songKey("Best Friend feat. Eminem"))
        assertEquals("hit em up", songKey("Hit 'Em Up [Official Audio] HD"))
    }

    // Letter strip

    @Test
    fun likelyLettersComeFromLibraryFirst() {
        val candidates = listOf(
            "Yelawolf" to LetterSource.Library,
            "Yeat" to LetterSource.Suggestion,
            "Yellowcard" to LetterSource.Suggestion,
            "Yes" to LetterSource.History,
        )
        assertEquals(listOf('L', 'A', 'S'), nextLetterRanks("ye", candidates))
        assertEquals(listOf('A', 'L'), nextLetterRanks("yel", candidates))
        assertEquals(listOf(' '), nextLetterRanks("dr", listOf("Dr. Dre" to LetterSource.Library)))
        assertTrue(nextLetterRanks("zz", candidates).isEmpty())
    }

    @Test
    fun theStripPullsLikelyLettersBesideTheFocus() {
        val arranged = arrangeStrip('L', listOf('A', 'W', 'O'))
        val at = arranged.indexOf('L')
        assertEquals('A', arranged[at + 1])
        assertEquals('W', arranged[at - 1])
        assertEquals('O', arranged[at + 2])
        assertEquals(26, arranged.size)
        assertEquals(STRIP_LETTERS.toSet(), arranged.toSet())
    }

    @Test
    fun theStripRestsAToZWithNothingToPull() {
        assertEquals(STRIP_LETTERS, arrangeStrip(null, listOf('A')))
        assertEquals(STRIP_LETTERS, arrangeStrip('L', emptyList()))
        assertEquals(STRIP_LETTERS, arrangeStrip('L', listOf(' ', '7')))
        assertEquals(STRIP_LETTERS.size, arrangeStrip('A', listOf('Z', 'Y', 'X', 'W')).size)
        assertEquals(STRIP_LETTERS.size, arrangeStrip('Z', listOf('A', 'B', 'C')).size)
    }

    @Test
    fun ghostCompletionKeepsTheCandidatesSpelling() {
        assertEquals("wolf", ghostCompletion("yela", listOf("yelawolf best friend", "Yelawolf").sortedBy { it.length }))
        assertEquals("", ghostCompletion("", listOf("Yelawolf")))
        assertEquals("", ghostCompletion("yelawolf", listOf("Yelawolf")))
    }

    // Settling

    @Test
    fun aMatchSettlesAfterTwoSearchesInARow() {
        val s = MatchSettler()
        assertNull(s.offer("UCyeat"))
        assertEquals("UCyela", s.offer("UCyela").let { s.offer("UCyela") })
        assertEquals("UCyela", s.offer("UCother"))
        assertEquals("UCyela", s.offer(null))
        assertEquals("UCother", s.offer("UCother").let { s.offer("UCother") })
        s.reset()
        assertNull(s.settled)
    }

    // Panels

    private val yela = MusicArtistRef("Yelawolf", "UCyela")

    private fun everything(
        top: MusicTopResult? = MusicTopResult("Yelawolf", "Artist", listOf(yela), null, null),
        songs: List<MusicTrack> = emptyList(),
        albums: List<MusicAlbumSummary> = emptyList(),
        artists: List<MusicArtistSummary> = emptyList(),
        videos: List<MusicTrack> = emptyList(),
    ) = MusicSearchEverything(top, songs, albums, artists, videos)

    @Test
    fun panelsMergeSourcesAndLeaveTheTopArtistOutOfTheList() {
        val r = buildHomeResults(
            parseQuery("yelawolf", library),
            everything(
                songs = listOf(track("a", "Till It's Gone", "Yelawolf", 240)),
                artists = listOf(MusicArtistSummary("UCyela", "Yelawolf", null), MusicArtistSummary("UCrittz", "Rittz", null)),
                videos = listOf(track("v1", "Till It's Gone (Official Video)", "Yelawolf", 241)),
            ),
            youtube = listOf(track("v1", "dup", "x", 1), track("v2", "Live session", "Yelawolf", 2000)),
        )
        assertEquals(listOf("Rittz"), r.artists.map { it.name })
        assertEquals("v1", r.rows.single().video?.videoId)
        assertEquals(listOf("v2"), r.videos.map { it.videoId })
        assertEquals("UCyela", r.topKey)
        assertFalse(r.isEmpty)
    }

    @Test
    fun aKindChipNarrowsEveryList() {
        val r = buildHomeResults(
            parseQuery("yelawolf remix", library),
            everything(
                songs = listOf(track("a", "Till It's Gone", "Yelawolf", 240), track("b", "Till It's Gone (Remix)", "Yelawolf", 250)),
                albums = listOf(MusicAlbumSummary("MPREb_1", "Love Story", listOf(yela), "2015", "Album", null), MusicAlbumSummary("MPREb_2", "The Remixes", listOf(yela), "2016", "EP", null)),
            ),
            youtube = listOf(track("v1", "Daddy's Lambo (Bootleg Mix)", "DJ", 300), track("v2", "Interview", "Radio", 600)),
        )
        assertEquals(listOf("b"), r.rows.map { it.song?.videoId })
        assertEquals(listOf("MPREb_2"), r.albums.map { it.browseId })
        assertEquals(listOf("v1"), r.videos.map { it.videoId })
    }

    @Test
    fun albumsOnlyAndAYear() {
        val r = buildHomeResults(
            parseQuery("yelawolf albums 2011", library),
            everything(
                songs = listOf(track("a", "Pop the Trunk", "Yelawolf", 240)),
                albums = listOf(MusicAlbumSummary("MPREb_1", "Love Story", listOf(yela), "2015", "Album", null), MusicAlbumSummary("MPREb_2", "Radioactive", listOf(yela), "2011", "Album", null)),
            ),
            youtube = listOf(track("v", "Pop the Trunk", "Yelawolf", 240)),
        )
        assertTrue(r.rows.isEmpty() && r.videos.isEmpty())
        assertEquals(listOf("MPREb_2"), r.albums.map { it.browseId })
    }

    @Test
    fun greetingWords() {
        assertEquals("THURSDAY EVENING", greetingLabel(4, 21))
        assertEquals("MONDAY MORNING", greetingLabel(1, 5))
        assertEquals("SUNDAY NIGHT", greetingLabel(7, 2))
        assertEquals("SATURDAY AFTERNOON", greetingLabel(6, 12))
    }

    // Action row

    @Test
    fun theActionRowFitsWhatTheTopMatchIs() {
        val artist = MusicTopResult("Yelawolf", "Artist", listOf(yela), null, null)
        assertEquals(
            listOf(HomeAction.PlayEssentials, HomeAction.ShuffleEssentials, HomeAction.PlayTopAlbums, HomeAction.ArtistRadio, HomeAction.OpenArtist, HomeAction.More),
            homeActions(artist),
        )
        val album = MusicTopResult("Love Story", "Album", listOf(yela), "MPREb_love", null)
        assertEquals(TopMatchKind.Album, topMatchKind(album))
        assertEquals(HomeAction.PlayAlbum, homeActions(album).first())
        val unlinkedVideo = MusicTopResult("Chaos in My Head", "Video", listOf(MusicArtistRef("Trunk Bangers")), null, null, videoId = "v")
        assertEquals(listOf(HomeAction.PlayTrack, HomeAction.QueueTrack, HomeAction.StartRadio, HomeAction.More), homeActions(unlinkedVideo))
    }

    @Test
    fun searchEssentialsPlaysTheMostPlayedListAtOnce() {
        val page = MusicArtist(
            browseId = "UCyela",
            name = "Yelawolf",
            thumbnailUrl = "https://img/yela",
            albums = listOf(
                MusicAlbumSummary("MPREb_s", "Till It's Gone", listOf(yela), "2014", "Single", null),
                MusicAlbumSummary("MPREb_a", "Love Story", listOf(yela), "2015", "Album", null),
            ),
            topSongs = listOf(
                MusicTopSong("t1", "Till It's Gone", "MPREb_a"),
                MusicTopSong("t2", "Till It's Gone (Remix)", "MPREb_r"),
                MusicTopSong("t3", "Daddy's Lambo"),
            ),
        )
        val mix = searchEssentials(page)!!
        assertEquals(listOf("t1", "t3"), mix.tracks.map { it.videoId })
        assertEquals("essentials:UCyela", mix.browseId)
        assertEquals(listOf("MPREb_a", "MPREb_s"), topAlbumIds(page))
        assertNull(searchEssentials(page.copy(topSongs = emptyList())))
    }

    // Narrow row and split panels

    @Test
    fun theNarrowRowRewritesTheKindWord() {
        val q = parseQuery("yelawolf live 2012", library)
        assertEquals("yelawolf remix 2012", withKind(q, QueryKind.Remix))
        assertEquals("yelawolf 2012", withKind(q, null))
        assertEquals("yelawolf", withKind(q, null, keepYear = false))
        assertEquals("yelawolf albums", withKind(parseQuery("yelawolf", library), QueryKind.Albums))
    }

    @Test
    fun aRemixChipSplitsOwnUploadsFromFans() {
        val r = buildHomeResults(
            parseQuery("yelawolf remix", library),
            everything(songs = listOf(track("a", "Till It's Gone (Remix)", "Yelawolf", 250))),
            youtube = listOf(
                track("v1", "Box Chevy V (Remix)", "YelawolfVEVO", 271),
                track("v2", "Till it's Gone Remix", "Mordern Music", 260),
                track("v3", "Daddy's Lambo (Slowed Remix)", "Some DJ", 300),
            ),
        )
        assertTrue(r.split)
        assertEquals(listOf(RowSource.YouTubeMusic, RowSource.YouTube), r.rows.map { it.source })
        assertEquals(listOf("a", "v1"), r.centreTracks.map { it.videoId })
        assertEquals(listOf("v2", "v3"), r.videos.map { it.videoId })
        assertEquals("OFFICIAL REMIXES · BOTH SOURCES", r.centreLabel)
        assertEquals("FAN AND DJ REMIXES · YOUTUBE", r.rightLabel)
    }

    @Test
    fun withoutASplitKindVideosStayRight() {
        val r = buildHomeResults(parseQuery("yelawolf", library), everything(), youtube = listOf(track("v1", "Box Chevy V", "YelawolfVEVO", 271)))
        assertFalse(r.split)
        assertTrue(r.rows.isEmpty())
        assertEquals(listOf("v1"), r.videos.map { it.videoId })
        assertEquals("YOUTUBE MUSIC · SONGS AND ALBUMS", r.centreLabel)
    }

    @Test
    fun aSplitKindLeadsTheActionRowWithPlayAll() {
        val artist = MusicTopResult("Yelawolf", "Artist", listOf(yela), null, null)
        assertEquals(listOf(HomeAction.PlayAll, HomeAction.ShuffleAll, HomeAction.OpenArtist, HomeAction.More), homeActions(artist, QueryKind.Remix))
        assertEquals("Play all remixes", HomeAction.PlayAll.labelFor(QueryKind.Remix))
        assertEquals("Shuffle live", HomeAction.ShuffleAll.labelFor(QueryKind.Live))
        assertEquals(HomeAction.PlayEssentials, homeActions(artist, QueryKind.Albums).first())
    }
}

class RecentSearchLabelTest {
    @kotlin.test.Test
    fun halfTypedLineBecomesTheMatchName() {
        kotlin.test.assertEquals("Sleep Token", recentSearchLabel("sleep t", "Sleep Token"))
    }

    @kotlin.test.Test
    fun otherLinesStayAsTyped() {
        kotlin.test.assertEquals("gang starr", recentSearchLabel(" gang starr ", "Gang Starr Foundation"))
        kotlin.test.assertEquals("tha squeeze", recentSearchLabel("tha squeeze", null))
        kotlin.test.assertEquals("sleep token", recentSearchLabel("sleep token", "Sleep Token"))
    }
}

class ArtistMatchResultsTest {
    private fun track(id: String, title: String, artist: String?) = MusicTrack(
        videoId = id, title = title,
        artists = artist?.let { listOf(MusicArtistRef(it, "UC$it")) }.orEmpty(),
        durationSeconds = 200, trackNumber = 0, isAvailable = true,
    )

    @kotlin.test.Test
    fun namesakeVideoSinksAndArtistlessSongGetsTheMatchArtist() {
        val top = MusicTopResult("Gang Starr", "Artist", listOf(MusicArtistRef("Gang Starr", "UCgs")), null, null)
        val everything = MusicSearchEverything.EMPTY.copy(
            top = top,
            songs = listOf(track("s1", "Tha Squeeze", null)),
            videos = listOf(track("v1", "Gangstar", "Oruam"), track("v2", "Gang Starr - Full Clip", "Gang Starr Topic")),
        )
        val results = buildHomeResults(ParsedQuery("gang starr", "gang starr", null, null, null), everything, emptyList())
        kotlin.test.assertEquals("Gang Starr", results.rows.first().song?.artists?.firstOrNull()?.name)
        kotlin.test.assertEquals("v2", results.videos.first().videoId)
    }
}
