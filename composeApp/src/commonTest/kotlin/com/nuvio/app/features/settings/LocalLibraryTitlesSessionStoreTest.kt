package com.nuvio.app.features.settings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LocalLibraryTitlesSessionStoreTest {

    @AfterTest
    fun tearDown() {
        LocalLibraryTitlesSessionStore.clear()
    }

    @Test
    fun `returning to local library restores the profile filter and query`() {
        val beforeDetails = LocalLibraryTitlesSessionStore.stateForProfile(1)
        beforeDetails.filter = "movies"
        beforeDetails.query = "pokemon"

        val afterDetails = LocalLibraryTitlesSessionStore.stateForProfile(1)

        assertSame(beforeDetails, afterDetails)
        assertEquals("movies", afterDetails.filter)
        assertEquals("pokemon", afterDetails.query)
    }

    @Test
    fun `different profiles do not share local library controls`() {
        val firstProfile = LocalLibraryTitlesSessionStore.stateForProfile(1)
        firstProfile.filter = "movies"

        val secondProfile = LocalLibraryTitlesSessionStore.stateForProfile(2)

        assertNotSame(firstProfile, secondProfile)
        assertEquals("*all*", secondProfile.filter)
    }
}
