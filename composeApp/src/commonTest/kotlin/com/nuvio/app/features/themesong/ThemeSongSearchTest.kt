package com.nuvio.app.features.themesong

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeSongSearchTest {
    private fun hit(
        id: String,
        title: String,
        channel: String = "Someone",
        verified: Boolean = false,
        seconds: Int = 180,
        views: Long = 100_000,
    ) = YouTubeSearchHit(id, title, channel, verified, seconds, views)

    @Test
    fun queryFitsTheKindOfTitle() {
        assertEquals("Halloween 1978 main theme", themeSongSearchQuery("Halloween", "1978", isSeries = false))
        assertEquals("Halloween main theme", themeSongSearchQuery("Halloween", null, isSeries = false))
        assertEquals("Twin Peaks theme song", themeSongSearchQuery("Twin Peaks", "1990", isSeries = true))
    }

    @Test
    fun officialThemeBeatsFanUploads() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("fan", "Halloween theme (piano cover)"),
                hit("plain", "Halloween Theme", channel = "Some Fan"),
                hit("official", "Halloween - Main Theme", channel = "John Carpenter - Topic", verified = true),
            ),
            title = "Halloween",
            year = "1978",
        )
        assertEquals(listOf("official", "plain"), ranked.map { it.videoId })
    }

    @Test
    fun rejectsWrongLengthAndUploadsWithoutTheTitleOrMusicWords() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("short", "Alien Theme", seconds = 8),
                hit("hours", "Alien Theme", seconds = 3_600),
                hit("other", "Predator Theme"),
                hit("film", "Alien 1979 Full Movie"),
                hit("ok", "Alien - Main Title"),
            ),
            title = "Alien",
            year = "1979",
        )
        assertEquals(listOf("ok"), ranked.map { it.videoId })
    }

    @Test
    fun anotherYearCostsPointsButDoesNotRemoveTheHit() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("remake", "Halloween (2018) Main Theme"),
                hit("original", "Halloween (1978) Main Theme"),
            ),
            title = "Halloween",
            year = "1978",
        )
        assertEquals(listOf("original", "remake"), ranked.map { it.videoId })
    }

    @Test
    fun leadingArticleIsNotRequired() {
        val ranked = rankThemeSongHits(listOf(hit("a", "Godfather Theme")), title = "The Godfather", year = null)
        assertTrue(ranked.isNotEmpty())
    }

    @Test
    fun emptyTitleGivesNothing() {
        assertTrue(rankThemeSongHits(listOf(hit("a", "Theme")), title = "  ", year = null).isEmpty())
    }

    @Test
    fun spokenScenesAreRejected() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("speech", "Gandalf speech - Lord of the Rings: The Return of the King (Soundtrack)", seconds = 200),
                hit("theme", "The Lord of the Rings: The Return of the King - Main Theme", seconds = 240),
            ),
            title = "The Lord of the Rings: The Return of the King",
            year = "2003",
        )
        assertEquals(listOf("theme"), ranked.map { it.videoId })
    }

    @Test
    fun aWordInTheFilmsOwnTitleIsNotUnwanted() {
        val ranked = rankThemeSongHits(
            listOf(hit("a", "The King's Speech - Main Theme")),
            title = "The King's Speech",
            year = "2010",
        )
        assertEquals(listOf("a"), ranked.map { it.videoId })
    }

    @Test
    fun aVerifiedLiveClipLosesToAPlainUploadOfTheOriginal() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("live", "Only Fools and Horses Theme - Live on The Jonathan Ross Show", channel = "ITV", verified = true, views = 5_000_000),
                hit("original", "Only Fools and Horses Theme", channel = "Some Fan"),
            ),
            title = "Only Fools and Horses",
            year = "1981",
        )
        assertEquals(listOf("original", "live"), ranked.map { it.videoId })
    }

    @Test
    fun eachPerformanceWordCostsPointsButDoesNotRemoveTheHit() {
        for (word in listOf("Live", "Performance", "Performs", "Sings", "Acoustic", "Tribute")) {
            val ranked = rankThemeSongHits(
                listOf(
                    hit("odd", "Alien Theme $word"),
                    hit("plain", "Alien Theme"),
                ),
                title = "Alien",
                year = null,
            )
            assertEquals(listOf("plain", "odd"), ranked.map { it.videoId }, "word: $word")
        }
    }

    @Test
    fun aPerformanceWordStillWinsWhenItIsTheOnlyUpload() {
        val ranked = rankThemeSongHits(
            listOf(hit("live", "Alien Theme (Live)")),
            title = "Alien",
            year = null,
        )
        assertEquals(listOf("live"), ranked.map { it.videoId })
    }

    @Test
    fun aPerformanceWordInTheFilmsOwnTitleIsNotPenalised() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("a", "Live and Let Die - Main Theme", channel = "Some Fan"),
                hit("b", "Live and Let Die - Theme", channel = "Another Fan", views = 50_000),
            ),
            title = "Live and Let Die",
            year = "1973",
        )
        assertEquals(listOf("a", "b"), ranked.map { it.videoId })
    }

    @Test
    fun fullTrackOutranksShortExcerpt() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("snippet", "Halloween Main Theme", seconds = 50),
                hit("full", "Halloween Main Theme", seconds = 180),
            ),
            title = "Halloween",
            year = null,
        )
        assertEquals(listOf("full", "snippet"), ranked.map { it.videoId })
    }

    @Test
    fun aCatalogueQualifierIsSearchedForButNotRequired() {
        assertEquals("Shameless" to "US", splitTitleQualifier("Shameless (US)"))
        assertEquals("Ghosts" to "2021", splitTitleQualifier("Ghosts (2021)"))
        // Not a qualifier: part of the name.
        assertEquals("The Office (An American Workplace)" to null, splitTitleQualifier("The Office (An American Workplace)"))
        assertEquals("Shameless US theme song", themeSongSearchQuery("Shameless (US)", "2011", isSeries = true))
        assertEquals("Ghosts 2021 main theme", themeSongSearchQuery("Ghosts (2021)", null, isSeries = false))

        val hits = listOf(
            hit("plain", "Shameless Theme Song", seconds = 95),
            hit("us", "Shameless (US) - Opening Theme", seconds = 95),
        )
        val ranked = rankThemeSongHits(hits, "Shameless (US)", "2011")
        // Neither upload was thrown out for lacking "US"; the one that has it comes first.
        assertEquals(listOf("us", "plain"), ranked.map { it.videoId })
    }

    // The cases below are real picks from the app's log (2026-11) that played the wrong thing.

    @Test
    fun animeSearchesForItsOpening() {
        assertEquals("Sword Art Online opening", themeSongSearchQuery("Sword Art Online", "2012", isSeries = true, isAnime = true))
    }

    @Test
    fun titleThatIsTheSubtitleOfAGameIsRejected() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("game", "Fallout 76: The Pitt Theme OST", views = 2_000_000),
                hit("show", "The Pitt - Main Title Theme"),
            ),
            title = "The Pitt",
            year = "2025",
        )
        assertEquals(listOf("show"), ranked.map { it.videoId })
    }

    @Test
    fun wrestlingEntranceThemeIsRejected() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("wrestler", "Drew Galloway 2nd TNA Theme Song''Ghosts In Us by Dale Oliver''"),
                hit("show", "Ghosts Theme Song"),
            ),
            title = "Ghosts (US)",
            year = "2021",
        )
        assertEquals(listOf("show"), ranked.map { it.videoId })
    }

    @Test
    fun countryQualifierCountsOnlyAsWritten() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("pronoun", "Ghosts In Us Theme"),
                hit("us", "Ghosts US Theme Song"),
            ),
            title = "Ghosts (US)",
            year = "2021",
        )
        assertEquals("us", ranked.first().videoId)
    }

    @Test
    fun coversFeaturettesAndDanceClipsAreRejected() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("cover", "Sword Art Online - \"Crossing Field\" (Opening) | ENGLISH ver", channel = "AmaLee", views = 9_000_000),
                hit("op", "Sword Art Online Opening 1 Full - Crossing Field"),
            ),
            title = "Sword Art Online",
            year = "2012",
        )
        assertEquals(listOf("op"), ranked.map { it.videoId })
        assertTrue(
            rankThemeSongHits(
                listOf(hit("featurette", "Station Eleven With Dan Romer & Patrick Somerville - \"Birth Of A Theme\" (Spoilers)", verified = true)),
                title = "Station Eleven",
                year = "2021",
            ).isEmpty(),
        )
        // A clip of the cast dancing to the theme is the last resort, behind the opening itself.
        assertEquals(
            "opening",
            rankThemeSongHits(
                listOf(
                    hit("dance", "Georgie & Mandy's First Marriage 1x01 Theme Song Dance | Young Sheldon Spinoff", verified = true),
                    hit("opening", "Georgie & Mandy's First Marriage - Season 1 Opening Theme"),
                ),
                title = "Georgie & Mandy's First Marriage",
                year = "2025",
            ).first().videoId,
        )
    }

    @Test
    fun characterAndBattleCuesLoseToTheMainTheme() {
        val rick = rankThemeSongHits(
            listOf(
                hit("evil", "Rick and Morty Soundtrack - Evil Morty's Theme (Quality Extended)", views = 20_000_000),
                hit("main", "Rick and Morty Theme Song", views = 50_000),
            ),
            title = "Rick and Morty",
            year = "2013",
        )
        assertEquals("main", rick.first().videoId)
        val broly = rankThemeSongHits(
            listOf(hit("battle", "Dragon Ball Super Broly OST - Gogeta vs Broly Full Theme Song")),
            title = "Dragon Ball Super: Broly",
            year = "2018",
        )
        assertTrue(broly.isEmpty())
    }

    @Test
    fun goodPicksStillPass() {
        val friends = rankThemeSongHits(
            listOf(hit("friends", "The Rembrandts - I'll Be There For You (Theme From FRIENDS)", channel = "RHINO", verified = true)),
            title = "Friends",
            year = "1994",
        )
        assertEquals(listOf("friends"), friends.map { it.videoId })
        val institute = rankThemeSongHits(
            listOf(hit("shout", "Shout (The Institute Theme Song)", channel = "The Lumineers - Topic")),
            title = "The Institute",
            year = "2025",
        )
        assertEquals(listOf("shout"), institute.map { it.videoId })
    }

    // Station 19 has only a title card, so YouTube's "intro" and "theme song" results (2026-11-13)
    // were fan remakes set to other shows' themes and songs from single episodes. Friends played.
    @Test
    fun anotherShowsThemeAndEpisodeSongsAreNotTheTitlesTheme() {
        val hits = listOf(
            hit("friendsStyle", "Station 19 Intro [Friends Style]", channel = "BABS", seconds = 101, views = 23_940),
            hit("friendsTheme", "station 19 intro (friends theme song)", channel = "greysvicley", seconds = 87, views = 5_202),
            hit("firemanSam", "Station 19 - fireman sam theme song", channel = "The Irish", seconds = 70, views = 139),
            hit("episodeSong", "Natalie Taylor - Wildfire (Audio) [STATION 19 - 1X04 - SOUNDTRACK]", seconds = 191, views = 95_634),
            hit("episodeSong2", "Station 19 Soundtrack - \"Hero (I Will Survive)\" by Supergirl (1x01)", seconds = 169, views = 74_054),
        )
        assertTrue(rankThemeSongHits(hits, "Station 19", "2018").isEmpty())
        assertTrue(rankThemeSongHits(hits, "Station 19", "2018", intro = true).isEmpty())
    }

    @Test
    fun aThemeNamedAfterItsComposerOrFromTheTitleStillCounts() {
        val ranked = rankThemeSongHits(
            listOf(
                hit("composer", "Hans Zimmer - Interstellar Main Theme", seconds = 240),
                hit("from", "John Williams - Theme from Jurassic Park", seconds = 220),
                hit("season", "South Park Season 1 (Episodes 1-5) Theme Song Intro", seconds = 60),
            ),
            title = "Interstellar",
            year = null,
        )
        assertEquals(listOf("composer"), ranked.map { it.videoId })
        assertEquals(
            listOf("from"),
            rankThemeSongHits(listOf(hit("from", "John Williams - Theme from Jurassic Park", seconds = 220)), "Jurassic Park", null).map { it.videoId },
        )
        assertEquals(
            listOf("season"),
            rankThemeSongHits(listOf(hit("season", "South Park Season 1 (Episodes 1-5) Theme Song Intro", seconds = 60)), "South Park", null, intro = true).map { it.videoId },
        )
    }
}
