package com.nuvio.app.features.music.nebula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NebulaAddonsTest {
    private val a = "https://nebula.example/abc"
    private val b = "https://nullbeat.example/xyz"

    @Test
    fun addonsKeepTheOrderTheLinksWereGiven() {
        val list = nebulaAddons(listOf(a, b))
        assertEquals(listOf(a, b), list.map { it.base })
    }

    @Test
    fun blankInvalidAndRepeatedLinksAreDropped() {
        val list = nebulaAddons(listOf("", "not a url", "$a/manifest.json", "$a/", b))
        assertEquals(listOf(a, b), list.map { it.base })
    }

    @Test
    fun noMoreThanThreeAddonsAreKept() {
        val many = (1..6).map { "https://addon$it.example/t" }
        assertEquals(NEBULA_MAX_ADDONS, nebulaAddons(many).size)
    }

    @Test
    fun anAddonsKeyIsStableShortAndNotShared() {
        val key = nebulaAddonKey(a)
        assertEquals(key, nebulaAddonKey(a))
        assertEquals(6, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertNotEquals(key, nebulaAddonKey(b))
        // Moving a link to another place does not change its key.
        assertEquals(nebulaAddons(listOf(a, b))[0].key, nebulaAddons(listOf(b, a))[1].key)
    }

    @Test
    fun trackIdsRoundTripForEveryAddon() {
        val key = nebulaAddonKey(b)
        assertEquals("nebula:123", nebulaTrackVideoId(null, "123"))
        assertEquals(NebulaTrackRef(null, "123"), nebulaTrackRef("nebula:123"))
        assertEquals("nebula:$key~123", nebulaTrackVideoId(key, "123"))
        assertEquals(NebulaTrackRef(key, "123"), nebulaTrackRef("nebula:$key~123"))
        assertNull(nebulaTrackRef("dQw4w9WgXcQ"))
    }

    @Test
    fun anIdThatMerelyContainsATildeIsNotTakenForAKeyedOne() {
        assertEquals(NebulaTrackRef(null, "ab~12"), nebulaTrackRef("nebula:ab~12"))
        assertEquals(NebulaTrackRef(null, "ABCDEF~1"), nebulaTrackRef("nebula:ABCDEF~1"))
        assertEquals(NebulaTrackRef(null, "abcdef~"), nebulaTrackRef("nebula:abcdef~"))
    }

    private fun found(addon: String?) = NebulaAlbumLookup.Found(StoredLookup(1L, "id", "T", listOf("t"), addon = addon), fromCache = false)

    @Test
    fun theFirstAddonThatHasTheAlbumWins() {
        val answer = firstAddonAnswer(listOf(NebulaAlbumLookup.NotFound, found("bbbbbb"), found("cccccc")))
        assertEquals("bbbbbb", (answer as NebulaAlbumLookup.Found).match.addon)
    }

    @Test
    fun aFoundBeatsAnEarlierFailure() {
        val answer = firstAddonAnswer(listOf(NebulaAlbumLookup.Failed("down"), found("bbbbbb")))
        assertTrue(answer is NebulaAlbumLookup.Found)
    }

    @Test
    fun notFoundOnlyWhenEveryAddonSaidSo() {
        assertEquals(NebulaAlbumLookup.NotFound, firstAddonAnswer(listOf(NebulaAlbumLookup.NotFound, NebulaAlbumLookup.NotFound)))
        assertEquals(NebulaAlbumLookup.NotFound, firstAddonAnswer(emptyList()))
    }

    @Test
    fun aFailureIsNotTurnedIntoNotFound() {
        val answer = firstAddonAnswer(listOf(NebulaAlbumLookup.NotFound, NebulaAlbumLookup.Failed("down")))
        assertEquals("down", (answer as NebulaAlbumLookup.Failed).reason)
    }

    @Test
    fun aKeptMatchNeedsItsAddonToStillBeSet() {
        val addons = nebulaAddons(listOf(a, b))
        assertTrue(StoredLookup(1L, "id", addon = addons[1].key).fitsAddons(addons))
        assertFalse(StoredLookup(1L, "id", addon = "ffffff").fitsAddons(addons))
        // An entry kept before there were several addons has no key and counts for the first.
        assertTrue(StoredLookup(1L, "id").fitsAddons(addons))
    }

    @Test
    fun aKeptMissIsOnlyBelievedForTheSameSetOfAddons() {
        val two = nebulaAddons(listOf(a, b))
        val one = nebulaAddons(listOf(a))
        val asked = two.joinToString(",") { it.key }
        assertTrue(StoredLookup(1L, asked = asked).fitsAddons(two))
        assertFalse(StoredLookup(1L, asked = asked).fitsAddons(one))
        assertFalse(StoredLookup(1L).fitsAddons(two))
    }

    @Test
    fun anOldKeptLookupStillDecodesAndMeansTheFirstAddon() {
        val old = """{"lookups":{"k":{"savedAt":5,"albumId":"9","albumTitle":"T","trackIds":["a",null]}}}"""
        val entry = decodeNebulaLookups(old).lookups.getValue("k")
        assertNull(entry.addon)
        assertEquals("", entry.asked)
    }

    @Test
    fun anAddonAlbumIdCarriesItsAddonAndOldOnesStillParse() {
        val key = nebulaAddonKey(b)
        val with = AddonAlbumRef("77", "UCartist", "rg-1", key)
        assertEquals(with, parseAddonAlbumId(addonAlbumId(with)))
        val without = AddonAlbumRef("77", "UCartist", "rg-1")
        assertEquals(without, parseAddonAlbumId(addonAlbumId(without)))
        assertNull(parseAddonAlbumId(addonAlbumId(without) + ":x:y"))
    }

    @Test
    fun availabilityFitsLikeLookups() {
        val addons = nebulaAddons(listOf(a, b))
        val summary = NebulaAlbumSummary("9", "T", "A", 10, 2000)
        assertTrue(addonFound(1L, summary, addons[1].key).fitsAddons(addons))
        assertFalse(addonFound(1L, summary, "ffffff").fitsAddons(addons))
        assertTrue(addonFound(1L, summary).fitsAddons(addons))
        val miss = addonMissed(1L, null, addons.joinToString(",") { it.key })
        assertTrue(miss.fitsAddons(addons))
        assertFalse(miss.fitsAddons(addons.take(1)))
    }
}
