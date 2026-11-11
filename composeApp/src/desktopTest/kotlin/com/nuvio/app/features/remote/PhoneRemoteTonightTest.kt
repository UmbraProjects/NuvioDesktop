package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// fork:phone-remote tonight. The same lines are checked on the phone by TonightLineTest (remote-android).
class PhoneRemoteTonightTest {
    @Test
    fun aQuestionTravelsAsOneLine() {
        val line = PhoneRemoteLibraryLines.tonightLine(
            RemoteTonightScreen(
                view = RemoteTonightScreen.QUESTION,
                number = 2,
                total = 3,
                countFrom = 214,
                countLeft = 41,
                prompt = "Laugh, or think?",
                leftLabel = "Laugh",
                leftDetail = "Light and easy",
                rightLabel = "Think",
                rightDetail = "Something with weight",
                answers = listOf("A film"),
            ),
        )
        assertEquals(
            "TONIGHT 1 question - 0 2 3 214 41 0 Laugh%2C%20or%20think%3F Laugh Light%20and%20easy Think Something%20with%20weight A%20film -",
            line,
        )
    }

    @Test
    fun theCardsTravelAsOneLine() {
        val line = PhoneRemoteLibraryLines.tonightLine(
            RemoteTonightScreen(
                view = RemoteTonightScreen.CARDS,
                reshuffleLeft = true,
                answers = listOf("A film", "Laugh"),
                cards = listOf(RemoteTonightCard("SAFE", "Heat", "Seen before", "1995 · 2h 50m", "https://img/h.jpg")),
            ),
        )
        assertEquals(
            "TONIGHT 1 cards - 0 0 0 0 0 1 - - - - - A%20film;Laugh SAFE,Heat,Seen%20before,1995%20%C2%B7%202h%2050m,https%3A%2F%2Fimg%2Fh.jpg",
            line,
        )
        assertEquals("TONIGHT 0", PhoneRemoteLibraryLines.tonightLine(null))
    }

    @Test
    fun thePhonesTapsAreRead() {
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.Play, 2), PhoneRemoteProtocol.parse("TONIGHT_DO play 2"))
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.Left, 0), PhoneRemoteProtocol.parse("TONIGHT_DO left"))
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.OpenQuestions, 0), PhoneRemoteProtocol.parse("TONIGHT_DO questions"))
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.Open, 0), PhoneRemoteProtocol.parse("TONIGHT_DO open"))
        assertNull(PhoneRemoteProtocol.parse("TONIGHT_DO nonsense"))
    }

    @Test
    fun aTapOnlyRunsWhileTonightIsOnScreen() {
        val owner = Any()
        var got: Pair<RemoteTonightAction, Int>? = null
        assertEquals(false, RemoteTonight.act(RemoteTonightAction.Left, 0))
        RemoteTonight.publish(owner, RemoteTonightScreen(RemoteTonightScreen.LAUNCHER)) { a, n -> got = a to n }
        assertEquals(true, RemoteTonight.act(RemoteTonightAction.Play, 3))
        assertEquals(RemoteTonightAction.Play to 3, got)
        RemoteTonight.clear(owner)
        assertNull(RemoteTonight.current.value)
        assertEquals(false, RemoteTonight.act(RemoteTonightAction.Left, 0))
    }
}
