package com.nuvio.app.features.music.home

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicSearchIntentTest {
    private val eminem = listOf("Eminem")
    private val rows = listOf(
        IntentRow(IntentKind.Songs, listOf(IntentHit("Lose Yourself", eminem), IntentHit("Without Me", eminem), IntentHit("Encore", eminem))),
        IntentRow(IntentKind.Albums, listOf(IntentHit("The Eminem Show", eminem), IntentHit("Encore (Deluxe Version)", eminem))),
        IntentRow(IntentKind.Singles, listOf(IntentHit("Without Me", eminem))),
        IntentRow(IntentKind.Artists, listOf(IntentHit("Eminem", emptyList()), IntentHit("Eminem Tribute", emptyList()))),
        IntentRow(IntentKind.Videos, listOf(IntentHit("Eminem - Without Me (Official Video)", eminem))),
    )

    private fun firstKind(query: String) = rows[intentOrder(query, rows).first().first].kind

    @Test
    fun anArtistsNameAloneBringsArtistsFirst() {
        assertEquals(IntentKind.Artists, firstKind("Eminem"))
        assertEquals(IntentKind.Artists, firstKind("eminem"))
    }

    @Test
    fun artistAndASongBringSongsFirst() {
        assertEquals(IntentKind.Songs, firstKind("Eminem without me"))
        assertEquals(IntentKind.Songs, firstKind("without me eminem"))
        // And that song moves to the front of its row.
        val songs = intentOrder("Eminem without me", rows).first().second
        assertEquals(1, songs.first())
    }

    @Test
    fun artistAndAnAlbumBringAlbumsFirstEvenWhenASongHasTheSameName() {
        assertEquals(IntentKind.Albums, firstKind("Eminem encore"))
        val albums = intentOrder("Eminem encore", rows).first().second
        assertEquals(1, albums.first())
    }

    @Test
    fun anOrdinarySearchKeepsTheUsualOrder() {
        assertEquals(listOf(0, 1, 2, 3, 4), intentOrder("eminem live 2002", rows).map { it.first })
    }

    @Test
    fun punctuationAndCaseDoNotMatter() {
        assertEquals("ac dc back in black", intentNormal("AC/DC — Back in Black!"))
        assertEquals("simon and garfunkel", intentNormal("Simon & Garfunkel"))
    }

    @Test
    fun anEpWinsOverTheSongAndSingleOfTheSameName() {
        val atc = listOf("Against The Current")
        val rows = listOf(
            IntentRow(IntentKind.Songs, listOf(IntentHit("Gravity", atc))),
            IntentRow(IntentKind.EPs, listOf(IntentHit("Gravity (The Acoustic Sessions Volume II)", atc))),
            IntentRow(IntentKind.Singles, listOf(IntentHit("Gravity", atc))),
        )
        assertEquals(IntentKind.EPs, rows[intentOrder("Against the current gravity", rows).first().first].kind)
    }

    @Test
    fun equallyGoodMatchesGoMostPopularFirst() {
        val rows = listOf(
            IntentRow(
                IntentKind.Albums,
                listOf(
                    IntentHit("Love Is Dead", listOf("gofun"), popularityTier(7)),
                    IntentHit("Love Is Dead", listOf("Some Band"), popularityTier(4_000)),
                    IntentHit("Love Is Dead", listOf("CHVRCHES"), popularityTier(900_000)),
                    IntentHit("Love Is Dead Remixes", listOf("CHVRCHES"), popularityTier(900_000)),
                ),
            ),
        )
        // The three exact titles first, best known first; the remix album (not the title searched) after them.
        assertEquals(listOf(2, 1, 0, 3), intentOrder("love is dead", rows).first().second)
    }

    @Test
    fun popularityIsBroadStepsNotExactCounts() {
        assertEquals(0, popularityTier(7))
        assertEquals(1, popularityTier(null))
        assertEquals(popularityTier(20_000), popularityTier(400_000))
        assertEquals(3, popularityTier(13_000_000))
    }

    @Test
    fun readsAnArtistsFansFromADeezerSearch() {
        val body = """{"data":[{"name":"Chvrches Tribute","nb_fan":12},{"name":"CHVRCHES","nb_fan":184600}]}"""
        assertEquals(184600L, parseDeezerArtistFans(body, "Chvrches"))
        assertEquals(null, parseDeezerArtistFans(body, "Muse"))
    }
}
