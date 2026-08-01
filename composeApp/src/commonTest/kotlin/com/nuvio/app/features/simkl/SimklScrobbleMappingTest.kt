package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SIMKL's side of the entry-local projection: carrying out the id decision.
 *
 * The decision itself (which coordinates, and whether the per-entry ids survive) is covered by
 * `TrackingIdProjectionTest`; this only checks that a `false` decision strips exactly the per-entry
 * namespaces and leaves the franchise ids intact.
 */
class SimklScrobbleMappingTest {
    private val fullIds = SimklScrobbleRepository.SimklIds(
        simkl = 1234,
        imdb = "tt0168366",
        tmdb = 60572,
        tvdb = 76703,
        mal = 527,
        kitsu = 486,
        anilist = 1565,
        anidb = 837,
    )

    @Test
    fun `dropping entry ids keeps every franchise id`() {
        val ids = with(SimklScrobbleRepository) { fullIds.retainingNativeAnimeIds(retains = false) }

        assertEquals("tt0168366", ids.imdb)
        assertEquals(60572, ids.tmdb)
        assertEquals(76703, ids.tvdb)
        assertNull(ids.simkl)
        assertNull(ids.mal)
        assertNull(ids.kitsu)
        assertNull(ids.anilist)
        assertNull(ids.anidb)
    }

    @Test
    fun `retaining entry ids leaves the set untouched`() {
        val ids = with(SimklScrobbleRepository) { fullIds.retainingNativeAnimeIds(retains = true) }

        assertEquals(fullIds, ids)
    }

    @Test
    fun `an entry-only id set becomes unusable once its entry ids are dropped`() {
        // This is what makes the caller's null-check load-bearing: a Kitsu-only anime with an
        // ambiguous franchise season has nothing left to address, and must be skipped rather than
        // sent with a title-only match.
        val entryOnly = SimklScrobbleRepository.SimklIds(simkl = 5678, kitsu = 1404)

        val ids = with(SimklScrobbleRepository) { entryOnly.retainingNativeAnimeIds(retains = false) }

        assertEquals(SimklScrobbleRepository.SimklIds(), ids)
    }
}
