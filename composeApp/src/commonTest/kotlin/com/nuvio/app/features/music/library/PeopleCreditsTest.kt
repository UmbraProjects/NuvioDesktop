package com.nuvio.app.features.music.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleCreditsTest {
    // A cut-down MusicBrainz release with its recordings' and works' relationships.
    private val body = """{
      "relations": [{"type": "mastering", "artist": {"id": "m1", "name": "Ted Jensen"}}],
      "media": [{"tracks": [
        {"recording": {"relations": [
          {"type": "producer", "artist": {"id": "p1", "name": "Paul Epworth"}},
          {"type": "instrument", "attributes": ["guitar"], "artist": {"id": "g1", "name": "Neil Cowley"}},
          {"type": "vocal", "attributes": ["lead vocals"], "artist": {"id": "a1", "name": "Adele"}},
          {"type": "photography", "artist": {"id": "x1", "name": "Somebody"}},
          {"type": "performance", "work": {"relations": [
            {"type": "writer", "artist": {"id": "a1", "name": "Adele"}},
            {"type": "composer", "artist": {"id": "p1", "name": "Paul Epworth"}}
          ]}}
        ]}},
        {"recording": {"relations": [
          {"type": "mix", "artist": {"id": "e1", "name": "Tom Elmhirst"}},
          {"type": "instrument", "attributes": ["guitar"], "artist": {"id": "g1", "name": "Neil Cowley"}},
          {"type": "instrument", "attributes": ["piano"], "artist": {"id": "g1", "name": "Neil Cowley"}}
        ]}}
      ]}]
    }"""

    @Test
    fun everyKindIsReadAndOthersLeftOut() {
        val c = parseMbPeople(body)
        assertTrue(c.none { it.person == "Somebody" })
        assertEquals(CreditKind.Engineer, c.first { it.person == "Ted Jensen" }.kind)
        assertEquals(0, c.first { it.person == "Ted Jensen" }.track) // the whole release
        assertTrue(c.any { it.person == "Paul Epworth" && it.kind == CreditKind.Writer && it.detail == "composer" })
        assertTrue(c.any { it.person == "Adele" && it.kind == CreditKind.Player && it.detail == "lead vocals" })
        assertEquals("mix", c.first { it.person == "Tom Elmhirst" }.detail)
    }

    @Test
    fun peopleAreSummedUpMostCreditedFirst() {
        val people = creditPeople(parseMbPeople(body))
        assertEquals("Ted Jensen", people.first().name) // the whole album
        val neil = people.first { it.name == "Neil Cowley" }
        assertEquals(setOf(1, 2), neil.tracks)
        assertEquals("Guitar, Piano", neil.what)
        assertEquals("2 songs", neil.songsText(10))
        assertEquals("All songs", neil.songsText(2))
        val paul = people.first { it.name == "Paul Epworth" }
        assertEquals(setOf(CreditKind.Writer, CreditKind.Producer), paul.kinds)
        assertEquals(mapOf(CreditKind.Writer to 2, CreditKind.Producer to 1, CreditKind.Player to 2, CreditKind.Engineer to 2), creditCounts(people))
    }

    @Test
    fun keptCreditsSurviveStorageAndFindPeopleElsewhere() {
        val here = AlbumCredits(1, "MPREb_21", "Adele", "21", parseMbPeople(body), true)
        assertEquals(here, decodeAlbumCredits(encodeAlbumCredits(here)))
        val other = AlbumCredits(2, "MPREb_25", "Adele", "25", listOf(Credit("Paul Epworth", "p1", CreditKind.Producer, null, 3)), true)
        val unrelated = AlbumCredits(3, null, "Someone", "Else", listOf(Credit("Paul Epworth", "p9", CreditKind.Producer, null, 1)), true)
        val kept = mapOf(creditsKey("Adele", "21") to here, creditsKey("Adele", "25") to other, creditsKey("Someone", "Else") to unrelated)
        val paul = creditPeople(here.credits).first { it.name == "Paul Epworth" }
        val found = elsewhere(paul, kept, creditsKey("Adele", "21"))
        assertEquals(listOf("25"), found.map { it.album }) // matched by MusicBrainz id, not by name
        assertEquals("Producer", found.single().what)
        assertEquals("people2|adele|21", creditsKey("Adele", "21 (Deluxe)"))
    }
}
