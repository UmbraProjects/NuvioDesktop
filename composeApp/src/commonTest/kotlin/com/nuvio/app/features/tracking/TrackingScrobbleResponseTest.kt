package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackingScrobbleResponseTest {
    @Test
    fun `scrobble action confirms watched`() {
        assertTrue(trackingScrobbleResponseConfirmsWatched("""{"action":"scrobble"}"""))
    }

    @Test
    fun `pause action does not confirm watched`() {
        assertFalse(trackingScrobbleResponseConfirmsWatched("""{"action":"pause"}"""))
    }

    @Test
    fun `explicit watched fields confirm watched`() {
        assertTrue(trackingScrobbleResponseConfirmsWatched("""{"status":"completed"}"""))
        assertTrue(trackingScrobbleResponseConfirmsWatched("""{"watched":true}"""))
        assertTrue(trackingScrobbleResponseConfirmsWatched("""{"completed":true}"""))
        assertTrue(trackingScrobbleResponseConfirmsWatched("""{"watched_at":"2026-08-24T12:00:00Z"}"""))
    }

    @Test
    fun `accepted or malformed responses do not imply watched`() {
        assertFalse(trackingScrobbleResponseConfirmsWatched("""{"detail":"accepted"}"""))
        assertFalse(trackingScrobbleResponseConfirmsWatched("""{"watched":false}"""))
        assertFalse(trackingScrobbleResponseConfirmsWatched(""))
        assertFalse(trackingScrobbleResponseConfirmsWatched("not-json"))
    }
}
