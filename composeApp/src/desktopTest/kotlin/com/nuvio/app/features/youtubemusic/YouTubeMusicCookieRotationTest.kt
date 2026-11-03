package com.nuvio.app.features.youtubemusic

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * fork:ytmusic-login
 *
 * Google rotates parts of the signed-in session and expects the client to send the new values; a
 * jar frozen at sign-in is answered as a signed-out visitor within hours or days.
 */
class YouTubeMusicCookieRotationTest {
    private val jar = "SAPISID=abc; __Secure-1PSIDTS=old; SIDCC=c1; PREF=f6=40"

    @Test
    fun rotatedValuesReplaceTheSavedOnesInPlace() {
        val (merged, changed) = mergeSetCookies(
            jar,
            listOf(
                "__Secure-1PSIDTS=new; expires=Tue, 02-Nov-2027 18:00:00 GMT; path=/; domain=.youtube.com; Secure; HttpOnly",
                "SIDCC=c2; path=/; domain=.youtube.com",
            ),
        )

        assertEquals("SAPISID=abc; __Secure-1PSIDTS=new; SIDCC=c2; PREF=f6=40", merged)
        assertEquals(listOf("__Secure-1PSIDTS", "SIDCC"), changed)
    }

    @Test
    fun clearingsOtherDomainsAndNewNamesLeaveTheJarAlone() {
        val (merged, changed) = mergeSetCookies(
            jar,
            listOf(
                "SAPISID=EXPIRED; Max-Age=0; domain=.youtube.com",
                "SIDCC=; domain=.youtube.com",
                "__Secure-1PSIDTS=elsewhere; domain=.google.com",
                "PREF=x; expires=Thu, 01-Jan-1970 00:00:01 GMT",
                "VISITOR_INFO1_LIVE=v; domain=.youtube.com",
            ),
        )

        assertEquals(jar, merged)
        assertEquals(emptyList(), changed)
    }

    @Test
    fun anUnchangedValueIsNotAChange() {
        assertEquals(jar to emptyList(), mergeSetCookies(jar, listOf("SIDCC=c1; domain=.youtube.com")))
    }
}
