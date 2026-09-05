package com.nuvio.app.features.mdblist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the static-list wire shape and the id mapping that feeds it (plan §11).
 *
 * The wire shape matters more here than usual: `/watchlist/items/{action}` and
 * `/lists/{id}/items/{action}` are one path segment apart, take *different* body shapes, and each
 * accepts the other's by adding nothing at all. A silent no-op is the failure this file exists to
 * prevent.
 */
class MdbListStaticListsTest {
    @Test
    fun `static list body matches the documented flat shape`() {
        val body = encodeStaticListItems(
            listOf(
                MdbListItemRef(tmdbId = 630, imdbId = "tt0032138", isMovie = true),
                MdbListItemRef(tmdbId = 238, isMovie = true),
                MdbListItemRef(imdbId = "tt0417299", isMovie = false),
                MdbListItemRef(tmdbId = 1396, isMovie = false),
            )
        )

        assertEquals(
            """{"movies":[{"tmdb":630,"imdb":"tt0032138"},{"tmdb":238}],""" +
                """"shows":[{"imdb":"tt0417299"},{"tmdb":1396}]}""",
            body,
        )
    }

    @Test
    fun `static list ids are flat, not the watchlist's nested ids object`() {
        val body = encodeStaticListItems(listOf(MdbListItemRef(tmdbId = 550, isMovie = true)))

        assertTrue(body.contains(""""tmdb":550"""), body)
        // The watchlist endpoint would write {"ids":{"tmdb":550}} here. Sending that to the static
        // list endpoint succeeds and adds nothing.
        assertTrue(!body.contains("\"ids\""), body)
    }

    @Test
    fun `both keys are always present so an all-movie row still names shows`() {
        val body = encodeStaticListItems(listOf(MdbListItemRef(tmdbId = 550, isMovie = true)))

        assertEquals("""{"movies":[{"tmdb":550}],"shows":[]}""", body)
    }

    @Test
    fun `empty input encodes to two empty arrays rather than an empty object`() {
        assertEquals("""{"movies":[],"shows":[]}""", encodeStaticListItems(emptyList()))
    }

    @Test
    fun `tmdb ids map to the tmdb field`() {
        val ref = mdbListItemRef("tmdb:1234", "movie")

        assertEquals(1234, ref?.tmdbId)
        assertNull(ref?.imdbId)
        assertEquals(true, ref?.isMovie)
    }

    @Test
    fun `imdb ids map to the imdb field`() {
        val ref = mdbListItemRef("tt0111161", "movie")

        assertEquals("tt0111161", ref?.imdbId)
        assertNull(ref?.tmdbId)
    }

    @Test
    fun `series types land in shows`() {
        assertEquals(false, mdbListItemRef("tmdb:1396", "series")?.isMovie)
        assertEquals(false, mdbListItemRef("tmdb:1396", "tv")?.isMovie)
        assertEquals(true, mdbListItemRef("tmdb:1396", "movie")?.isMovie)
    }

    @Test
    fun `native anime and addon ids resolve to nothing`() {
        // Kitsu and MAL ids are franchise-level and mean nothing to MDBList; an addon can invent
        // anything. All three have to be reported as dropped rather than sent and silently ignored.
        assertNull(mdbListItemRef("kitsu:1376", "series"))
        assertNull(mdbListItemRef("mal:9253", "series"))
        assertNull(mdbListItemRef("someaddon:abc123", "movie"))
        assertNull(mdbListItemRef("", "movie"))
        assertNull(mdbListItemRef("tt", "movie"))
        assertNull(mdbListItemRef("ttnotanumber", "movie"))
    }

    @Test
    fun `outcomes add up across chunks`() {
        val first = MdbListPublishOutcome(added = 2, existing = 1, notFound = 0)
        val second = MdbListPublishOutcome(added = 3, existing = 0, notFound = 4)

        assertEquals(MdbListPublishOutcome(added = 5, existing = 1, notFound = 4), first + second)
    }

    @Test
    fun `dynamic lists do not accept items`() {
        val dynamic = MdbListUserList(1, "Trending", "trending", 30, isDynamic = true, isPrivate = false)
        val static = MdbListUserList(2, "Mine", "mine", 0, isDynamic = false, isPrivate = false)

        assertEquals(false, dynamic.acceptsItems)
        assertEquals(true, static.acceptsItems)
    }
}
