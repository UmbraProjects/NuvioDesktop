package com.nuvio.app.features.music

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicAlbumEditionsTest {
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
