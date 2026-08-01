package com.nuvio.app.features.simkl

import com.nuvio.app.features.library.LibraryItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklLibraryMutationTest {
    private val item = LibraryItem(
        id = "tt2543164",
        type = "movie",
        name = "Arrival",
        savedAtEpochMs = 0L,
    )

    @Test
    fun `add sends per-item plan to watch destination`() {
        val body = SimklLibraryRepository.encodeLibraryMutationForTest(
            item = item,
            ids = SimklScrobbleRepository.SimklIds(imdb = item.id),
            desired = true,
        )

        assertTrue("\"movies\"" in body)
        assertTrue("\"to\":\"plantowatch\"" in body)
    }

    @Test
    fun `remove omits destination for history remove endpoint`() {
        val body = SimklLibraryRepository.encodeLibraryMutationForTest(
            item = item,
            ids = SimklScrobbleRepository.SimklIds(imdb = item.id),
            desired = false,
        )

        assertFalse("\"to\"" in body)
    }
}
