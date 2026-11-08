package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteThemeTest {
    @Test
    fun readsTheThemeLine() {
        val line = ServerLine.parse("THEME 1E88E5 - 0D0D0F", 0L) as ServerLine.Theme
        assertEquals(RemoteTheme(0xFF1E88E5.toInt(), null, 0xFF0D0D0F.toInt()), line.theme)
        val custom = ServerLine.parse("THEME FFD700 FF8800 0B0F10", 0L) as ServerLine.Theme
        assertEquals(0xFFFF8800.toInt(), custom.theme.accentEnd)
    }

    @Test
    fun keepsAndRestoresTheTheme() {
        val theme = RemoteTheme(0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF0D0D0D.toInt())
        assertEquals(theme, RemoteTheme.decode(theme.encode()))
        assertNull(RemoteTheme.decode("nonsense"))
        assertEquals(ServerLine.Other, ServerLine.parse("THEME 1E88 - 0D0D0F", 0L))
    }

    @Test
    fun readsTrackNamesWhenThePcSendsThem() {
        val withTracks = ServerLine.parse(
            "STATE film 1 5000 7200000 80 150 0 Blade%20Runner - - English%20SDH English%205.1",
            0L,
        ) as ServerLine.State
        assertEquals("English SDH", withTracks.nowPlaying.subtitleTrack)
        assertEquals("English 5.1", withTracks.nowPlaying.audioTrack)
        val older = ServerLine.parse("STATE music 0 1000 0 - 100 0 Billie%20Jean - -", 0L) as ServerLine.State
        assertNull(older.nowPlaying.subtitleTrack)
    }
}
