package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteProtocolTest {
    @Test
    fun readsADiscoveryReply() {
        assertEquals(
            FoundPc(name = "Living Room PC", host = "192.168.1.20", port = 47321),
            Discovery.parseReply("NUVIO_REMOTE 1 47321 Living%20Room%20PC", "192.168.1.20"),
        )
    }

    @Test
    fun ignoresAnythingElse() {
        assertNull(Discovery.parseReply("HELLO there", "192.168.1.20"))
        assertNull(Discovery.parseReply("NUVIO_REMOTE 1 notaport Name", "192.168.1.20"))
    }

    @Test
    fun textSurvivesTheRoundTrip() {
        val text = "Amélie & the 3+1 crew\n"
        assertEquals(text, RemoteProtocol.decode(RemoteProtocol.encode(text)))
        assertEquals(false, RemoteProtocol.encode(text).contains(' '))
    }

    @Test
    fun readsTheStateLinesThePcSends() {
        // The same line the desktop's PhoneRemoteLinksTest expects PhoneRemoteProtocol.stateLine to write.
        val line = "STATE film 1 5000 7200000 80 150 0 Blade%20Runner%202049 S01E02%20%C2%B7%20Pilot https%3A%2F%2Fimg%2Fp.jpg"
        val state = (ServerLine.parse(line, nowMs = 1_000L) as ServerLine.State).nowPlaying
        assertEquals(
            NowPlaying(
                kind = "film", isPlaying = true, positionMs = 5_000L, durationMs = 7_200_000L, volume = 80, volumeMax = 150,
                canSkip = false, title = "Blade Runner 2049", subtitle = "S01E02 · Pilot", imageUrl = "https://img/p.jpg",
                receivedAtMs = 1_000L,
            ),
            state,
        )
        val music = (ServerLine.parse("STATE music 0 1000 0 - 100 1 Billie%20Jean - -", 0L) as ServerLine.State).nowPlaying
        assertNull(music.volume)
        assertNull(music.subtitle)
        assertNull(music.imageUrl)
        assertEquals(true, music.canSkip)
        assertEquals(ServerLine.Idle, ServerLine.parse("IDLE", 0L))
        assertEquals(ServerLine.Pong, ServerLine.parse("PONG", 0L))
        assertEquals(ServerLine.Opening("a YouTube video"), ServerLine.parse("OPENING a%20YouTube%20video", 0L))
        assertEquals(ServerLine.Other, ServerLine.parse("STATE film 1", 0L))
    }

    @Test
    fun thePositionMovesOnWhilePlayingAndStopsAtTheEnd() {
        val playing = NowPlaying("music", true, 10_000L, 12_000L, 50, 100, true, "Song", null, null, receivedAtMs = 0L)
        assertEquals(11_500L, playing.positionAt(1_500L))
        assertEquals(12_000L, playing.positionAt(60_000L))
        assertEquals(10_000L, playing.copy(isPlaying = false).positionAt(60_000L))
    }
}
