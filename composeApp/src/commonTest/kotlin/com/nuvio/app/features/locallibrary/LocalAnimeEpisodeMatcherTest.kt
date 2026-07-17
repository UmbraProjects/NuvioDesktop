package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the matcher paths that don't depend on anime-list-mini.json being present: an item
 * with no native kitsu/mal id never consults the mapping, so these results are deterministic.
 */
class LocalAnimeEpisodeMatcherTest {

    private val s1e1 = LocalMediaFile(path = "/anime/SAO/SAO S01E01.mkv", season = 1, episode = 1)
    private val s2e1 = LocalMediaFile(path = "/anime/SAO/SAO S02E01.mkv", season = 2, episode = 1)
    private val abs12 = LocalMediaFile(path = "/anime/Frieren/Frieren - 12.mkv", season = null, episode = 12)

    private val item = LocalMediaItem(
        key = "folder:sao",
        folderId = "folder",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Sword Art Online",
        imdbId = "tt2250192",
        tmdbId = 45782,
        files = listOf(s1e1, s2e1, abs12),
    )

    @Test
    fun foreignVideoIdMatchesNothing() {
        // Another show's episode id (e.g. reaching us through a stale meta) must not match,
        // even though its season/episode coordinates line up with a file on disk.
        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(item, "tt0108778:1:1")!!.isEmpty())
    }

    @Test
    fun seasonedFilesMatchFranchiseCoordinates() {
        assertEquals(listOf(s1e1), LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:1:1"))
        assertEquals(listOf(s2e1), LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:2:1"))
    }

    @Test
    fun missingEpisodeMatchesNothing() {
        // S03E01 is not on disk — must return an empty list, not a wrong-season file.
        assertTrue(LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:3:1")!!.isEmpty())
    }

    @Test
    fun absoluteFileMatchesSeasonOneCoordinatesWhenUnmapped() {
        // With no mapping entry the item is treated as a plain season-1 entry.
        assertEquals(listOf(abs12), LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192:1:12"))
    }

    @Test
    fun idWithoutEpisodeCoordinatesIsUnparsed() {
        assertNull(LocalAnimeEpisodeMatcher.matchFiles(item, "tt2250192"))
    }

    @Test
    fun tmdbStyleFourPartIdsParse() {
        assertEquals(listOf(s2e1), LocalAnimeEpisodeMatcher.matchFiles(item, "tmdb:45782:2:1"))
    }

    @Test
    fun myAnimeListAliasMatchesAbsoluteFile() {
        val malItem = item.copy(malId = 123, files = listOf(abs12))

        assertEquals(
            listOf(abs12),
            LocalAnimeEpisodeMatcher.matchFiles(malItem, "myanimelist:123:12"),
        )
    }
}
