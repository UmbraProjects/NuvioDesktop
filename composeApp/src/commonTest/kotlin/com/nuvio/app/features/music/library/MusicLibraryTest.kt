package com.nuvio.app.features.music.library

import com.nuvio.app.features.music.MusicAlbumSummary
import com.nuvio.app.features.music.MusicArtistRef
import com.nuvio.app.features.music.identity.ArtworkPaletteMath
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicSourceParsersTest {
    @Test
    fun musicBrainzSearchPicksTheExactNameWithAHighScore() {
        val body = """
        {"artists":[
          {"id":"a1","name":"Radiohead","sort-name":"Radiohead","score":100,"type":"Group","country":"GB"},
          {"id":"a2","name":"Radiohead Tribute","score":91},
          {"id":"a3","name":"Radiohed","score":60}
        ]}"""
        val hits = parseMbArtistSearch(body)
        assertEquals(3, hits.size)
        assertEquals("a1", pickMbArtist(hits, "radiohead")?.id)
    }

    @Test
    fun musicBrainzDoesNotGuessWhenNoNameMatches() {
        val hits = parseMbArtistSearch("""{"artists":[{"id":"a2","name":"Some Other Band","score":100}]}""")
        assertNull(pickMbArtist(hits, "Radiohead"))
        assertNull(pickMbArtist(parseMbArtistSearch("""{"artists":[{"id":"a1","name":"Radiohead","score":50}]}"""), "Radiohead"))
    }

    @Test
    fun musicBrainzArtistKeepsFactsGenresAndLabelledLinks() {
        val body = """
        {"id":"mb1","name":"Radiohead","sort-name":"Radiohead","type":"Group","country":"GB",
         "area":{"name":"United Kingdom"},"life-span":{"begin":"1985","ended":false},
         "disambiguation":"","rating":{"value":4.5,"votes-count":10},
         "genres":[{"name":"art rock","count":3},{"name":"alternative rock","count":9}],
         "tags":[{"name":"british","count":5}],
         "relations":[
           {"type":"official homepage","url":{"resource":"https://radiohead.com/"}},
           {"type":"wikipedia","url":{"resource":"https://en.wikipedia.org/wiki/Radiohead"}},
           {"type":"social network","url":{"resource":"https://www.instagram.com/radiohead"}},
           {"type":"lyrics","url":{"resource":"https://genius.com/artists/Radiohead"}}
         ]}"""
        val artist = assertNotNull(parseMbArtist(body))
        assertEquals("Group", artist.type)
        assertEquals("1985", artist.beginYear)
        assertNull(artist.endYear)
        assertEquals(false, artist.ended)
        assertNull(artist.disambiguation)
        assertEquals(listOf("alternative rock", "art rock"), artist.genres)
        assertEquals("https://radiohead.com/", artist.website)
        assertEquals("Radiohead", artist.wikipediaTitle)
        assertEquals(listOf("Website", "Wikipedia", "Instagram"), artist.links.map { it.label })
        assertEquals(4.5, artist.rating)
    }

    @Test
    fun releaseGroupsReadTypesAndTheTotal() {
        val body = """
        {"release-group-count":3,"release-groups":[
          {"id":"g1","title":"OK Computer","primary-type":"Album","secondary-types":[],"first-release-date":"1997-05-21"},
          {"id":"g2","title":"Live at Glastonbury","primary-type":"Album","secondary-types":["Live"],"first-release-date":"2003"},
          {"id":"g3","title":"Creep","primary-type":"Single","secondary-types":[],"first-release-date":"1992-09-21"}
        ]}"""
        val (groups, total) = parseMbReleaseGroups(body)
        assertEquals(3, total)
        assertEquals(listOf(ReleaseKind.Album, ReleaseKind.Live, ReleaseKind.Single), groups.map { kindFromMusicBrainz(it.primaryType, it.secondaryTypes) })
    }

    @Test
    fun deezerAlbumsCarryTheRecordTypeAndTheNextPage() {
        val body = """
        {"data":[{"id":1,"title":"A","cover_xl":"https://c/1","record_type":"EP","release_date":"2020-01-02","explicit_lyrics":true}],
         "next":"https://api.deezer.com/artist/9/albums?index=100"}"""
        val (albums, next) = parseDeezerAlbums(body)
        assertEquals("ep", albums.single().recordType)
        assertEquals(true, albums.single().explicit)
        assertEquals("https://api.deezer.com/artist/9/albums?index=100", next)
        assertNull(parseDeezerAlbums("""{"data":[]}""").second)
    }

    @Test
    fun deezerAlbumDetailReadsLabelTracksAndGenres() {
        val detail = assertNotNull(
            parseDeezerAlbumDetail(
                """{"label":"XL","nb_tracks":12,"duration":2800,"upc":"123","release_date":"1997-06-16","genres":{"data":[{"name":"Alternative"}]}}""",
            ),
        )
        assertEquals("XL", detail.label)
        assertEquals(12, detail.trackCount)
        assertEquals(listOf("Alternative"), detail.genres)
        assertNull(parseDeezerAlbumDetail("""{"error":{"type":"DataException"}}"""))
    }

    @Test
    fun audioDbTreatsNullTextAsMissingAndMakesLinksOpenable() {
        val body = """
        {"artists":[{"idArtist":"111239","strArtist":"Radiohead","strMusicBrainzID":"mb1","strBiographyEN":"null",
          "strGenre":"Alternative Rock","strStyle":"Rock/Pop","strMood":"Sad","strWebsite":"www.radiohead.com",
          "intFormedYear":"1985","strArtistLogo":"http://img/logo.png","strArtistClearart":"https://img/clear.png",
          "strArtistFanart":"https://img/f1.jpg","strArtistFanart2":"null","strArtistThumb":""}]}"""
        val artist = parseAudioDbArtists(body).single()
        assertNull(artist.biography)
        assertEquals("https://www.radiohead.com", artist.website)
        assertEquals("https://img/logo.png", artist.art.logo)
        assertEquals("https://img/clear.png", artist.art.clearArt)
        assertEquals(listOf("https://img/f1.jpg"), artist.art.backgrounds)
        assertNull(artist.art.thumb)
        assertEquals("1985", artist.formedYear)
    }

    @Test
    fun audioDbAlbumsCarryCdArtAndDescription() {
        val body = """
        {"album":[{"idAlbum":"5","strAlbum":"OK Computer","strMusicBrainzID":"g1","intYearReleased":"1997",
          "strReleaseFormat":"Album","strLabel":"Parlophone","strDescriptionEN":"A record.",
          "strAlbumThumb":"https://img/t.jpg","strAlbumThumbHQ":"https://img/hq.jpg","strAlbumCDart":"https://img/cd.png"}]}"""
        val album = parseAudioDbAlbums(body).single()
        assertEquals("g1", album.musicBrainzId)
        assertEquals(listOf("https://img/hq.jpg", "https://img/t.jpg"), album.art.covers)
        assertEquals("https://img/cd.png", album.art.cdArt)
        assertEquals("Parlophone", album.label)
    }

    @Test
    fun fanartPicksTheFirstDiscsPrintAndSortsByLikes() {
        val body = """
        {"name":"Radiohead","mbid_id":"mb1",
         "hdmusiclogo":[{"url":"https://f/hd1.png","likes":"2"},{"url":"https://f/hd2.png","likes":"9"}],
         "artistbackground":[{"url":"https://f/bg.jpg","likes":"1"}],
         "albums":{"g1":{"cdart":[{"url":"https://f/disc2.png","disc":"2","likes":"5"},{"url":"https://f/disc1.png","disc":"1","likes":"1"}],
                         "albumcover":[{"url":"https://f/cover.jpg","likes":"1"}]}}}"""
        val fan = assertNotNull(parseFanartArtist(body))
        assertEquals("https://f/hd2.png", fan.hdLogos.first())
        assertEquals("https://f/disc1.png", fan.releases.getValue("g1").cdArt)
        assertEquals(listOf("https://f/cover.jpg"), fan.releases.getValue("g1").covers)
        assertNull(parseFanartArtist("""{"status":"error","error message":"Not found"}"""))
    }

    @Test
    fun wikipediaSummarySkipsDisambiguationPages() {
        assertNull(parseWikipediaSummary("""{"type":"disambiguation","extract":"Radiohead may refer to a lot of things here."}"""))
        assertEquals(
            "Radiohead are an English rock band formed in Abingdon.",
            parseWikipediaSummary("""{"type":"standard","extract":"Radiohead are an English rock band formed in Abingdon."}"""),
        )
    }
}

class MusicLibraryMergeTest {
    private fun yt(id: String, title: String, kind: String?, year: String? = null, thumb: String? = null) =
        MusicAlbumSummary(id, title, listOf(MusicArtistRef("Radiohead", "UC1")), year, kind, thumb)

    @Test
    fun titleKeysFoldAccentsAndEditionNotes() {
        assertEquals(strictTitleKey("Beyoncé"), strictTitleKey("beyonce"))
        assertEquals(looseTitleKey("OK Computer (Deluxe Edition)"), looseTitleKey("OK Computer"))
        assertEquals(looseTitleKey("Kid A [Remastered]"), looseTitleKey("Kid A"))
        // A title made only of edition words must not collapse to nothing.
        assertTrue(looseTitleKey("Deluxe").isNotEmpty())
        assertTrue(sameArtistName("The xx", "THE XX"))
    }

    @Test
    fun youTubeMusicAndMusicBrainzJoinOnTitleAndTheMusicBrainzKindWins() {
        val merged = mergeReleases(
            ReleaseSources(
                musicBrainz = listOf(MbReleaseGroup("g1", "OK Computer", "Album", emptyList(), "1997-05-21")),
                youTubeMusic = listOf(yt("MPREb_ok", "OK Computer", "Single", "1997")),
            ),
        )
        val release = merged.single()
        assertEquals(ReleaseKind.Album, release.kind)
        assertEquals("MPREb_ok", release.ytBrowseId)
        assertEquals("g1", release.musicBrainzId)
        assertEquals("1997-05-21", release.releaseDate)
        assertTrue(release.isPlayable)
        assertEquals("g1", release.key)
    }

    @Test
    fun aDeluxeEditionStaysItsOwnReleaseWhenTheAlbumTookTheSlot() {
        val merged = mergeReleases(
            ReleaseSources(
                youTubeMusic = listOf(
                    yt("MPREb_a", "OK Computer", "Album", "1997"),
                    yt("MPREb_b", "OK Computer (Deluxe Edition)", "Album", "2017"),
                ),
            ),
        )
        assertEquals(setOf("MPREb_a", "MPREb_b"), merged.map { it.ytBrowseId }.toSet())
    }

    @Test
    fun aDeluxeTitleFromAnotherSourceJoinsTheAlbumWhenItsSlotIsFree() {
        val merged = mergeReleases(
            ReleaseSources(
                youTubeMusic = listOf(yt("MPREb_a", "OK Computer", "Album", "1997")),
                deezer = listOf(DeezerAlbum(7, "OK Computer (Remastered)", "https://dz/c", "album", "1997-05-21", false)),
            ),
        )
        val release = merged.single()
        assertEquals("MPREb_a", release.ytBrowseId)
        assertEquals(7L, release.deezerId)
    }

    @Test
    fun anAlbumAndASingleWithTheSameTitleAreNotMixedUp() {
        val merged = mergeReleases(
            ReleaseSources(
                musicBrainz = listOf(
                    MbReleaseGroup("g1", "Creep", "Single", emptyList(), "1992-09-21"),
                    MbReleaseGroup("g2", "Creep", "Album", emptyList(), "2000"),
                ),
                youTubeMusic = listOf(yt("MPREb_single", "Creep", "Single", "1992")),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("MPREb_single", merged.first { it.kind == ReleaseKind.Single }.ytBrowseId)
        assertNull(merged.first { it.kind == ReleaseKind.Album }.ytBrowseId)
    }

    @Test
    fun releasesNoSourceCanPlayAreStillListed() {
        val merged = mergeReleases(
            ReleaseSources(musicBrainz = listOf(MbReleaseGroup("g9", "Rarities", "EP", emptyList(), "2001"))),
        )
        assertEquals(false, merged.single().isPlayable)
        assertEquals(ReleaseKind.EP, merged.single().kind)
    }

    @Test
    fun liveAndCompilationGetTheirOwnKinds() {
        assertEquals(ReleaseKind.Live, kindFromMusicBrainz("Album", listOf("Live")))
        assertEquals(ReleaseKind.Compilation, kindFromMusicBrainz("Album", listOf("Compilation", "Live")))
        assertEquals(ReleaseKind.Other, kindFromMusicBrainz("Broadcast", emptyList()))
        assertEquals(ReleaseKind.Compilation, kindFromLabel("compile"))
        assertNull(kindFromLabel("Playlist"))
    }

    @Test
    fun fanartCdArtAndCoverArtArchiveJoinByReleaseGroup() {
        val fan = FanartArtist(
            thumbs = emptyList(), logos = emptyList(), hdLogos = emptyList(), banners = emptyList(), backgrounds = emptyList(),
            releases = mapOf("g1" to FanartRelease(listOf("https://f/c.jpg"), "https://f/cd.png")),
        )
        val merged = mergeReleases(
            ReleaseSources(
                musicBrainz = listOf(MbReleaseGroup("g1", "OK Computer", "Album", emptyList(), "1997")),
                youTubeMusic = listOf(
                    yt("MPREb_a", "OK Computer", "Album", "1997", "https://lh3.googleusercontent.com/x=w226-h226-l90-rj"),
                ),
                deezer = listOf(DeezerAlbum(7, "OK Computer", "https://dz/c.jpg", "album", "1997-05-21", null)),
                fanart = fan,
            ),
        )
        val release = merged.single()
        assertEquals("https://f/cd.png", release.art.cdArt)
        // YouTube Music's cover (enlarged) first, since it matches what plays; Cover Art Archive last.
        assertEquals("https://lh3.googleusercontent.com/x=w600-h600-l90-rj", release.art.covers.first())
        assertEquals("https://coverartarchive.org/release-group/g1/front-500", release.art.covers.last())
        assertEquals(listOf("https://lh3.googleusercontent.com/x=w600-h600-l90-rj", "https://dz/c.jpg", "https://f/c.jpg", "https://coverartarchive.org/release-group/g1/front-500"), release.art.covers)
    }

    @Test
    fun audioDbJoinsByReleaseGroupIdEvenWhenTheTitleDiffers() {
        val merged = mergeReleases(
            ReleaseSources(
                musicBrainz = listOf(MbReleaseGroup("g1", "OK Computer", "Album", emptyList(), "1997")),
                audioDb = listOf(
                    AudioDbAlbum("5", "OKC", "g1", "1997", "Album", "Alt", "Rock", "Sad", "Parlophone", "A record.", ReleaseArtwork(emptyList(), "https://a/cd.png")),
                ),
            ),
        )
        val release = merged.single()
        assertEquals("5", release.audioDbId)
        assertEquals("Parlophone", release.label)
        assertEquals("https://a/cd.png", release.art.cdArt)
    }

    @Test
    fun sectionsRunAlbumsThenEpsThenSinglesNewestFirst() {
        val merged = mergeReleases(
            ReleaseSources(
                musicBrainz = listOf(
                    MbReleaseGroup("s", "Single One", "Single", emptyList(), "2010"),
                    MbReleaseGroup("e", "An EP", "EP", emptyList(), "2005"),
                    MbReleaseGroup("a1", "Old Album", "Album", emptyList(), "1995"),
                    MbReleaseGroup("a2", "New Album", "Album", emptyList(), "2016-05-08"),
                ),
            ),
        )
        assertEquals(listOf("New Album", "Old Album", "An EP", "Single One"), merged.map { it.title })
        val artist = LibraryArtist(id = "UC1", name = "X", releases = merged)
        assertEquals(listOf(ReleaseKind.Album, ReleaseKind.EP, ReleaseKind.Single), artist.sections.map { it.first })
    }

    @Test
    fun deezerDetailFillsGapsButKeepsWhatIsKnown() {
        val base = LibraryRelease(key = "k", title = "T", kind = ReleaseKind.Album, label = "Mine", releaseDate = "1997")
        val filled = base.withDeezerDetail(DeezerAlbumDetail("Theirs", 12, 2800, listOf("Rock"), "999", "1997-05-21"))
        assertEquals("Mine", filled.label)
        assertEquals(12, filled.trackCount)
        assertEquals("1997-05-21", filled.releaseDate)
        assertEquals(listOf("Rock"), filled.genres)
    }

    @Test
    fun deezerArtistNamesakesResolveToTheOneWithMostFans() {
        val hits = listOf(DeezerArtistHit(1, "Air", null, 10, 1), DeezerArtistHit(2, "Air", null, 5000, 20), DeezerArtistHit(3, "Air Supply", null, 99999, 5))
        assertEquals(2L, pickDeezerArtist(hits, "Air")?.id)
    }
}

class AssembleArtistTest {
    @Test
    fun betterSourcesWinAndTheCreditLineNamesThem() {
        val mb = MbArtist(
            id = "mb1", name = "Radiohead", sortName = "Radiohead", type = "Group", country = "GB", area = "United Kingdom",
            beginYear = "1985", endYear = null, ended = false, disambiguation = null,
            genres = listOf("art rock"), tags = listOf("art rock", "british"), rating = 4.5,
            links = listOf(ArtistLink("Website", "https://radiohead.com/")), website = "https://radiohead.com/", wikipediaTitle = "Radiohead",
        )
        val audio = AudioDbArtist(
            id = "9", name = "Radiohead", musicBrainzId = "mb1", biography = "Audio bio", genre = "Alternative Rock",
            style = "Rock", mood = "Sad", country = "United Kingdom", formedYear = "1986", disbandedYear = null,
            website = "https://www.radiohead.com", links = listOf(ArtistLink("Website", "https://radiohead.com")),
            art = ArtistArtwork(thumb = "https://a/thumb", logo = "https://a/logo", clearArt = "https://a/clear", backgrounds = listOf("https://a/bg")),
        )
        val fan = FanartArtist(
            thumbs = emptyList(), logos = listOf("https://f/logo"), hdLogos = listOf("https://f/hd"), banners = listOf("https://f/banner"),
            backgrounds = listOf("https://f/bg"), releases = emptyMap(),
        )
        val artist = assembleArtist(
            id = "UC1", name = "Radiohead", youTubeThumbnail = "https://yt/t",
            sources = ArtistSources(mb, DeezerArtistHit(3, "Radiohead", "https://dz/p", 1_000_000, 9), audio, fan, "Wiki bio is long enough to count."),
            releases = emptyList(), addedAtEpochMs = 1, nowEpochMs = 2,
        )
        assertEquals("1985", artist.beginYear)
        assertEquals("Audio bio", artist.biography)
        assertEquals("TheAudioDB", artist.biographySource)
        assertEquals("https://f/logo", artist.art.logo)
        assertEquals("https://f/hd", artist.art.bestLogo)
        assertEquals("https://f/bg", artist.art.bestBackground)
        assertEquals("https://a/clear", artist.art.bestClearArt)
        assertEquals("https://a/thumb", artist.art.thumb)
        assertEquals(listOf("art rock", "Alternative Rock"), artist.genres)
        assertEquals(listOf("british"), artist.tags)
        assertEquals(1, artist.links.size)
        assertEquals(1_000_000L, artist.fans)
        assertEquals(listOf("YouTube Music", "MusicBrainz", "Deezer", "TheAudioDB", "Fanart.tv"), artist.sources)
    }

    @Test
    fun withNoSourcesTheArtistIsStillUsable() {
        val artist = assembleArtist("UC1", "Nobody", "https://yt/t", ArtistSources(), emptyList(), 1, 2)
        assertEquals("https://yt/t", artist.art.thumb)
        assertEquals(listOf("YouTube Music"), artist.sources)
        assertNull(artist.biography)
        assertNull(artist.biographySource)
    }

    @Test
    fun wikipediaFillsTheBiographyWhenTheAudioDbHasNone() {
        val artist = assembleArtist("UC1", "X", null, ArtistSources(wikipedia = "From Wikipedia, long enough to be used."), emptyList(), 1, 2)
        assertEquals("Wikipedia", artist.biographySource)
        assertTrue("Wikipedia" in artist.sources)
    }

    @Test
    fun aLibrarySavedBeforePalettesExistedStillLoadsAndAPaletteRoundTrips() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"artists":[{"id":"UC1","name":"Hozier","releases":[{"key":"k","title":"T","kind":"Album"}]}]}"""
        val loaded = json.decodeFromString<StoredMusicLibrary>(old).artists.single()
        assertNull(loaded.palette)
        assertNull(loaded.releases.single().palette)

        val palette = ArtworkPaletteMath.derive(0xE8913A, forUrl = "https://bg")
        val saved = json.encodeToString(StoredMusicLibrary(listOf(loaded.copy(palette = palette))))
        assertEquals(palette, json.decodeFromString<StoredMusicLibrary>(saved).artists.single().palette)
    }
}
