package com.nuvio.app.features.remote

import com.nuvio.app.features.livescores.LiveFeed
import com.nuvio.app.features.livescores.LiveMatch
import com.nuvio.app.features.livescores.LiveSport
import com.nuvio.app.features.livescores.LiveTeam
import com.nuvio.app.features.livescores.MatchPhase
import com.nuvio.app.features.livescores.ScoreEvent
import com.nuvio.app.features.livescores.ScoreEventKind
import com.nuvio.app.features.livescores.phoneScoreLine
import kotlin.test.Test
import kotlin.test.assertEquals

/** fork:live-scores - the SCORE line the phone's ScoreLineTest reads. */
class PhoneRemoteScoreTest {
    @Test
    fun aFavouritesGoalGoesToThePhoneAsOneLine() {
        val match = LiveMatch(
            LiveFeed.Espn, "1", LiveSport.Football, "eng.1", "Premier League",
            LiveTeam(LiveSport.Football, "360", "Manchester United", "Man United"),
            LiveTeam(LiveSport.Football, "364", "Liverpool", "Liverpool"),
            2, 1, MatchPhase.Live, "STATUS_SECOND_HALF", "67'", null,
        )
        val event = ScoreEvent(ScoreEventKind.Goal, match, true, "Bruno Fernandes · pen", "67'", true, "1|g")
        assertEquals(
            "SCORE Football Goal GOAL%20%C2%B7%20Man%20United%202%20%E2%80%93%201%20Liverpool " +
                "Bruno%20Fernandes%20%C2%B7%20pen%20%C2%B7%2067%27%20%C2%B7%20Premier%20League",
            phoneScoreLine(event),
        )
    }
}
