package com.nuvio.app.features.youtube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeSignedOutTest {
    private fun response(vararg loggedIn: String) =
        """{"responseContext":{"visitorData":"CgtidVlaOVFVWXpsRSjbqaPXBg%3D%3D","serviceTrackingParams":[""" +
            loggedIn.joinToString(",") {
                """{"service":"GFEEDBACK","params":[{"key":"browse_id","value":"FEwhat_to_watch"},{"key":"logged_in","value":"$it"}]}"""
            } +
            """,{"service":"CSI","params":[{"key":"c","value":"WEB"}]}]},"contents":{}}"""

    @Test
    fun `a signed request answered as signed out is recognised`() {
        // Shape of YouTube's answer to a browse with dead cookies (checked live, Nov 2, 2026).
        assertTrue(youTubeAnsweredSignedOut(response("0")))
        assertTrue(youTubeAnsweredSignedOut(response("0", "0")))
    }

    @Test
    fun `a signed-in answer, a mixed one or one without the flag is not`() {
        assertFalse(youTubeAnsweredSignedOut(response("1")))
        assertFalse(youTubeAnsweredSignedOut(response("1", "0")))
        assertFalse(youTubeAnsweredSignedOut("""{"responseContext":{"serviceTrackingParams":[]},"contents":{}}"""))
        assertFalse(youTubeAnsweredSignedOut("""{"contents":{}}"""))
        assertFalse(youTubeAnsweredSignedOut("<html>Sorry...</html>"))
    }
}
