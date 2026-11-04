package com.nuvio.app.features.music.booklet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlbumBookletTest {
    private val request = BookletRequest(
        artist = "Jamiroquai",
        album = "Travelling Without Moving",
        year = "1996",
        tracks = listOf(BookletTrack("Virtual Insanity", 221), BookletTrack("Cosmic Girl", 405), BookletTrack("Use the Force", 0)),
    )

    @Test
    fun normaliseKeepsWordsOnly() {
        assertEquals("travelling without moving deluxe", bookletNorm("Travelling Without Moving (Deluxe)"))
        assertEquals("beyonc\u00E9 lemonade", bookletNorm("  Beyonc\u00E9 -- Lemonade!! "))
        assertEquals("", bookletNorm("...!"))
    }

    @Test
    fun cacheKeysAgreeAcrossSpellings() {
        val a = bookletKey(request, "wiki")
        val b = bookletKey(request.copy(album = "TRAVELLING  WITHOUT MOVING"), "wiki")
        assertEquals(a, b)
        assertFalse(a == bookletKey(request, "ai:model"))
    }

    @Test
    fun cutEndsAtASentenceWhenOneFits() {
        assertEquals("First sentence here.", cutAtSentence("First sentence here. Second sentence goes on and on without end", 30))
        assertEquals("short", cutAtSentence("  short ", 30))
    }

    @Test
    fun cutFallsBackToAWordWithAnEllipsis() {
        assertEquals("alpha beta…", cutAtSentence("alpha beta gamma delta epsilon", 14))
    }

    @Test
    fun wikipediaIntroIsTheOpeningLine() {
        val text = "The Album is a studio album by X released in 1996 and it did well.\n\n== Background ==\nMore detail follows here."
        val booklet = wikipediaBooklet(text)
        assertNotNull(booklet)
        assertEquals("The Album is a studio album by X released in 1996 and it did well.", booklet.intro)
        assertEquals(BookletSource.Wikipedia, booklet.source)
        assertTrue(booklet.credits.isEmpty() && booklet.trackNotes.isEmpty())
    }

    @Test
    fun wikipediaIntroNeedsRealText() {
        assertNull(wikipediaBooklet(null))
        assertNull(wikipediaBooklet("short"))
    }

    private val goodReply = """
        {"intro":"Recorded in London, the third album moved the band toward disco and funk.",
         "credits":[{"role":"Producer","names":"Al Stone"},{"role":"","names":"nobody"}],
         "tracks":{"1":"The lead single.","3":"A slow groove.","99":"Not a track","x":"bad key"}}
    """.trimIndent()

    @Test
    fun parsesTheModelsAnswer() {
        val booklet = parseAiBooklet(goodReply, trackCount = 3)
        assertNotNull(booklet)
        assertEquals(BookletSource.Ai, booklet.source)
        assertEquals(listOf(BookletCredit("Producer", "Al Stone")), booklet.credits)
        assertEquals(mapOf(1 to "The lead single.", 3 to "A slow groove."), booklet.trackNotes)
    }

    @Test
    fun toleratesAFenceOrWordsAroundTheJson() {
        assertNotNull(parseAiBooklet("```json\n$goodReply\n```", 3))
        assertNotNull(parseAiBooklet("Here are the notes: $goodReply Hope that helps.", 3))
    }

    @Test
    fun refusesWhatCannotBeRead() {
        assertNull(parseAiBooklet("no json here", 3))
        assertNull(parseAiBooklet("{\"intro\": \"Recorded in London, the third", 3))
        assertNull(parseAiBooklet("{\"intro\": \"Short.\"}", 3))
        assertNull(parseAiBooklet("{\"credits\": []}", 3))
    }

    @Test
    fun capsTheNumberOfCredits() {
        val credits = (1..12).joinToString(",") { "{\"role\":\"Role $it\",\"names\":\"Name $it\"}" }
        val booklet = parseAiBooklet("{\"intro\":\"An intro that is long enough to keep.\",\"credits\":[$credits]}", 3)
        assertNotNull(booklet)
        assertEquals(8, booklet.credits.size)
    }

    @Test
    fun longNotesAreCut() {
        val note = "word ".repeat(200)
        val booklet = parseAiBooklet("{\"intro\":\"An intro that is long enough to keep.\",\"tracks\":{\"1\":\"$note\"}}", 3)
        assertNotNull(booklet)
        assertTrue(booklet.trackNotes.getValue(1).length <= 261)
    }

    @Test
    fun promptCarriesTheAlbumTheTracksAndTheText() {
        val prompt = bookletPrompt(request, "Jamiroquai released it in 1996.")
        assertTrue("Album: Travelling Without Moving" in prompt)
        assertTrue("Artist: Jamiroquai" in prompt)
        assertTrue("Year: 1996" in prompt)
        assertTrue("1. Virtual Insanity (3:41)" in prompt)
        assertTrue("2. Cosmic Girl (6:45)" in prompt)
        assertTrue("3. Use the Force\n" in prompt)
        assertTrue("<<<\nJamiroquai released it in 1996.\n>>>" in prompt)
        assertTrue("never invent" in BOOKLET_SYSTEM_PROMPT)
    }

    @Test
    fun anEntryRoundTripsThroughTheCache() {
        val booklet = AlbumBooklet(
            intro = "Hello there, this is the intro.",
            credits = listOf(BookletCredit("Producer", "Al Stone")),
            trackNotes = mapOf(2 to "two", 1 to "one"),
            source = BookletSource.Ai,
        )
        val decoded = decodeBookletEntry(encodeBookletEntry(BookletEntry(42L, booklet)))
        assertEquals(BookletEntry(42L, booklet), decoded)
        val wiki = booklet.copy(source = BookletSource.Wikipedia, credits = emptyList(), trackNotes = emptyMap())
        assertEquals(BookletEntry(7L, wiki), decodeBookletEntry(encodeBookletEntry(BookletEntry(7L, wiki))))
    }

    @Test
    fun aMissRoundTripsToo() {
        assertEquals(BookletEntry(5L, null), decodeBookletEntry(encodeBookletEntry(BookletEntry(5L, null))))
    }

    @Test
    fun damagedCacheTextIsIgnored() {
        assertNull(decodeBookletEntry(null))
        assertNull(decodeBookletEntry("garbage"))
        assertNull(decodeBookletEntry("{\"x\":1}"))
    }

    @Test
    fun freshnessDependsOnHitOrMiss() {
        val hit = BookletEntry(0L, AlbumBooklet("An intro that is long enough.", emptyList(), emptyMap(), BookletSource.Ai))
        val miss = BookletEntry(0L, null)
        val tenYears = 3_650L * 24 * 60 * 60 * 1_000
        assertTrue(hit.isFresh(tenYears, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS), "an AI fact never expires")
        assertTrue(hit.isFresh(Long.MAX_VALUE - 1, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS))
        assertTrue(miss.isFresh(BOOKLET_AI_MISS_TTL_MS - 1, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS))
        assertFalse(miss.isFresh(BOOKLET_AI_MISS_TTL_MS, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS))
    }

    @Test
    fun theOldestEntriesGoFirst() {
        val ages = mapOf("a" to 5L, "b" to 1L, "c" to 9L, "d" to 3L)
        assertEquals(emptyList(), bookletKeysToEvict(ages, 4))
        assertEquals(listOf("b", "d"), bookletKeysToEvict(ages, 2))
    }

    @Test
    fun theAisFactsAreDroppedLast() {
        val ages = mapOf("a" to 5L, "b" to 1L, "c" to 9L, "d" to 3L)
        assertEquals(listOf("d", "a"), bookletKeysToEvict(ages, 2, kept = setOf("b", "c")))
        assertEquals(listOf("a"), bookletKeysToEvict(ages, 3, kept = setOf("b", "c", "d")))
        assertEquals(listOf("b", "d"), bookletKeysToEvict(ages, 2, kept = setOf("a", "b", "c", "d")), "all kept: oldest still go")
    }

    @Test
    fun findsThePlayingTrackInTheAlbum() {
        val ids = listOf("a", "b", "c")
        assertEquals(2, bookletTrackNumber(ids, "b"))
        assertNull(bookletTrackNumber(ids, "zzz"))
        assertNull(bookletTrackNumber(ids, null))
    }

    @Test
    fun onlyRealAlbumsGetABooklet() {
        assertTrue(isBookletAlbum("MPREb_abc"))
        assertFalse(isBookletAlbum("OLAK5uy_abc"))
        assertFalse(isBookletAlbum("artist-mix:123"))
        assertFalse(isBookletAlbum(null))
    }
}
