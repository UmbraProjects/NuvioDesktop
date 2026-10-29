package com.nuvio.app.features.music.nebula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NebulaModelsTest {
    @Test fun baseUrlStripsManifest() {
        assertEquals("https://n.example/abc", nebulaBaseUrl(" https://n.example/abc/manifest.json "))
        assertEquals("https://n.example/abc", nebulaBaseUrl("https://n.example/abc/"))
        assertNull(nebulaBaseUrl("not a url"))
        assertNull(nebulaBaseUrl(""))
    }

    @Test fun searchAlbumsParse() {
        val raw = """{"tracks":[],"albums":[{"id":"1","title":"Abbey Road","artist":"The Beatles","trackCount":17,"year":1969},{"title":"no id"}]}"""
        val albums = parseNebulaSearchAlbums(raw)
        assertEquals(1, albums.size)
        assertEquals(17, albums[0].trackCount)
        assertTrue(parseNebulaSearchAlbums("garbage").isEmpty())
    }

    @Test fun streamNeedsHttpUrl() {
        assertNotNull(parseNebulaStream("""{"url":"https://cdn/x.flac","format":"flac"}"""))
        assertNull(parseNebulaStream("""{"url":"ftp://x"}"""))
        assertNull(parseNebulaStream("""{"detail":"Not Found"}"""))
    }

    @Test fun failureReasonNeverHasAddress() {
        assertFalse(nebulaFailureReason(401).contains("http://") || nebulaFailureReason(401).contains("https://"))
        assertTrue(nebulaFailureReason(null, error = "timeout").contains("timeout"))
    }

    @Test fun urlEncode() {
        assertEquals("a%20b%26c", urlEncodeQuery("a b&c"))
    }
}

class NebulaMatchingTest {
    private fun a(id: String, title: String, artist: String, n: Int? = null, y: Int? = null) =
        NebulaAlbumSummary(id, title, artist, n, y)

    @Test fun ranksExactTitleAndArtistFirst() {
        val ranked = rankNebulaAlbums(
            listOf(a("1", "Abbey Road (Super Deluxe)", "The Beatles", 40), a("2", "Abbey Road", "The Beatles", 17, 1969), a("3", "Road", "Other")),
            title = "Abbey Road", artist = "The Beatles", year = "1969", trackCount = 17,
        )
        assertEquals("2", ranked.first().id)
        assertFalse(ranked.any { it.id == "3" })
    }

    @Test fun wrongArtistIsRejected() {
        assertTrue(rankNebulaAlbums(listOf(a("1", "Greatest Hits", "Queen")), "Greatest Hits", "ABBA", null, 0).isEmpty())
    }

    @Test fun queriesTryArtistThenAlbumOnly() {
        assertEquals(listOf("beatles abbey road", "abbey road"), nebulaSearchQueries("Abbey Road (Remastered)", "The Beatles"))
    }

    private fun t(id: String, title: String, d: Int = 0) = NebulaTrack(id, title, "", d, null)

    @Test fun mapsByTitleEvenWhenOrderDiffers() {
        val addon = listOf(t("b", "Second Song"), t("a", "First Song"), t("c", "Third Song"))
        val wanted = listOf(
            NebulaTrackKey(0, 1, "First Song", 0), NebulaTrackKey(1, 2, "Second Song", 0), NebulaTrackKey(2, 3, "Third Song (Live)", 0),
        )
        assertEquals(listOf("a", "b", "c"), mapNebulaTracks(addon, wanted))
    }

    @Test fun neverUsesATrackTwice() {
        val addon = listOf(t("a", "Intro"), t("b", "Other"))
        val wanted = listOf(NebulaTrackKey(0, 1, "Intro", 0), NebulaTrackKey(1, 2, "Intro", 0))
        val result = mapNebulaTracks(addon, wanted)
        assertTrue(result == null || result.filterNotNull().toSet().size == result.filterNotNull().size)
    }

    @Test fun rejectsAlbumWithTooFewMatches() {
        val addon = listOf(t("a", "Alpha"), t("b", "Beta"))
        val wanted = (0 until 5).map { NebulaTrackKey(it, it + 1, "Completely $it Different", 0) }
        assertNull(mapNebulaTracks(addon, wanted))
    }
}

class NebulaLookupCacheTest {
    @Test fun roundTripAndFreshness() {
        val book = NebulaLookupBook().withLookup("k", StoredLookup(1000, "id", "T", listOf("a", null)))
        val back = decodeNebulaLookups(encodeNebulaLookups(book))
        assertEquals(listOf("a", null), back.lookups["k"]?.trackIds)
        val e = back.lookups.getValue("k")
        assertTrue(e.isFresh(1000 + NEBULA_FOUND_TTL_MS - 1))
        assertFalse(e.isFresh(1000 + NEBULA_FOUND_TTL_MS))
        assertFalse(StoredLookup(1000).isFresh(1000 + NEBULA_NONE_TTL_MS))
        assertFalse(e.isFresh(0))
    }

    @Test fun badJsonGivesEmptyBook() {
        assertTrue(decodeNebulaLookups("{nope").lookups.isEmpty())
        assertTrue(decodeNebulaLookups(null).lookups.isEmpty())
    }
}

class NebulaQualityTest {
    @Test fun labels() {
        assertEquals("FLAC · 16-bit", nebulaQualityLabel("flac", "16-bit"))
        assertEquals("FLAC", nebulaQualityLabel("flac", "FLAC"))
        assertEquals("LOSSLESS", nebulaQualityLabel(null, "lossless"))
        assertNull(nebulaQualityLabel(" ", null))
    }
}
