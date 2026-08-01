package com.nuvio.app.features.locallibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalAnimeFixTest {

    @Test
    fun `rows preserve scanner coordinates while editing entry-relative episodes`() {
        val item = animeItem(
            LocalMediaFile(
                path = "D:\\Anime\\Show\\Season 15\\ep25.mkv",
                season = 15,
                episode = 25,
                mappedEpisode = 1,
            ),
        )

        val row = LocalAnimeFixPlanner.rows(item).single()

        assertNull(row.season)
        assertEquals(15, row.sourceSeason)
        assertEquals(1, row.episode)
        assertTrue(row.included)
    }

    @Test
    fun `excluded files remain available when the mapping dialog is reopened`() {
        val item = animeItem(
            LocalMediaFile(
                path = "D:\\Anime\\Show\\extra.mkv",
                episode = 99,
                excludedFromEpisodeMapping = true,
            ),
        )

        val row = LocalAnimeFixPlanner.rows(item).single()

        assertFalse(row.included)
        assertEquals(99, row.episode)
    }

    @Test
    fun `mapping records every row without changing its path`() {
        val item = animeItem(
            LocalMediaFile(path = "D:\\Anime\\Show\\a.mkv", season = 17, episode = 12),
            LocalMediaFile(path = "D:\\Anime\\Show\\b.mkv", season = 17, episode = 13),
        )
        val rows = LocalAnimeFixPlanner.rows(item).mapIndexed { index, row ->
            if (index == 0) row.copy(episode = 13) else row.copy(episode = 12, included = false)
        }

        val mappings = requireNotNull(LocalAnimeFixPlanner.mappings(rows))

        assertEquals(
            listOf(
                LocalEpisodeMapping("D:\\Anime\\Show\\a.mkv", 13, included = true),
                LocalEpisodeMapping("D:\\Anime\\Show\\b.mkv", 12, included = false),
            ),
            mappings,
        )
    }

    @Test
    fun `applying a saved map overlays coordinates and excludes unknown new files`() {
        val files = listOf(
            LocalMediaFile(path = "D:\\Anime\\Show\\a.mkv", season = 17, episode = 12),
            LocalMediaFile(path = "D:\\Anime\\Show\\excluded.mkv", season = 17, episode = 13),
            LocalMediaFile(path = "D:\\Anime\\Show\\new.mkv", season = 17, episode = 14),
        )

        val mapped = files.withEpisodeMappings(
            listOf(
                LocalEpisodeMapping("d:\\anime\\show\\A.mkv", 3, included = true),
                LocalEpisodeMapping("D:\\Anime\\Show\\excluded.mkv", 8, included = false),
            ),
        )

        assertEquals(17, mapped[0].season)
        assertEquals(12, mapped[0].episode)
        assertEquals(3, mapped[0].effectiveEpisode)
        assertNull(mapped[0].effectiveSeason)
        assertTrue(mapped[0].isEpisodePlayable)
        assertEquals(8, mapped[1].effectiveEpisode)
        assertFalse(mapped[1].isEpisodePlayable)
        assertFalse(mapped[2].isEpisodePlayable)
    }

    @Test
    fun `keeping nothing is not a mapping`() {
        val rows = LocalAnimeFixPlanner.rows(
            animeItem(LocalMediaFile(path = "D:\\Anime\\Show\\a.mkv", episode = 1)),
        ).map { it.copy(included = false) }

        assertNull(LocalAnimeFixPlanner.mappings(rows))
    }

    @Test
    fun `samples are not offered for mapping`() {
        val item = animeItem(
            LocalMediaFile(path = "D:\\Anime\\Show\\sample.mkv", episode = 1),
            LocalMediaFile(path = "D:\\Anime\\Show\\ep01.mkv", episode = 1),
        )

        assertEquals(listOf("ep01.mkv"), LocalAnimeFixPlanner.rows(item).map { it.fileName })
    }

    private fun animeItem(vararg files: LocalMediaFile) = LocalMediaItem(
        key = "folder-1:show",
        folderId = "folder-1",
        type = LocalFolderType.SERIES,
        isAnime = true,
        title = "Show",
        kitsuId = 5605,
        matchState = LocalMatchState.MANUAL,
        files = files.toList(),
    )
}
