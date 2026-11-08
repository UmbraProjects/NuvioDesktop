package com.nuvio.app.features.music
// FORK UI (Spyder): tests for the Now Playing stage title. See CLAUDE.md.

import kotlin.test.Test
import kotlin.test.assertEquals

class StageSongTitleTest {
    @Test
    fun dropsBracketedGuests() {
        assertEquals("The Watcher", stageSongTitle("The Watcher (feat. Eminem & Knoc 'Turn 'Al)"))
        assertEquals("Lolo (Intro)", stageSongTitle("Lolo (Intro) (feat. Xzibit & Tray Dee)"))
        assertEquals("Song", stageSongTitle("Song [ft. Someone]"))
        assertEquals("Song", stageSongTitle("Song (Featuring Someone)"))
        assertEquals("Song", stageSongTitle("Song (with Someone)"))
    }

    @Test
    fun dropsTrailingGuests() {
        assertEquals("Song", stageSongTitle("Song feat. Someone"))
        assertEquals("Song", stageSongTitle("Song ft Someone"))
    }

    @Test
    fun keepsVersionNotesAndRealTitles() {
        assertEquals("Song (Remix)", stageSongTitle("Song (Remix) (feat. Someone)"))
        assertEquals("Dancing with Myself", stageSongTitle("Dancing with Myself"))
        assertEquals("Still D.R.E.", stageSongTitle("Still D.R.E."))
    }
}
