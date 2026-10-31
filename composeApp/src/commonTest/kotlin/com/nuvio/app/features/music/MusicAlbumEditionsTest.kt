package com.nuvio.app.features.music

import com.nuvio.app.features.music.library.LibraryRelease
import com.nuvio.app.features.music.library.ReleaseKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpectedEditionCountsTest {
    private fun release(key: String, title: String, tracks: Int?, yt: String?) =
        LibraryRelease(key = key, title = title, kind = ReleaseKind.EP, trackCount = tracks, ytBrowseId = yt)

    @Test
    fun theLinkedReleasesOwnCountComesFirst() {
        val releases = listOf(release("a", "Vincent's Tale", 7, "short"))
        assertEquals(listOf(7), expectedEditionCounts(releases, "short"))
    }

    @Test
    fun aSiblingNotOnYouTubeMusicAddsItsCount() {
        // The refresh left the linked release with no count; the unlinked sibling still knows 11.
        val releases = listOf(
            release("a", "Vincent's Tale", null, "short"),
            release("b", "Vincent's Tale", 11, null),
        )
        assertEquals(listOf(11), expectedEditionCounts(releases, "short"))
    }

    @Test
    fun bothCountsAreKeptWhenTheyDiffer() {
        val releases = listOf(
            release("a", "Vincent's Tale", 7, "short"),
            release("b", "Vincent's Tale (Deluxe)", 11, null),
        )
        assertEquals(listOf(7, 11), expectedEditionCounts(releases, "short"))
    }

    @Test
    fun otherTitlesAndYouTubeLinkedSiblingsAreIgnored() {
        val releases = listOf(
            release("a", "Vincent's Tale", 7, "short"),
            release("b", "Sick Sick Soul", 14, null),
            release("c", "Vincent's Tale", 11, "long"),
            release("d", "Vincent's Tale", null, null),
        )
        assertEquals(listOf(7), expectedEditionCounts(releases, "short"))
    }

    @Test
    fun anUnknownAlbumExpectsNothing() {
        assertEquals(emptyList(), expectedEditionCounts(listOf(release("a", "X", 5, "x")), "missing"))
    }
}

class MusicAlbumEditionsTest {
    @Test
    fun severalExpectedCountsFindTheLongerEdition() = runBlocking {
        val short = album("short", 7, listOf("long"))
        val long = album("long", 11)
        // 7 is what the page already has; only 11 is looked for.
        assertEquals("long", matchingEdition(short, listOf(7, 11)) { if (it == "long") long else null }.browseId)
    }

    @Test
    fun countsTheAlbumAlreadyHasLookNowhere() = runBlocking {
        val fetched = mutableListOf<String>()
        val short = album("short", 7, listOf("long"))
        val result = matchingEdition(short, listOf(7)) { fetched += it; null }
        assertEquals("short", result.browseId)
        assertEquals(emptyList(), fetched)
    }

    private fun album(id: String, tracks: Int, others: List<String> = emptyList()) = MusicAlbum(
        browseId = id,
        title = "Vincent's Tale",
        artists = emptyList(),
        year = "2026",
        kind = "Album",
        thumbnailUrl = null,
        tracks = (1..tracks).map { MusicTrack("v$id$it", "t$it", emptyList(), 100, it, isAvailable = true) },
        audioPlaylistId = null,
        otherVersionIds = others,
    )

    private val short = album("short", 7, listOf("long", "other"))
    private val long = album("long", 11)
    private val fetched = mutableListOf<String>()
    private val fetch: suspend (String) -> MusicAlbum? = { id ->
        fetched += id
        mapOf("long" to long, "other" to album("other", 9))[id]
    }

    @Test
    fun anAlbumWithTheExpectedTrackCountIsKept() = runBlocking {
        assertEquals("short", matchingEdition(short, 7, fetch).browseId)
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun theEditionWithTheExpectedTrackCountIsChosen() = runBlocking {
        assertEquals("long", matchingEdition(short, 11, fetch).browseId)
    }

    @Test
    fun noMatchingEditionKeepsTheAlbum() = runBlocking {
        assertEquals("short", matchingEdition(short, 14, fetch).browseId)
    }

    @Test
    fun unknownCountOrNoOtherVersionsChangesNothing() = runBlocking {
        assertEquals("short", matchingEdition(short, null, fetch).browseId)
        assertEquals("solo", matchingEdition(album("solo", 7), 11, fetch).browseId)
        assertEquals(emptyList(), fetched)
    }

    @Test
    fun aFailedFetchSkipsThatEdition() = runBlocking {
        val result = matchingEdition(short, 11) { id -> if (id == "long") error("down") else null }
        assertEquals("short", result.browseId)
    }
}
