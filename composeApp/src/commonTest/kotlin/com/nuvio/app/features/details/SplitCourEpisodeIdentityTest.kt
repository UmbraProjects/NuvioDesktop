package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AIOMetadata lists a whole anime franchise under one IMDb meta, but numbers the rows with
 * franchise season/episode while keeping each cour's own native id. Dr. STONE season 4 is
 * three kitsu entries: 48342 covers S04E01-E12, 49751 covers E13-E24 and 50187 covers E25-E26,
 * each restarting its episode segment at 1.
 *
 * The player used to resolve its active row from the *stream* coordinates, which for a native
 * anime id is the franchise season paired with the cour-local episode — (4, 1) while S04E25 was
 * playing. That matched the S04E01 row, so the next/previous cards, autoplay and the sources
 * panel all walked the franchise from the wrong place: S04E24 advanced to S04E13, S04E25 to
 * S04E02. These tests lock the canonical coordinates (and the row id) as the resolution input.
 */
class SplitCourEpisodeIdentityTest {

    private val drStoneSeason4: List<MetaVideo> = buildList {
        (1..12).forEach { local ->
            add(MetaVideo(id = "kitsu:48342:$local", title = "E$local", season = 4, episode = local))
        }
        (1..12).forEach { local ->
            add(MetaVideo(id = "kitsu:49751:$local", title = "E${local + 12}", season = 4, episode = local + 12))
        }
        (1..2).forEach { local ->
            add(MetaVideo(id = "kitsu:50187:$local", title = "E${local + 24}", season = 4, episode = local + 24))
        }
    }

    @Test
    fun courLocalStreamIdResolvesToItsFranchiseRow() {
        val resolved = drStoneSeason4.resolveSeriesEpisodePosition(
            parentMetaId = "tt9679542",
            videoId = "kitsu:50187:1",
            seasonNumber = 4,
            episodeNumber = 25,
        )

        assertEquals("kitsu:50187:1", resolved?.video?.id)
        assertEquals(4, resolved?.seasonNumber)
        assertEquals(25, resolved?.episodeNumber)
    }

    @Test
    fun canonicalCoordinatesResolveTheRowWithoutAVideoId() {
        // The App-side lookup falls back to coordinates when the launch carries no row id.
        val resolved = drStoneSeason4.resolveSeriesEpisodePosition(
            parentMetaId = "tt9679542",
            videoId = null,
            seasonNumber = 4,
            episodeNumber = 25,
        )

        assertEquals("kitsu:50187:1", resolved?.video?.id)
    }

    @Test
    fun lastEpisodeOfACourAdvancesIntoTheNextCour() {
        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = drStoneSeason4,
            currentSeason = 4,
            currentEpisode = 24,
            currentVideoId = "kitsu:49751:12",
            parentMetaId = "tt9679542",
        )

        assertEquals("kitsu:50187:1", next?.id)
    }

    @Test
    fun firstEpisodeOfTheFinalCourDoesNotWalkBackToTheSeasonStart() {
        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = drStoneSeason4,
            currentSeason = 4,
            currentEpisode = 25,
            currentVideoId = "kitsu:50187:1",
            parentMetaId = "tt9679542",
        )

        assertEquals("kitsu:50187:2", next?.id)
    }

    @Test
    fun theOldStreamCoordinatePairingWouldHavePickedTheWrongRow() {
        // Regression guard documenting the defect: the cour-local episode carries no season, so
        // pairing it with the franchise season addressed a completely different episode.
        val misresolved = drStoneSeason4.resolveSeriesEpisodePosition(
            parentMetaId = "tt9679542",
            videoId = null,
            seasonNumber = 4,
            episodeNumber = 1, // kitsu:50187:1's cour-local number
        )

        assertEquals("kitsu:48342:1", misresolved?.video?.id)
    }
}
