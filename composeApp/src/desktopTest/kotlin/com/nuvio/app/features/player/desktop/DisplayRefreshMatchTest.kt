package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayRefreshMatchTest {
    private val film = 24000.0 / 1001.0
    private val tvRates = listOf(23, 24, 25, 29, 30, 50, 59, 60)

    @Test
    fun ntscListingsMeanTheFractionalRate() {
        assertEquals(film, effectiveRefreshHz(23), 1e-9)
        assertEquals(60000.0 / 1001.0, effectiveRefreshHz(59), 1e-9)
        assertEquals(120000.0 / 1001.0, effectiveRefreshHz(119), 1e-9)
        assertEquals(60.0, effectiveRefreshHz(60), 0.0)
        assertEquals(144.0, effectiveRefreshHz(144), 0.0)
    }

    @Test
    fun filmOnSixtyHertzMovesToTheExactFilmRate() {
        assertEquals(23, pickDisplayRefreshRate(film, 60, tvRates))
    }

    @Test
    fun filmUsesTwentyFourWhenNoFractionalRateIsListed() {
        assertEquals(24, pickDisplayRefreshRate(film, 60, listOf(24, 50, 60)))
    }

    @Test
    fun aDisplayThatAlreadyFitsIsLeftAlone() {
        assertNull(pickDisplayRefreshRate(film, 120, listOf(60, 120)))
        assertNull(pickDisplayRefreshRate(30.0, 60, listOf(30, 60)))
        assertNull(pickDisplayRefreshRate(film, 144, listOf(60, 144)))
    }

    @Test
    fun theHighestFittingRateWins() {
        assertEquals(120, pickDisplayRefreshRate(24.0, 60, listOf(24, 48, 60, 120)))
        assertEquals(50, pickDisplayRefreshRate(25.0, 60, tvRates))
    }

    @Test
    fun exactBeatsNear() {
        // 23.976 fits 119 (119.88) exactly and 120 only nearly.
        assertEquals(119, pickDisplayRefreshRate(film, 60, listOf(60, 119, 120)))
    }

    @Test
    fun nearlyFittingCurrentRateIsKeptWhenNothingExactExists() {
        assertNull(pickDisplayRefreshRate(film, 24, listOf(24, 60)))
    }

    @Test
    fun unknownOrOddFrameRatesLeaveTheDisplayAlone() {
        assertNull(pickDisplayRefreshRate(0.0, 60, tvRates))
        assertNull(pickDisplayRefreshRate(Double.NaN, 60, tvRates))
        assertNull(pickDisplayRefreshRate(1000.0, 60, tvRates))
        assertNull(pickDisplayRefreshRate(film, 60, listOf(60, 75)))
    }
}
