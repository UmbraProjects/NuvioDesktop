package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** fork:album-editions - Ren's "Vincent's Tale": an 11-track and a 7-track edition, both on YouTube Music. */
class EditionRelinkTest {
    private fun release(key: String, title: String, tracks: Int?, yt: String?) =
        LibraryRelease(key = key, title = title, kind = ReleaseKind.EP, trackCount = tracks, ytBrowseId = yt)

    private val linkedToShort = listOf(
        release("a", "Vincent's Tale", 11, "short"),
        release("b", "Vincent's Tale", 7, null),
        release("c", "Sick Sick Soul", 6, "sick"),
    )

    @Test
    fun movingToTheLongEditionLeavesTheShortOneToTheSibling() {
        val out = relinkedReleases(linkedToShort, from = "short", to = "long", trackCount = 11, fromTrackCount = 7)
        assertEquals(listOf("long", "short", "sick"), out.map { it.ytBrowseId })
        assertEquals(listOf(11, 7, 6), out.map { it.trackCount })
    }

    @Test
    fun noSiblingWithThatCountKeepsTheOldBehaviour() {
        val out = relinkedReleases(linkedToShort, from = "short", to = "long", trackCount = 11, fromTrackCount = 9)
        assertEquals(listOf("long", null, "sick"), out.map { it.ytBrowseId })
    }

    @Test
    fun anEditionAlreadyLinkedIsNotLinkedTwice() {
        val both = listOf(release("a", "Vincent's Tale", 11, "short"), release("b", "Vincent's Tale", 7, "long"))
        assertSame(both, relinkedReleases(both, from = "short", to = "long", trackCount = 11, fromTrackCount = 7))
    }

    @Test
    fun aMatchingAlbumStaysAndItsSiblingGetsTheOtherEdition() {
        val matching = listOf(release("a", "Vincent's Tale", 7, "short"), release("b", "Vincent's Tale", 11, null))
        val out = siblingLinked(matching, linkedId = "short", editionId = "long", trackCount = 11)
        assertEquals(listOf("short", "long"), out.map { it.ytBrowseId })
        // Nothing with that count, or the edition already linked: unchanged.
        assertSame(matching, siblingLinked(matching, linkedId = "short", editionId = "long", trackCount = 12))
        assertSame(out, siblingLinked(out, linkedId = "short", editionId = "long", trackCount = 11))
    }
}
