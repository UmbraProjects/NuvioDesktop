package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fork:phone-remote menus: the long-press sheet's lines (MENU, DO, DONE, PLAYLISTS, RECAP). */
class PhoneRemoteMenuTest {
    @Test
    fun aLongPressParses() {
        assertEquals(
            RemoteMessage.Menu(RemoteMenuTarget(RemoteMenuKind.Title, "movie", "tt0862856", null)),
            PhoneRemoteProtocol.parse("MENU title movie tt0862856 -"),
        )
        assertEquals(
            RemoteMessage.Menu(RemoteMenuTarget(RemoteMenuKind.Episode, "series", "tt0387764", "tt0387764:1:5")),
            PhoneRemoteProtocol.parse("MENU episode series tt0387764 tt0387764%3A1%3A5"),
        )
        assertEquals(
            RemoteMessage.Menu(RemoteMenuTarget(RemoteMenuKind.Season, "series", "tt0387764", "2")),
            PhoneRemoteProtocol.parse("MENU season series tt0387764 2"),
        )
    }

    @Test
    fun aBrokenLongPressIsIgnored() {
        assertNull(PhoneRemoteProtocol.parse("MENU"))
        assertNull(PhoneRemoteProtocol.parse("MENU shelf movie tt1 -"))
        assertNull(PhoneRemoteProtocol.parse("MENU title - tt1 -"))
        // Everything but a poster needs to say which card, episode or season.
        assertNull(PhoneRemoteProtocol.parse("MENU episode series tt1 -"))
    }

    @Test
    fun aChosenRowParsesWithItsArguments() {
        val target = RemoteMenuTarget(RemoteMenuKind.Title, "series", "tt1", null)
        assertEquals(RemoteMessage.Do(target, "watched", emptyList()), PhoneRemoteProtocol.parse("DO title series tt1 - watched"))
        assertEquals(
            RemoteMessage.Do(target, "add_to_playlist", listOf("+", "title", "2")),
            PhoneRemoteProtocol.parse("DO title series tt1 - add_to_playlist %2B title 2"),
        )
        assertNull(PhoneRemoteProtocol.parse("DO title series tt1 - WATCHED"))
        assertNull(PhoneRemoteProtocol.parse("DO title series tt1 -"))
    }

    @Test
    fun theSheetCarriesItsRows() {
        val line = PhoneRemoteLibraryLines.menuLine(
            RemoteMenuTarget(RemoteMenuKind.Episode, "series", "tt1", "tt1:1:5"),
            "S1 E5 · Dream Job",
            "Peep Show",
            listOf(
                RemoteMenuAction(RemoteMenuCode.WATCHED, "Mark as watched"),
                RemoteMenuAction(RemoteMenuCode.PLAYLIST, "Add episode to a playlist"),
            ),
        )
        assertEquals(
            "MENU episode series tt1 tt1%3A1%3A5 S1%20E5%20%C2%B7%20Dream%20Job Peep%20Show " +
                "watched,Mark%20as%20watched;playlist,Add%20episode%20to%20a%20playlist",
            line,
        )
        // A poster's ref is a dash, and an empty sheet still has its eight fields.
        val empty = PhoneRemoteLibraryLines.menuLine(RemoteMenuTarget(RemoteMenuKind.Title, "movie", "tt2", null), "Film", null, emptyList())
        assertEquals("MENU title movie tt2 - Film - -", empty)
    }

    @Test
    fun doneSaysWhatToReload() {
        assertEquals(
            "DONE 1 Peep%20Show%20marked%20as%20watched library,continue",
            PhoneRemoteLibraryLines.doneLine(true, "Peep Show marked as watched", listOf("library", "continue")),
        )
        assertEquals("DONE 0 Nope -", PhoneRemoteLibraryLines.doneLine(false, "Nope"))
    }

    @Test
    fun thePlaylistPickerListsSeasonsAndPlaylists() {
        val line = PhoneRemoteLibraryLines.playlistsLine(
            RemoteMenuTarget(RemoteMenuKind.Title, "series", "tt1", null),
            RemoteMenuCode.PLAYLIST_SCOPE_TITLE,
            "Peep Show",
            listOf(1, 2),
            listOf(RemotePlaylistChoice("p1", "Sunday, lazy", 4)),
        )
        assertEquals("PLAYLISTS title series tt1 - title Peep%20Show 1;2 p1,Sunday%2C%20lazy,4", line)
        val film = PhoneRemoteLibraryLines.playlistsLine(
            RemoteMenuTarget(RemoteMenuKind.Title, "movie", "tt2", null), RemoteMenuCode.PLAYLIST_SCOPE_TITLE, "Heat", emptyList(), emptyList(),
        )
        assertEquals("PLAYLISTS title movie tt2 - title Heat - -", film)
    }

    @Test
    fun aRecapIsOneLineHoweverLong() {
        val text = "Line one.\nLine two, with a comma; and more.\n".repeat(400)
        val line = PhoneRemoteLibraryLines.recapLine(1, "Peep Show · Before S2 E1", text)
        assertTrue('\n' !in line)
        val fields = line.split(' ')
        assertEquals(4, fields.size)
        assertEquals("1", fields[1])
        assertTrue(PhoneRemoteProtocol.decode(fields[3]).length <= 6000)
        assertEquals("RECAP 0 Writing -", PhoneRemoteLibraryLines.recapLine(0, "Writing", null))
    }
}
