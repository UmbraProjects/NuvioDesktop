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
    fun readsThePcsContinueLine() {
        // The exact line PhoneRemoteContinueTest on the PC side produces.
        val line = ServerLine.parse(
            "CONTINUE series,tt0903747,tt0903747%3A2%3A5,Breaking%2C%20Bad%3B%20Show,S2%20E5%20%C2%B7%20Breakage," +
                "https%3A%2F%2Fimg%2Fp.jpg,-,1200000,2820000,Torrentio%201080p,425;" +
                "movie,tt1856101,tt1856101,Blade%20Runner%202049,-,-,-,0,0,-,300",
            0L,
        ) as ServerLine.Continue
        assertEquals(2, line.items.size)
        val show = line.items[0]
        assertEquals("Breaking, Bad; Show", show.title)
        assertEquals("tt0903747:2:5", show.videoId)
        assertEquals("Torrentio 1080p", show.lastSource)
        assertEquals("27m left", show.timeLeft)
        assertEquals(0.425f, show.progress, 0.001f)
        assertEquals(null, line.items[1].timeLeft)
        assertEquals(ServerLine.Continue(emptyList()), ServerLine.parse("CONTINUE -", 0L))
    }

    @Test
    fun readsThePcsSourcesLine() {
        // The exact line PhoneRemoteSourcesTest on the PC side produces.
        val list = (ServerLine.parse(
            "SOURCES 1 1 Severance%20%C2%B7%20S2%20E5 " +
                "Torrentio%204K,Severance.S02E05.2160p.mkv%0A%F0%9F%92%BE%2012.3%20GB%20%F0%9F%91%A4%2041,Torrentio,13200000000,1,1;" +
                "P2P%201080p,-,Comet,-,0,0",
            0L,
        ) as ServerLine.Sources).list!!
        assertEquals("Severance · S2 E5", list.title)
        assertTrue(list.loading)
        assertEquals(2, list.sources.size)
        assertEquals(2, list.sources[1].number)
        assertEquals("Severance.S02E05.2160p.mkv\n💾 12.3 GB 👤 41", list.sources[0].detail)
        assertEquals("13.2 GB", list.sources[0].sizeLabel)
        assertEquals(false, list.sources[1].playable)
        assertEquals(ServerLine.Sources(null), ServerLine.parse("SOURCES 0 0 - -", 0L))
    }

    @Test
    fun readsThePcsQueueAndLyricsLines() {
        // The lines PhoneRemoteMusicLinesTest on the PC side produces.
        val queue = ServerLine.parse(
            "QUEUE 0 abc,Billie%20Jean,Michael%20Jackson,294,https%3A%2F%2Fimg%2Fc.jpg,0;def,Beat%20It,Michael%20Jackson,258,-,1",
            0L,
        ) as ServerLine.Queue
        assertEquals(0, queue.playing)
        assertEquals("Beat It", queue.tracks[1].title)
        assertTrue(queue.tracks[1].upcoming)
        val lyrics = ServerLine.parse(
            "LYRICS synced Billie%20Jean 1200,She%20was%20more%20like%20a%20beauty%20queen,-;5400,-,-;" +
                "9000,Billie%20Jean,Billie%3A9000%3A9400%7CJean%3A9400%3A10100",
            0L,
        ) as ServerLine.LyricsText
        assertEquals("Billie Jean", lyrics.title)
        assertEquals(LyricLine(1200L, "She was more like a beauty queen"), lyrics.lines[0])
        assertEquals(LyricLine(5400L, ""), lyrics.lines[1])
        assertEquals(listOf(WordTime("Billie", 9000L, 9400L), WordTime("Jean", 9400L, 10100L)), lyrics.lines[2].words)
        assertEquals(0, (ServerLine.parse("LYRICS none - -", 0L) as ServerLine.LyricsText).lines.size)
    }

    @Test
    fun readsThePcsEpisodesLine() {
        // The line PhoneRemoteEpisodesTest on the PC side produces.
        val line = ServerLine.parse(
            "EPISODES series tt0903747 1 1,1,Pilot,tt0903747%3A1%3A1,https%3A%2F%2Fimg%2Fe.jpg,2008-01-20,1000;2,-,Untitled,x,-,-,250",
            0L,
        ) as ServerLine.Episodes
        assertEquals("tt0903747", line.id)
        assertTrue(line.found)
        assertEquals(EpisodeInfo(1, 1, "Pilot", "tt0903747:1:1", "https://img/e.jpg", "2008-01-20", 1f), line.episodes[0])
        assertEquals(null, line.episodes[1].episode)
        assertEquals(0.25f, line.episodes[1].progress, 0.001f)
    }

    @Test
    fun readsThePcsSearchLine() {
        // The exact line PhoneRemoteSearchTest on the PC side produces.
        val line = ServerLine.parse(
            "SEARCH_RESULTS 0 blade%20runner Movies%20%C2%B7%20Cinemeta,movie,tt1856101,Blade%20Runner%202049,2017,https%3A%2F%2Fimg%2Fp.jpg,-,1",
            0L,
        ) as ServerLine.SearchResults
        assertEquals("blade runner", line.query)
        assertEquals(false, line.loading)
        assertEquals("Movies · Cinemeta", line.titles.single().section)
        assertTrue(line.titles.single().watched)
        assertEquals(0, (ServerLine.parse("SEARCH_RESULTS 1 x -", 0L) as ServerLine.SearchResults).titles.size)
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

        // Opening a title says so and shows its sources, which fill in; picking one plays it as a film.
        session.openTitle("movie", "demo-m2")
        assertEquals("Glass Orchard", lines.next<ServerLine.Opening>().what)
        val filling = lines.next<ServerLine.Sources>().list!!
        assertTrue(filling.loading)
        assertEquals("Glass Orchard", filling.title)
        val full = lines.next<ServerLine.Sources>().list!!
        assertEquals(4, full.sources.size)
        assertEquals(false, full.sources[3].playable)
        assertEquals("18.4 GB", full.sources[0].sizeLabel)
        session.pickSource(2)
        assertEquals("Demo 1080p", lines.next<ServerLine.Opening>().what)
        assertEquals(null, lines.next<ServerLine.Sources>().list)
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

        // The queue: five songs, the second playing; removing one and playing another.
        session.requestQueue()
        val queue = lines.next<ServerLine.Queue>()
        assertEquals(1, queue.playing)
        assertEquals(5, queue.tracks.size)
        assertTrue(queue.tracks[3].upcoming)
        session.queueAction("remove", queue.tracks[2].videoId)
        assertEquals(4, lines.next<ServerLine.Queue>().tracks.size)
        session.requestLyrics()
        val lyrics = lines.next<ServerLine.LyricsText>()
        assertTrue(lyrics.synced)
        assertEquals("Neon Weather", lyrics.title)
        assertEquals(2_000L, lyrics.lines.first().timeMs)

        // Continue Watching lists, and Resume plays from where it stopped.
        session.requestContinue()
        val resumes = lines.next<ServerLine.Continue>().items
        assertEquals(3, resumes.size)
        assertEquals("S2 E5 · The Breakwater", resumes.first().episode)
        assertEquals("26m left", resumes.first().timeLeft)
        session.resume(resumes[1].parentId, resumes[1].videoId)
        assertEquals("Northbound Night Train", lines.next<ServerLine.Opening>().what)
        val resumed = lines.next<ServerLine.State>().nowPlaying
        assertEquals("Northbound Night Train", resumed.title)
        assertTrue(resumed.positionMs >= 72 * 60_000L)

        // Search: loading first, then the matches and the catalogs' extras.
        session.search("signal")
        val searching = lines.next<ServerLine.SearchResults>()
        assertTrue(searching.loading)
        val answered = lines.next<ServerLine.SearchResults>()
        assertEquals("signal", answered.query)
        assertEquals("The Long Signal", answered.titles.first().name)
        assertEquals(3, answered.titles.size)

        // An album's songs, and playing one from the middle.
        session.albumTracks(music.albums[2].browseId)
        val songs = lines.next<ServerLine.AlbumTracks>()
        assertEquals(4, songs.tracks.size)
        assertEquals("Driftwood", songs.tracks[1].title)
        session.playTrack(music.albums[2].browseId, 2)
        assertEquals("Copper Sky", lines.next<ServerLine.Opening>().what)
        var playingNow = lines.next<ServerLine.State>().nowPlaying
        while (playingNow.title != "Copper Sky") playingNow = lines.next<ServerLine.State>().nowPlaying

        // A show's season list, and an episode from it opening its sources.
        session.episodes("series", "demo-s1")
        val show = lines.next<ServerLine.Episodes>()
        assertTrue(show.found)
        assertEquals(12, show.episodes.size)
        assertEquals(1f, show.episodes[0].progress)
        session.playEpisode("series", "demo-s1", show.episodes[7].videoId)
        assertEquals("Harbour Lights S2 E2", lines.next<ServerLine.Opening>().what)
        assertEquals("Harbour Lights", lines.next<ServerLine.Sources>().list!!.title)
        session.key(RemoteKey.Back)

        // Back closes the player; a theme change reaches the phone.
        session.key(RemoteKey.Back)
        lines.next<ServerLine.Idle>()
        DemoTv.nextTheme()
        assertEquals(0xFFE53935.toInt(), lines.next<ServerLine.Theme>().theme.accent)
        assertTrue(DemoTv.log.value.isNotEmpty())
        session.close()
    }

    @Test
    fun sleepsAndWakesFromTheWakeUpPacket() {
        val (session, lines) = connect()
        val wake = lines.next<ServerLine.Wake>()
        DemoTv.sleep()
        Thread.sleep(200)
        val asleep = RemoteSession.open(DemoTv.HOST, DemoTv.PORT)
        assertTrue(asleep.hello(null, "Test phone") is HelloResult.Refused)
        asleep.close()
        assertTrue(WakeOnLan.send(wake.macs, wake.broadcasts) > 0)
        Thread.sleep(4_600)
        val awake = RemoteSession.open(DemoTv.HOST, DemoTv.PORT)
        assertEquals(HelloResult.PinRequired, awake.hello(null, "Test phone"))
        awake.close()
        session.close()
    }

    @Test
    fun aLongPressMarksAnEpisodeAndTheListFollows() {
        val (session, lines) = connect()
        val show = LibraryTitle("Favourites", "series", "demo-s1", "Harbour Lights", "2019", null, null, false)
        val episode = EpisodeInfo(2, 1, "Chapter 21", "demo-s1:2:1", null, null, 0f)
        session.menu(MenuTarget.episode(show, episode))
        val menu = lines.next<ServerLine.Menu>()
        assertEquals("demo-s1:2:1", menu.target.ref)
        val watched = menu.actions.first { it.code == MenuCode.WATCHED }
        assertEquals("Mark as watched", watched.label)

        session.act(menu.target, watched.code)
        val done = lines.next<ServerLine.Done>()
        assertTrue(done.ok)
        assertTrue(LibraryLines.REFRESH_EPISODES in done.refresh)
        session.episodes(show.type, show.id)
        val listed = lines.next<ServerLine.Episodes>().episodes.first { it.videoId == "demo-s1:2:1" }
        assertEquals(1f, listed.progress, 0.001f)

        // And back, so the demo is as it was for the other tests.
        session.act(menu.target, MenuCode.WATCHED)
        assertTrue(lines.next<ServerLine.Done>().ok)
        session.close()
    }

    @Test
    fun aPlaylistPickerThenAnAdd() {
        val (session, lines) = connect()
        val target = MenuTarget("title", "series", "demo-s2", null)
        session.act(target, MenuCode.PLAYLIST)
        val pick = lines.next<ServerLine.Playlists>()
        assertEquals(listOf(1, 2), pick.seasons)
        assertTrue(pick.playlists.isNotEmpty())
        session.act(target, MenuCode.ADD_TO_PLAYLIST, listOf(pick.playlists.first().id, pick.scope, "2"))
        assertTrue(lines.next<ServerLine.Done>().message.startsWith("Added to"))
        session.close()
    }
}
