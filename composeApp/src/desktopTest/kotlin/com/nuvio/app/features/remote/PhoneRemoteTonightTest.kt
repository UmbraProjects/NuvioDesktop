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
            "TONIGHT 1 question - 0 2 3 214 41 0 Laugh%2C%20or%20think%3F Laugh Light%20and%20easy Think Something%20with%20weight A%20film - - -1 0 0 0",
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
            "TONIGHT 1 cards - 0 0 0 0 0 1 - - - - - A%20film;Laugh SAFE,Heat,Seen%20before,1995%20%C2%B7%202h%2050m,https%3A%2F%2Fimg%2Fh.jpg,0 - -1 0 0 0",
            line,
        )
        assertEquals("TONIGHT 0", PhoneRemoteLibraryLines.tonightLine(null))
    }

    @Test
    fun aModeTravelsWithItsCountdownAndRow() {
        val line = PhoneRemoteLibraryLines.tonightLine(
            RemoteTonightScreen(
                view = RemoteTonightScreen.ENDS_BY,
                cards = listOf(RemoteTonightCard("22:41", "Heat", "Ends 22:41", "1995", null, flag = true)),
                extra = "Bed at 23:30",
                seconds = 7,
                secondsTotal = 10,
                paused = true,
                selected = 1,
            ),
        )
        assertEquals(
            "TONIGHT 1 endsby - 0 0 0 0 0 0 - - - - - - 22%3A41,Heat,Ends%2022%3A41,1995,-,1 Bed%20at%2023%3A30 7 10 1 1",
            line,
        )
    }

    @Test
    fun thePhonesTapsAreRead() {
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.Mode, 3), PhoneRemoteProtocol.parse("TONIGHT_DO mode 3"))
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.LetGo, 2), PhoneRemoteProtocol.parse("TONIGHT_DO letgo 2"))
        assertEquals(RemoteMessage.TonightDo(RemoteTonightAction.Up, 0), PhoneRemoteProtocol.parse("TONIGHT_DO up"))
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
