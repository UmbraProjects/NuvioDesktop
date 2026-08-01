package com.nuvio.app.features.librarypvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualGrabPlannerTest {

    private val gigabyte = 1_000_000_000L

    private fun monitored(
        contentType: String = "series",
        isAnime: Boolean = false,
        contentId: String = "tt0108778",
    ) = MonitoredItem(
        id = "mon-1",
        contentId = contentId,
        contentType = contentType,
        title = "Friends",
        year = 1994,
        targetFolderId = "folder-1",
        mode = if (contentType == "series") MonitorMode.SELECTED_PLUS_FUTURE else MonitorMode.MOVIE_WHEN_AVAILABLE,
        isAnime = isAnime,
        addedAtEpochMs = 0L,
    )

    private fun file(name: String, sizeBytes: Long? = 4 * 1_000_000_000L) =
        ManualSourceFile(id = name, name = name, sizeBytes = sizeBytes)

    @Test
    fun `maps a season pack to episode coordinates in order`() {
        val rows = ManualGrabPlanner.plan(
            files = listOf(
                file("Friends.S03E02.1080p.mkv"),
                file("Friends.S03E01.1080p.mkv"),
            ),
            sourceName = "Friends.S03.REMUX",
            item = monitored(),
        )

        assertEquals(listOf(1, 2), rows.map { it.episode })
        assertTrue(rows.all { it.season == 3 })
        assertTrue(rows.all { it.included })
    }

    @Test
    fun `drops samples and non-video files`() {
        val rows = ManualGrabPlanner.plan(
            files = listOf(
                file("Friends.S03E01.1080p.mkv"),
                file("sample.mkv", sizeBytes = 8_000_000L),
                file("Friends.S03.nfo", sizeBytes = 2_000L),
                file("cover.jpg", sizeBytes = 500_000L),
            ),
            sourceName = "Friends.S03",
            item = monitored(),
        )

        assertEquals(listOf("Friends.S03E01.1080p.mkv"), rows.map { it.fileName })
    }

    @Test
    fun `falls back to the pack name for a season when the file omits it`() {
        val rows = ManualGrabPlanner.plan(
            files = listOf(file("Friends - E04.mkv")),
            sourceName = "Friends Season 03",
            item = monitored(),
        )

        assertEquals(3, rows.single().season)
        assertEquals(4, rows.single().episode)
        assertTrue(rows.single().hasRequiredCoordinates(monitored()))
    }

    @Test
    fun `selects only the largest file for a movie`() {
        val item = monitored(contentType = "movie")
        val rows = ManualGrabPlanner.plan(
            files = listOf(
                file("Extras.mkv", sizeBytes = 2 * gigabyte),
                file("The.Movie.2160p.mkv", sizeBytes = 30 * gigabyte),
            ),
            sourceName = "The Movie 2160p",
            item = item,
        )

        assertEquals("The.Movie.2160p.mkv", rows.first().fileName)
        assertTrue(rows.first().included)
        assertFalse(rows.last().included)
        // A movie needs no coordinates to be filed.
        assertTrue(rows.first().hasRequiredCoordinates(item))
    }

    @Test
    fun `reads absolute numbering for anime and needs no season`() {
        val item = monitored(isAnime = true)
        val rows = ManualGrabPlanner.plan(
            files = listOf(file("[Group] Show - 1075 [1080p].mkv")),
            sourceName = "[Group] Show",
            item = item,
        )

        assertEquals(1075, rows.single().episode)
        assertTrue(rows.single().hasRequiredCoordinates(item))
    }

    // The real listing from a [PokeArchive] "Seasons 14-16" pack opened on Rival Destinies
    // (kitsu:7080). Long-hand markers, one episode 1 per season, no season the entry can own.
    private fun pokeArchivePack() = listOf(
        file("[PokeArchive] Pokemon Season 14 - Ep01 - BW001 - Black and White [1080p].mkv"),
        file("[PokeArchive] Pokemon Season 15 - Ep01 - BW051 - BW Rival Destinies [1080p].mkv"),
        file("[PokeArchive] Pokemon Season 16 - Ep01 - BW100 - BW Adventures in Unova [1080p].mkv"),
        file("[PokeArchive] Pokemon Season 15 - Ep02 - BW052 - BW Rival Destinies [1080p].mkv"),
    )

    private val pokeArchiveSourceName =
        "[PokeArchive] Pokemon the Series - Black and White [Seasons 14-16] - Amazon CBR, PokemonTV"

    @Test
    fun `an anime-native id keeps the file's season as a label but not as a coordinate`() {
        val rows = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )

        // Every season was parsed from the file itself, not inherited from the pack name.
        assertEquals(listOf(14, 15, 15, 16), rows.map { it.sourceSeason })
        // ...and none of them is a coordinate: kitsu entries have no seasons to file under.
        assertTrue(rows.all { it.season == null })
        assertEquals(listOf(1, 1, 2, 1), rows.map { it.episode })
    }

    @Test
    fun `a multi-season pack under an anime-native id starts with nothing selected`() {
        val rows = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )

        // Three files claim entry-relative episode 1 and nothing in the names says which block is
        // this entry, so the plan refuses to guess instead of pre-selecting a conflict.
        assertTrue(rows.none { it.included })
        assertEquals(4, rows.size, "the files are kept and shown, only deselected")
    }

    @Test
    fun `a single-season anime source stays fully selected`() {
        val rows = ManualGrabPlanner.plan(
            files = listOf(
                file("[PokeArchive] Pokemon Season 15 - Ep01 - BW051.mkv"),
                file("[PokeArchive] Pokemon Season 15 - Ep02 - BW052.mkv"),
            ),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )

        assertTrue(rows.all { it.included })
        assertEquals(listOf(1, 2), rows.map { it.episode })
    }

    @Test
    fun `a franchise id keeps the season as a coordinate and stays selected`() {
        val rows = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = false,
        )

        assertEquals(listOf(14, 15, 15, 16), rows.map { it.season })
        assertTrue(rows.all { it.included })
        // S14E01 and S15E01 are different slots in franchise coordinates, so there is no conflict.
        assertEquals(4, rows.map { it.destinationSlot(entryRelative = false) }.distinct().size)
    }

    @Test
    fun `entry-relative rows sharing an episode collide even across seasons`() {
        val rows = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )

        // The whole point of deselecting: selecting all of these would write three files to one path.
        assertEquals(2, rows.map { it.destinationSlot(entryRelative = true) }.distinct().size)
    }

    @Test
    fun `anime-native ids are recognised by namespace`() {
        assertTrue(monitored(contentId = "kitsu:7080").usesEntryRelativeNumbering)
        assertTrue(monitored(contentId = "MAL:1234").usesEntryRelativeNumbering)
        assertTrue(monitored(contentId = "anilist:99").usesEntryRelativeNumbering)
        assertTrue(monitored(contentId = "anidb:42").usesEntryRelativeNumbering)
        // A franchise id in a non-anime folder keeps franchise coordinates...
        assertFalse(monitored(contentId = "tt0108778").usesEntryRelativeNumbering)
        assertFalse(monitored(contentId = "tmdb:1399").usesEntryRelativeNumbering)
        // ...but an anime *folder* still numbers absolutely, as it always has.
        assertTrue(monitored(contentId = "tt0108778", isAnime = true).usesEntryRelativeNumbering)
    }

    // --- Auto match ---

    /** The Adventures in Unova block: the entry's episode 1 is the pack's "Ep25". */
    private fun unovaBlock() = listOf(
        file("[PokeArchive] Pokemon Season 15 - Ep24 - BW074 - Rival Destinies.mkv"),
        file("[PokeArchive] Pokemon Season 15 - Ep25 - BW075 - Adventures in Unova.mkv"),
        file("[PokeArchive] Pokemon Season 15 - Ep26 - BW076 - Adventures in Unova.mkv"),
        file("[PokeArchive] Pokemon Season 15 - Ep27 - BW077 - Adventures in Unova.mkv"),
    )

    @Test
    fun `auto match renumbers from the anchor so the release group's numbering stops mattering`() {
        val rows = ManualGrabPlanner.plan(
            files = unovaBlock(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )
        // The user ticks the pack's "Ep25", which is this entry's episode 1.
        val anchor = rows.indexOfFirst { it.episode == 25 }

        val matched = rows.autoMatchedFromAnchor(anchorIndex = anchor, count = 3)

        assertEquals(listOf(false, true, true, true), matched.map { it.included })
        // Renumbered 1..3, not left at the pack's 25..27.
        assertEquals(listOf(1, 2, 3), matched.filter { it.included }.map { it.episode })
        // The untouched row keeps its own coordinates.
        assertEquals(24, matched.first().episode)
    }

    @Test
    fun `auto match deselects everything outside the run`() {
        val rows = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )
        // Anchor on the first Season 15 row; an entry of 2 episodes takes it and the next row only.
        val anchor = rows.indexOfFirst { it.sourceSeason == 15 }

        val matched = rows.autoMatchedFromAnchor(anchorIndex = anchor, count = 2)

        assertEquals(2, matched.count { it.included })
        assertTrue(matched.filterIndexed { index, _ -> index < anchor }.none { it.included })
        assertEquals(listOf(1, 2), matched.filter { it.included }.map { it.episode })
    }

    @Test
    fun `auto match stops at the end of the source rather than inventing rows`() {
        val rows = ManualGrabPlanner.plan(
            files = unovaBlock(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )
        val anchor = rows.lastIndex

        // The entry claims 24 episodes but only one row remains below the anchor.
        val matched = rows.autoMatchedFromAnchor(anchorIndex = anchor, count = 24)

        assertEquals(1, matched.count { it.included })
        assertEquals(1, matched.last().episode)
    }

    @Test
    fun `best wishes auto match includes the anchor plus 83 further episodes`() {
        val rows = (1..146).map { sourceEpisode ->
            ManualGrabRow(
                fileId = sourceEpisode.toString(),
                fileName = "Pokemon.S15E${sourceEpisode.toString().padStart(2, '0')}.mkv",
                sizeBytes = gigabyte,
                season = null,
                episode = sourceEpisode,
                episodeTitle = null,
                included = sourceEpisode == 10,
                sourceSeason = 15,
            )
        }
        val anchor = rows.indexOfFirst { it.included }

        val matched = rows.autoMatchedFromAnchor(anchorIndex = anchor, count = 84)

        assertEquals(84, matched.count { it.included })
        assertEquals((1..84).toList(), matched.filter { it.included }.map { it.episode })
        assertEquals(83, matched.drop(anchor + 1).count { it.included })
    }

    @Test
    fun `auto match with a nonsense anchor or count leaves the plan alone`() {
        val rows = ManualGrabPlanner.plan(
            files = unovaBlock(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )

        assertEquals(rows, rows.autoMatchedFromAnchor(anchorIndex = -1, count = 4))
        assertEquals(rows, rows.autoMatchedFromAnchor(anchorIndex = 0, count = 0))
    }

    @Test
    fun `leaves an unparsable name incomplete rather than guessing`() {
        val item = monitored()
        val rows = ManualGrabPlanner.plan(
            files = listOf(file("mystery-release.mkv")),
            sourceName = null,
            item = item,
        )

        assertFalse(rows.single().hasRequiredCoordinates(item))
    }

    // --- Renumber (multi-season selection) ---

    @Test
    fun `renumber collapses a multi-season selection onto one continuous run`() {
        val planned = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )
        // Sorted order is S14E01, S15E01, S15E02, S16E01. The user ticks the run that belongs to this
        // entry — S14E01 through S15E02 — and leaves the Season 16 file out.
        val selected = planned.mapIndexed { index, row -> if (index <= 2) row.copy(included = true) else row }

        val renumbered = selected.renumberSelectionContinuously()

        val included = renumbered.filter { it.included }
        // Continuous across the 14→15 boundary: 1, 2, 3 — not two seasons' worth of "E1".
        assertEquals(listOf(1, 2, 3), included.map { it.episode })
        // No two selected files collide on the entry-relative slot any more.
        assertEquals(3, included.map { it.destinationSlot(entryRelative = true) }.distinct().size)
        // The unticked row is left exactly as it was.
        assertFalse(renumbered.last().included)
        assertEquals(1, renumbered.last().episode)
    }

    @Test
    fun `renumber from a start episode shifts a single season onto its offset in the entry`() {
        val planned = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )
        // One season's worth of files (the two S15 entries), which the entry numbers from 27 on
        // because the TMDB season begins partway into this kitsu entry.
        val selected = planned.mapIndexed { index, row -> row.copy(included = index in 1..2) }

        val renumbered = selected.renumberSelectionContinuously(startEpisode = 27)

        assertEquals(listOf(27, 28), renumbered.filter { it.included }.map { it.episode })
    }

    @Test
    fun `renumber with nothing selected leaves the plan alone`() {
        val planned = ManualGrabPlanner.plan(
            files = pokeArchivePack(),
            sourceName = pokeArchiveSourceName,
            isMovie = false,
            entryRelative = true,
        )

        assertEquals(planned, planned.renumberSelectionContinuously())
    }
}
