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
}
