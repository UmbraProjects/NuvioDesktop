package com.nuvio.app.features.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceWakeWordTest {

    @Test
    fun theCommandAfterTheName() {
        assertEquals("pause", commandAfterWakeWord("Nuvio, pause."))
        assertEquals("search movies for Midsommar", commandAfterWakeWord("Nuvio, search movies for Midsommar."))
        assertEquals("open settings", commandAfterWakeWord("Hey Nuvio, open settings."))
        assertEquals("playback speed two", commandAfterWakeWord("okay nuvio playback speed two"))
        // Spellings whisper uses when the prompt does not win.
        assertEquals("play", commandAfterWakeWord("Novio, play."))
        assertEquals("play", commandAfterWakeWord("Nuvi play"))
        // Split in two, as whisper wrote it for one of Windows' voices.
        assertEquals("Pause", commandAfterWakeWord("No ovio. Pause."))
        assertEquals("search movies for Midsommar", commandAfterWakeWord("Hey, no ovio, search movies for Midsommar."))
        assertEquals("", commandAfterWakeWord("No ovio."))
    }

    @Test
    fun theNameMidClip() {
        // Music or a film under the voice: no pause to end the utterance, so the name is not first.
        assertEquals("pause", commandAfterWakeWord("Billie Jean is not my lover. Nuvio, pause."))
        assertEquals("go down 3", commandAfterWakeWord("she's just a girl who claims Nuvio go down 3"))
        // Said twice (the first went unanswered): the command after the last.
        assertEquals("select", commandAfterWakeWord("Nuvio. Nuvio, select."))
        // The name last: listen for the command.
        assertEquals("", commandAfterWakeWord("I think we should pause, Nuvio."))
    }

    @Test
    fun theNameAloneStartsListening() {
        assertEquals("", commandAfterWakeWord("Nuvio."))
        assertEquals("", commandAfterWakeWord("Hey Nuvio!"))
    }

    @Test
    fun speechNotAddressedToNuvioIsIgnored() {
        assertNull(commandAfterWakeWord("Pause."))
        assertNull(commandAfterWakeWord("Nova, search for her."))
        assertNull(commandAfterWakeWord("Now pause it."))
        assertNull(commandAfterWakeWord("No video tonight."))
        assertNull(commandAfterWakeWord("New movies are out."))
        assertNull(commandAfterWakeWord("No, pause it."))
        assertNull(commandAfterWakeWord("Hey"))
        assertNull(commandAfterWakeWord(""))
    }
}
