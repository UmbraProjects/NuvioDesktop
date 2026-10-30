package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicAudioKeepAliveTest {
    private val track = MusicTrack("id1", "Track 1", emptyList(), 200, 1, true)
    private val playing = MusicPlayerState(track = track, playWhenReady = true)

    @Test
    fun holdsWhileATrackIsActiveAndWanted() {
        assertTrue(musicKeepAliveWanted(enabled = true, state = playing, hidden = false))
    }

    @Test
    fun staysHeldWhileTheNextTrackLoads() {
        assertTrue(musicKeepAliveWanted(enabled = true, state = playing.copy(isLoading = true), hidden = false))
    }

    @Test
    fun offWhenTheSettingIsOff() {
        assertFalse(musicKeepAliveWanted(enabled = false, state = playing, hidden = false))
    }

    @Test
    fun offWhenNothingIsLoaded() {
        assertFalse(musicKeepAliveWanted(enabled = true, state = MusicPlayerState(), hidden = false))
    }

    @Test
    fun offWhenPausedOrWhenAFilmHidesTheMusicPlayer() {
        assertFalse(musicKeepAliveWanted(enabled = true, state = playing.copy(playWhenReady = false), hidden = false))
        assertFalse(musicKeepAliveWanted(enabled = true, state = playing, hidden = true))
    }
}
