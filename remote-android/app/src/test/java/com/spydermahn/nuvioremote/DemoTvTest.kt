package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The demo TV, driven through the app's own client, over a real socket on the loopback address. */
class DemoTvTest {
    private fun connect(): Pair<RemoteSession, LinkedBlockingQueue<ServerLine>> {
        DemoTv.startAndWait()
        val first = RemoteSession.open(DemoTv.HOST, DemoTv.PORT)
        assertEquals(HelloResult.PinRequired, first.hello(null, "Test phone"))
        assertEquals(PairResult.WrongPin, first.pair("000000"))
        val paired = first.pair(DemoTv.PIN) as PairResult.Paired
        first.close()

        val session = RemoteSession.open(DemoTv.HOST, DemoTv.PORT)
        assertEquals(HelloResult.Welcome(DemoTv.NAME), session.hello(paired.token, "Test phone"))
        val lines = LinkedBlockingQueue<ServerLine>()
        session.listen(clock = { System.nanoTime() / 1_000_000L }, onLine = { lines.add(it) }, onClosed = {})
        session.subscribe()
        return session to lines
    }

    private inline fun <reified T : ServerLine> LinkedBlockingQueue<ServerLine>.next(): T {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val line = poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (line is T) return line
        }
        throw AssertionError("No ${T::class.simpleName} line")
    }

    @Test
    fun pairsPlaysAndAnswersLikeTheTv() {
        val (session, lines) = connect()
        DemoTv.stop()
        lines.clear()

        // The library parses with the app's own reader.
        session.requestLibrary(LibraryLines.VIDEO)
        val video = lines.next<ServerLine.VideoLibrary>()
        assertTrue(video.ready)
        assertEquals(8, video.titles.size)
        session.requestLibrary(LibraryLines.MUSIC)
        val music = lines.next<ServerLine.MusicLibrary>()
        assertEquals(2, music.artists.size)
        assertEquals(4, music.albums.size)

        // Opening a title says so, then plays it as a film.
        session.openTitle("movie", "demo-m2")
        assertEquals("Glass Orchard", lines.next<ServerLine.Opening>().what)
        val film = lines.next<ServerLine.State>().nowPlaying
        assertEquals("film", film.kind)
        assertEquals("Glass Orchard", film.title)
        assertTrue(film.isPlaying)

        // Play/pause, seeking and the subtitle track come back in the next STATE.
        session.key(RemoteKey.PlayPause)
        assertEquals(false, lines.next<ServerLine.State>().nowPlaying.isPlaying)
        session.seek(60_000L)
        assertEquals(60_000L, lines.next<ServerLine.State>().nowPlaying.positionMs)
        session.key(RemoteKey.Subtitles)
        assertEquals("English SDH", lines.next<ServerLine.State>().nowPlaying.subtitleTrack)

        // An album plays as music, with next and previous.
        session.album(music.albums.first().browseId, AlbumAction.Play)
        val song = lines.next<ServerLine.State>().nowPlaying
        assertEquals("music", song.kind)
        assertTrue(song.canSkip)
        session.key(RemoteKey.Next)
        assertEquals("Neon Weather", lines.next<ServerLine.State>().nowPlaying.title)

        // Back closes the player; a theme change reaches the phone.
        session.key(RemoteKey.Back)
        lines.next<ServerLine.Idle>()
        DemoTv.nextTheme()
        assertEquals(0xFFE53935.toInt(), lines.next<ServerLine.Theme>().theme.accent)
        assertTrue(DemoTv.log.value.isNotEmpty())
        session.close()
    }
}
