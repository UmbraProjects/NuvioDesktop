package com.nuvio.app.features.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// fork:phone-remote live-tv. The same line is read on the phone by LiveTvLineTest (remote-android).
class PhoneRemoteLiveTvTest {
    @Test
    fun aStageTravelsAsOneLine() {
        val line = PhoneRemoteLibraryLines.liveTvLine(
            RemoteLiveTvScreen(
                stage = "Three",
                heading = "Three things",
                sub = "Pick one; there is never a fourth.",
                stages = listOf("Three" to "Three things", "Soon" to "Starting soon"),
                items = listOf(
                    RemoteLiveTvItem("cards", 0, "SAFE", "Bake Off", "Channel 4 · on now", "https://x/p.jpg", live = true, progress = 250, action = "Watch"),
                    RemoteLiveTvItem("-", 1, "FOLLOWING", "Doctor Who", "Nothing in the guide yet", null),
                ),
                buttons = listOf(RemoteLiveTvButton("deal", 0, "Deal again")),
                empty = "Nothing to pick right now.",
            ),
        )
        assertEquals(
            "LIVETV 1 Three Three%20things Pick%20one%3B%20there%20is%20never%20a%20fourth. Three,Three%20things;Soon,Starting%20soon cards,0,SAFE,Bake%20Off,Channel%204%20%C2%B7%20on%20now,https%3A%2F%2Fx%2Fp.jpg,1,250,Watch;-,1,FOLLOWING,Doctor%20Who,Nothing%20in%20the%20guide%20yet,-,0,-1,- deal,0,Deal%20again,0 Nothing%20to%20pick%20right%20now.",
            line,
        )
        assertEquals("LIVETV 0", PhoneRemoteLibraryLines.liveTvLine(null))
    }

    @Test
    fun tapsAreReadBack() {
        assertEquals(RemoteLiveTvDo.Stage("Soon"), RemoteLiveTvDo.parse(listOf("stage", "Soon")))
        assertEquals(RemoteLiveTvDo.Item("cards", 2), RemoteLiveTvDo.parse(listOf("item", "cards", "2")))
        assertEquals(RemoteLiveTvDo.All, RemoteLiveTvDo.parse(listOf("all")))
        assertNull(RemoteLiveTvDo.parse(listOf("item", "-", "2")))
        assertNull(RemoteLiveTvDo.parse(listOf("item", "cards", "x")))
        assertNull(RemoteLiveTvDo.parse(listOf("jump")))
        assertEquals(
            RemoteMessage.LiveTvDo(RemoteLiveTvDo.Item("order", 1)),
            PhoneRemoteProtocol.parse("LIVETV_DO item order 1"),
        )
    }
}
