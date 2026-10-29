package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals

class SimilarTasteTest {
    @Test
    fun lastFmSimilarReadsNamesAndMatchClosestFirst() {
        val body = """{"similarartists":{"artist":[
            {"name":"Olivia Rodrigo","match":"1"},{"name":"Gracie Abrams","match":"0.82"},{"name":"olivia rodrigo","match":"0.5"}
        ],"@attr":{"artist":"Someone"}}}"""
        val similar = parseLastFmSimilar(body)
        assertEquals(listOf("Olivia Rodrigo", "Gracie Abrams"), similar.map { it.name })
        assertEquals(0.82, similar[1].match)
        assertEquals(emptyList(), parseLastFmSimilar("""{"error":6,"message":"The artist you supplied could not be found"}"""))
    }

    @Test
    fun genreChipsAreTheArtistsOwnGenresTheSimilarArtistsShare() {
        val similar = listOf(
            SimilarArtist("A", 1.0, setOf("pop", "indie pop")),
            SimilarArtist("B", 0.9, setOf("pop")),
            SimilarArtist("C", 0.8, setOf("folk")),
        )
        assertEquals(listOf("pop", "folk"), similarTasteGenres(listOf("folk", "pop", "jazz"), similar))
    }
}

class RefreshedReleasesTest {
    private fun artist(sources: List<String>, vararg titles: String) = LibraryArtist(
        id = "UC1", name = "X", sources = sources,
        releases = titles.mapIndexed { i, t -> LibraryRelease(key = "k$i$t", title = t, kind = ReleaseKind.Single) },
    )

    @Test
    fun aShorterFreshListWinsWhenNoSourceWasLost() {
        // Two old entries for one song merged into one: the fresh, shorter list is the better one.
        val old = artist(listOf("YouTube Music", "MusicBrainz", "Deezer"), "Caught It", "Caught It (with Someone & X)")
        val fresh = artist(listOf("YouTube Music", "MusicBrainz", "Deezer"), "Caught It")
        assertEquals(listOf("Caught It"), refreshedReleases(fresh, old).map { it.title })
    }

    @Test
    fun anOutageAddsBackOnlyWhatTheFreshListLacks() {
        val old = artist(listOf("YouTube Music", "MusicBrainz"), "Caught It", "Rarity")
        val fresh = artist(listOf("YouTube Music"), "Caught It (with Someone & X)")
        assertEquals(listOf("Caught It (with Someone & X)", "Rarity"), refreshedReleases(fresh, old).map { it.title })
    }
}
