package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshStagesTest {
    private fun ids(count: Int) = List(count) { "artist-$it" }

    @Test
    fun stagesAreFiveArtistsEachWithTheRemainderLast() {
        val stages = refreshStages(ids(12))
        assertEquals(listOf(5, 5, 2), stages.map { it.size })
    }

    @Test
    fun anExactMultipleHasNoEmptyTrailingStage() {
        assertEquals(listOf(5, 5), refreshStages(ids(10)).map { it.size })
    }

    @Test
    fun aSmallLibraryIsOneStage() {
        assertEquals(listOf(3), refreshStages(ids(3)).map { it.size })
    }

    @Test
    fun anEmptyLibraryHasNoStages() {
        assertEquals(emptyList(), refreshStages(emptyList()))
    }

    @Test
    fun orderIsKeptAcrossStages() {
        val all = ids(11)
        assertEquals(all, refreshStages(all).flatten())
        assertEquals(listOf("artist-0", "artist-1", "artist-2", "artist-3", "artist-4"), refreshStages(all).first())
    }

    @Test
    fun theStageSizeIsFive() {
        assertEquals(5, REFRESH_STAGE_SIZE)
    }

    @Test
    fun aNonsenseSizeStillMakesProgress() {
        assertEquals(listOf(1, 1, 1), refreshStages(ids(3), size = 0).map { it.size })
    }
}
