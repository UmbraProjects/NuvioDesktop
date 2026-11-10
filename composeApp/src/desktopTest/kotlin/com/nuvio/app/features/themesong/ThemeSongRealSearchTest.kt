package com.nuvio.app.features.themesong

import com.nuvio.app.features.trailer.YouTubeSearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ranker against real YouTube results (captured 2026-11-10 with the app's own request: InnerTube
 * WEB, videos only). The shows are the ones a test round found wrong or right; each pick must be the
 * opening as aired, not the full-length single, a cover act, a comparison or a clip.
 */
class ThemeSongRealSearchTest {
    private val searches: Map<String, List<YouTubeSearchHit>> by lazy {
        val text = javaClass.getResource("/themesong/youtube-search-2026-11.json")!!.readText()
        (Json.parseToJsonElement(text) as JsonObject).mapValues { (_, hits) ->
            (hits as JsonArray).map { element ->
                val hit = element as JsonObject
                YouTubeSearchHit(
                    videoId = hit["videoId"]!!.jsonPrimitive.content,
                    title = hit["title"]!!.jsonPrimitive.content,
                    channel = hit["channel"]!!.jsonPrimitive.content,
                    verified = hit["verified"]!!.jsonPrimitive.boolean,
                    durationSeconds = hit["durationSeconds"]!!.jsonPrimitive.int,
                    views = hit["views"]!!.jsonPrimitive.long,
                )
            }
        }
    }

    private fun introPicks(title: String, year: String? = null): List<YouTubeSearchHit> {
        val hits = searches.getValue(themeIntroSearchQuery(title))
        return rankThemeSongHits(hits, title, year, intro = true)
    }

    private fun assertPick(title: String, year: String, vararg accepted: String) {
        val pick = introPicks(title, year).firstOrNull()
        assertTrue(
            pick != null && pick.videoId in accepted,
            "$title picked ${pick?.videoId} \"${pick?.title}\" (${pick?.channel}), expected one of ${accepted.toList()}",
        )
    }

    // Previously wrong.

    @Test
    fun southParkPlaysItsOpeningNotAGameSoundtrack() =
        assertPick("South Park", "1997", "S8p22rtNMoM", "BQFrthyb-Wc", "CYv7_DpiuIQ", "1AhdZ8ScYxQ")

    @Test
    fun familyGuyPlaysTheMainTitleNotTheFullSongOrAnEpisodeGag() =
        assertPick("Family Guy", "1999", "VileKLJz6tQ")

    @Test
    fun orangeIsTheNewBlackPlaysNetflixsOpeningNotACoverAct() =
        assertPick("Orange Is the New Black", "2013", "fBITGyJynfA", "hY_tX9wW3bc")

    @Test
    fun chicagoMedPlaysItsOpeningCredits() =
        assertPick("Chicago Med", "2015", "ONmk__AbB_8")

    @Test
    fun georgieAndMandyFindsItsOpening() =
        assertPick("Georgie & Mandy's First Marriage", "2024", "ljbktU99ITA", "41Sbd6Byox4")

    @Test
    fun scrubsPlaysTheShortIntroNotTheMusicVideo() =
        assertPick("Scrubs", "2001", "kBz1vvlkzSw", "0Rzh_yHaHmc", "bhh7qzurMZY")

    @Test
    fun newGirlPlaysTheOpeningNotTheFullSong() =
        assertPick("New Girl", "2011", "1Mrxye-LKy0", "8tK33mC_f_8", "aW3LEVGQ-lE")

    @Test
    fun dexterResurrectionPlaysAnIntroNotAScoreCue() =
        assertPick("Dexter: Resurrection", "2025", "wf7eOAlI8G0", "jsJoz1vW7eE", "nnLFtoqxJNY")

    @Test
    fun kimsConvenienceDoesNotPlayACharacterSketch() {
        val pick = introPicks("Kim's Convenience", "2016").firstOrNull()
        assertTrue(pick?.videoId != "_8u65v2wveo", "picked the \"Handy staff intro\" sketch")
    }

    // Previously right, and must stay right.

    @Test
    fun shamelessWentworthFriendsSiloAndGreysStayRight() {
        assertPick("Shameless (US)", "2011", "LCTdi-TYrA8", "JnCqi6QCnT8", "K4aVlEOJYIE")
        assertPick("Wentworth", "2013", "2FFVs7sHD-Y", "J_J1ESSksAA", "AzIwrQRXdDc")
        assertPick("Friends", "1994", "Niu9Zmrx0p8", "s2TyVQGoCYo")
        assertPick("Silo", "2023", "AJlOS6ZeIcA")
        assertPick("Grey's Anatomy", "2005", "GKyvMFkuCog", "m-ZEyCmsjc0", "pXLq67QUM2k")
    }
}
