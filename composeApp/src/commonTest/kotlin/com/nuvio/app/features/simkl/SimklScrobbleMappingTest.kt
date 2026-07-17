package com.nuvio.app.features.simkl

import com.nuvio.app.features.metadata.ResolvedMediaIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimklScrobbleMappingTest {
    @Test
    fun `ambiguous Pokemon franchise season drops stale entry-local ids`() {
        val resolved = ResolvedMediaIds(
            sourceId = "kitsu:486",
            contentType = "series",
            imdb = "tt0168366",
            tmdb = 60572,
            tvdb = 76703,
            simkl = 1234,
            mal = 527,
            kitsu = 486,
            isAnime = true,
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = false,
        )

        val ids = with(SimklScrobbleRepository) {
            resolved.idsForSimklScrobble(
                SimklScrobbleRepository.SimklIds(
                    simkl = 1234,
                    imdb = "tt0168366",
                    tmdb = 60572,
                    tvdb = 76703,
                    mal = 527,
                    kitsu = 486,
                ),
                isAnime = true,
            )
        }

        assertEquals("tt0168366", ids.imdb)
        assertEquals(60572, ids.tmdb)
        assertEquals(76703, ids.tvdb)
        assertNull(ids.simkl)
        assertNull(ids.mal)
        assertNull(ids.kitsu)
    }

    @Test
    fun `explicitly mapped anime season retains entry-local ids`() {
        val resolved = ResolvedMediaIds(
            sourceId = "kitsu:1404",
            contentType = "series",
            simkl = 5678,
            kitsu = 1404,
            isAnime = true,
            franchiseNumbering = true,
            nativeMappingCoversFranchiseEpisode = true,
        )
        val original = SimklScrobbleRepository.SimklIds(simkl = 5678, kitsu = 1404)

        val ids = with(SimklScrobbleRepository) {
            resolved.idsForSimklScrobble(original, isAnime = true)
        }

        assertEquals(original, ids)
    }
}
