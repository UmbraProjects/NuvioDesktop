package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRemoteSubtitlesTest {
    @Test
    fun theSubtitleListTravelsAsOneLine() {
        val line = PhoneRemoteLibraryLines.subtitlesLine(
            RemoteSubtitleList(
                loading = true,
                choices = listOf(
                    RemoteSubtitleChoice("Off", null, "Off", selected = false),
                    RemoteSubtitleChoice("English, SDH", "eng · forced", "Built-in", selected = false),
                    RemoteSubtitleChoice("English (SubSource)", "eng", "OpenSubtitles v3", selected = true),
                ),
            ),
        )
        assertEquals(
            "SUBTITLES 1 Off,-,Off,0;English%2C%20SDH,eng%20%C2%B7%20forced,Built-in,0;" +
                "English%20%28SubSource%29,eng,OpenSubtitles%20v3,1",
            line,
        )
    }

    @Test
    fun theAudioListUsesTheSameFields() {
        val line = PhoneRemoteLibraryLines.audioLine(
            RemoteSubtitleList(false, listOf(RemoteSubtitleChoice("Track 1 (Stereo)", "eng", "", selected = true), RemoteSubtitleChoice("Commentary", null, "", selected = false))),
        )
        assertEquals("AUDIO 0 Track%201%20%28Stereo%29,eng,-,1;Commentary,-,-,0", line)
        assertEquals("AUDIO 0 -", PhoneRemoteLibraryLines.audioLine(null))
        assertEquals(RemoteMessage.AudioRequest, PhoneRemoteProtocol.parse("AUDIO"))
        assertEquals(RemoteMessage.PickAudio(2), PhoneRemoteProtocol.parse("PICK_AUDIO 2"))
        assertNull(PhoneRemoteProtocol.parse("PICK_AUDIO 0"))
    }

    @Test
    fun noFilmMeansAnEmptyList() {
        assertEquals("SUBTITLES 0 -", PhoneRemoteLibraryLines.subtitlesLine(null))
    }

    @Test
    fun theRequestsParse() {
        assertEquals(RemoteMessage.SubtitlesRequest, PhoneRemoteProtocol.parse("SUBTITLES"))
        assertEquals(RemoteMessage.PickSubtitle(3), PhoneRemoteProtocol.parse("PICK_SUBTITLE 3"))
        assertNull(PhoneRemoteProtocol.parse("PICK_SUBTITLE 0"))
        assertNull(PhoneRemoteProtocol.parse("PICK_SUBTITLE x"))
    }
}
