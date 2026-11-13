package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-press sheet's lines, as the PC's PhoneRemoteMenuTest writes them. */
class MenuLinesTest {
    @Test
    fun aTargetGoesOnTheWireAsThePcReadsIt() {
        assertEquals("title movie tt0862856 -", MenuTarget("title", "movie", "tt0862856", null).wire())
        assertEquals(
            "episode series tt0387764 tt0387764%3A1%3A5",
            MenuTarget.episode(LibraryTitle("Shows", "series", "tt0387764", "Peep Show", null, null, null, false), EPISODE).wire(),
        )
        assertEquals("season series tt0387764 2", MenuTarget("season", "series", "tt0387764", "2").wire())
    }

    @Test
    fun readsTheSheet() {
        val line = "MENU episode series tt1 tt1%3A1%3A5 S1%20E5%20%C2%B7%20Dream%20Job Peep%20Show " +
            "watched,Mark%20as%20watched;playlist,Add%20episode%20to%20a%20playlist"
        val menu = ServerLine.parse(line, 0L) as ServerLine.Menu
        assertEquals(MenuTarget("episode", "series", "tt1", "tt1:1:5"), menu.target)
        assertEquals("S1 E5 · Dream Job", menu.heading)
        assertEquals("Peep Show", menu.detail)
        assertEquals(
            listOf(MenuAction("watched", "Mark as watched"), MenuAction("playlist", "Add episode to a playlist")),
            menu.actions,
        )
    }

    @Test
    fun anEmptySheetMeansThePcCouldNotFindIt() {
        val menu = ServerLine.parse("MENU title movie tt2 - Couldn%27t%20load%20this - -", 0L) as ServerLine.Menu
        assertEquals(MenuTarget("title", "movie", "tt2", null), menu.target)
        assertTrue(menu.actions.isEmpty())
        assertNull(menu.detail)
    }

    @Test
    fun readsDoneAndWhatToReload() {
        val done = ServerLine.parse("DONE 1 Peep%20Show%20marked%20as%20watched library,continue", 0L) as ServerLine.Done
        assertTrue(done.ok)
        assertEquals("Peep Show marked as watched", done.message)
        assertEquals(setOf("library", "continue"), done.refresh)
        val failed = ServerLine.parse("DONE 0 Nope -", 0L) as ServerLine.Done
        assertEquals(false, failed.ok)
        assertTrue(failed.refresh.isEmpty())
    }

    @Test
    fun readsThePlaylistPicker() {
        val pick = ServerLine.parse("PLAYLISTS title series tt1 - title Peep%20Show 1;2 p1,Sunday%2C%20lazy,4", 0L) as ServerLine.Playlists
        assertEquals(MenuTarget("title", "series", "tt1", null), pick.target)
        assertEquals("title", pick.scope)
        assertEquals("Peep Show", pick.label)
        assertEquals(listOf(1, 2), pick.seasons)
        assertEquals(listOf(PlaylistOption("p1", "Sunday, lazy", 4)), pick.playlists)
        val film = ServerLine.parse("PLAYLISTS title movie tt2 - title Heat - -", 0L) as ServerLine.Playlists
        assertTrue(film.seasons.isEmpty() && film.playlists.isEmpty())
    }

    @Test
    fun readsARecap() {
        val recap = ServerLine.parse("RECAP 1 Peep%20Show Line%20one.%0ALine%20two.", 0L) as ServerLine.Recap
        assertEquals(1, recap.state)
        assertEquals("Line one.\nLine two.", recap.text)
        val writing = ServerLine.parse("RECAP 0 Peep%20Show -", 0L) as ServerLine.Recap
        assertEquals(0, writing.state)
        assertNull(writing.text)
    }

    @Test
    fun brokenLinesAreIgnored() {
        assertEquals(ServerLine.Other, ServerLine.parse("MENU shelf movie tt1 - x - -", 0L))
        assertEquals(ServerLine.Other, ServerLine.parse("PLAYLISTS", 0L))
        assertEquals(ServerLine.Other, ServerLine.parse("DONE", 0L))
    }

    private companion object {
        val EPISODE = EpisodeInfo(1, 5, "Dream Job", "tt0387764:1:5", null, null, 0f)
    }
}
