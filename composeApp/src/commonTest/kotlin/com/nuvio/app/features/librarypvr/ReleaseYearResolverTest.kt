package com.nuvio.app.features.librarypvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure half of [ReleaseYearResolver]. The lookup order it wraps reads live repositories, but
 * everything downstream of a hit depends on this parse agreeing with what the catalogs display —
 * a wrong year here names a folder that no later download will match.
 */
class ReleaseYearResolverTest {

    @Test
    fun parsesASingleYear() {
        assertEquals(2021, ReleaseYearResolver.parseReleaseYear("2021"))
    }

    @Test
    fun takesTheFirstYearOfARange() {
        // Both dash shapes appear in the wild; a series folder is named for when it started.
        assertEquals(2021, ReleaseYearResolver.parseReleaseYear("2021-2023"))
        assertEquals(2019, ReleaseYearResolver.parseReleaseYear("2019–2023"))
        assertEquals(2019, ReleaseYearResolver.parseReleaseYear("2019–"))
    }

    @Test
    fun ignoresNumbersThatAreNotYears() {
        assertNull(ReleaseYearResolver.parseReleaseYear("1075"))
        assertNull(ReleaseYearResolver.parseReleaseYear("Season 2"))
        assertNull(ReleaseYearResolver.parseReleaseYear(""))
        assertNull(ReleaseYearResolver.parseReleaseYear(null))
    }

    @Test
    fun ignoresAYearEmbeddedInALongerNumber() {
        // The word boundary is what stops an id or a runtime being read as a release year.
        assertNull(ReleaseYearResolver.parseReleaseYear("120210"))
    }

    @Test
    fun rejectsAYearThatCouldNotNameAFolder() {
        // The pattern only admits 19xx/20xx, so anything else is out before the range check gets a
        // look in. Both are kept: the range is what agrees with LibraryFileNaming, and reporting a
        // year it would then silently drop would look like a successful lookup.
        assertNull(ReleaseYearResolver.parseReleaseYear("2999"))
        assertNull(ReleaseYearResolver.parseReleaseYear("1850"))
    }
}
