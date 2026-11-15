package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fork:live-scores - the SCORE line as the PC's PhoneRemoteScoreTest writes it. */
class ScoreLineTest {
    @Test
    fun aScoreLineBecomesANotification() {
        val line = "SCORE Football Goal GOAL%20%C2%B7%20Man%20United%202%20%E2%80%93%201%20Liverpool " +
            "Bruno%20Fernandes%20%C2%B7%20pen%20%C2%B7%2067'%20%C2%B7%20Premier%20League"
        val score = ServerLine.parse(line, 0L) as ServerLine.Score
        assertEquals("Football", score.sport)
        assertEquals("Goal", score.kind)
        assertEquals("GOAL · Man United 2 – 1 Liverpool", score.title)
        assertEquals("Bruno Fernandes · pen · 67' · Premier League", score.text)
    }

    @Test
    fun aDashMeansNoText() {
        val score = ServerLine.parse("SCORE Football FullTime FULL-TIME -", 0L) as ServerLine.Score
        assertEquals("", score.text)
    }

    @Test
    fun aShortLineIsIgnored() {
        assertTrue(ServerLine.parse("SCORE Football", 0L) is ServerLine.Other)
    }

    @Test
    fun oneNotificationPerMatchWhateverTheScore() {
        assertEquals(
            ScoreNotifications.notificationId("GOAL · Man United 1 – 0 Liverpool"),
            ScoreNotifications.notificationId("FULL-TIME · Man United 3 – 2 Liverpool"),
        )
        assertNotEquals(
            ScoreNotifications.notificationId("GOAL · Man United 1 – 0 Liverpool"),
            ScoreNotifications.notificationId("GOAL · Arsenal 1 – 0 Chelsea"),
        )
    }
}
