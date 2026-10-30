package com.nuvio.app.features.music.library

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderAlbumTargetTest {
    private fun cand(
        id: String,
        name: String,
        votes: Map<String, Int>,
        fans: Long? = null,
        beginYear: Int? = null,
    ) = LadderCandidate(id = id, name = name, votes = votes, fans = fans, beginYear = beginYear)

    // The band's usual genre is alternative rock; this one record is dream pop.
    private val slowdive = cand("a1", "Slowdive", mapOf("alternative rock" to 30, "shoegaze" to 25))
    private val souvlaki = AlbumDetails(genre = "Dream Pop", style = "Shoegaze/Alternative Rock", mood = "Dreamy", year = "1993")

    private val artistTarget = artistTarget(slowdive)!!

    private fun resolver(details: AlbumDetails? = souvlaki, vararg hits: LadderCandidate) =
        AlbumTargetResolver(ArtistTargetResolver(now = { 0L }) { hits.toList().ifEmpty { listOf(slowdive) } }) { _, _ -> details }

    // region the blend

    @Test
    fun theRecordsOwnGenreOutweighsItsArtistsUsualOne() {
        val votes = blendAlbumVotes(slowdive.votes, souvlaki)
        assertEquals("dream pop", normalizeWeightedGenres(votes).keys.first())
        assertEquals(ALBUM_GENRE_PEAK, votes["dream pop"])
        // The artist's own tags are still in the mix.
        assertTrue(votes.getValue("alternative rock") > ALBUM_ARTIST_PEAK)
        assertTrue("shoegaze" in votes)
    }

    @Test
    fun withNoDetailsTheBlendIsTheArtistsTagsScaledToThePeak() {
        val votes = blendAlbumVotes(slowdive.votes, null)
        assertEquals(ALBUM_ARTIST_PEAK, votes.values.max())
        assertEquals(listOf("alternative rock", "shoegaze"), normalizeWeightedGenres(votes).keys.toList())
    }

    @Test
    fun junkInTheRecordsTagsIsLeftOut() {
        val votes = blendAlbumVotes(emptyMap(), AlbumDetails(genre = "Dream Pop", style = "seen live"))
        assertEquals(setOf("dream pop"), votes.keys)
    }

    @Test
    fun theYearIsTheSearchResultsThenTheSources() {
        assertEquals(1993, albumYear("1993", null))
        assertEquals(1991, albumYear("1991-09-16", AlbumDetails(year = "1993")))
        assertEquals(1993, albumYear(null, AlbumDetails(year = "1993")))
        assertEquals(1993, albumYear("soon", AlbumDetails(year = "1993")))
        assertNull(albumYear("0000", null))
        assertNull(albumYear(null, null))
    }

    @Test
    fun theTargetGenreIsTheBlendsAndTheArtistKeepsTheirIdAndName() {
        val album = albumTarget("Souvlaki", "1993", artistTarget, souvlaki)
        assertEquals("dream pop", album.target.genre)
        assertEquals("Slowdive", album.artistName)
        assertEquals("a1", album.target.artist.id)
        assertEquals("Dreamy", album.target.artist.mood)
        assertEquals(1993, album.year)
        assertEquals(1993, album.flavour.year)
        assertTrue(album.described)
        // Not the artist's own ladder.
        assertEquals("alternative rock", artistTarget.genre)
    }

    @Test
    fun aRecordNoSourceKnowsFallsBackToItsArtistsGenreAndItsYear() {
        val album = albumTarget("Souvlaki", "1993", artistTarget, null)
        assertEquals("alternative rock", album.target.genre)
        assertTrue(!album.described)
        assertEquals(1993, album.flavour.year)
        assertTrue(!album.flavour.isEmpty)
    }

    // endregion

    // region the flavour in buildLadder

    @Test
    fun eraFitFallsFromOneToZeroOverTheSpan() {
        assertEquals(1.0, eraFit(1993, 1993))
        assertEquals(0.0, eraFit(1993, 1993 + ERA_SPAN_YEARS))
        assertEquals(0.0, eraFit(1950, 1993))
        assertEquals(0.0, eraFit(null, 1993))
        assertEquals(0.0, eraFit(1993, null))
        assertEquals(0.5, eraFit(1993, 1993 - ERA_SPAN_YEARS / 2), 0.05)
    }

    @Test
    fun aCandidateFromTheRecordsEraBeatsAMorePopularOneFromAnother() {
        val old = cand("o", "Old Guard", mapOf("shoegaze" to 10), fans = 5_000_000, beginYear = 1965)
        val peer = cand("p", "Peer", mapOf("shoegaze" to 10), fans = 1_000, beginYear = 1991)
        val plain = buildLadder("shoegaze", emptyList(), listOf(old, peer), rungs = 1)
        assertEquals("o", plain.rungs.single().candidate.id)
        val era = buildLadder("shoegaze", emptyList(), listOf(old, peer), rungs = 1, flavour = LadderFlavour(year = 1993))
        val rung = era.rungs.single()
        assertEquals("p", rung.candidate.id)
        assertTrue(rung.why.endsWith("Began around the same time (1991)."), rung.why)
    }

    @Test
    fun aCandidateThatSharesTheRecordsSoundBeyondTheGenreWins() {
        val noise = cand("n", "Noisy", mapOf("shoegaze" to 10, "noise pop" to 5), fans = 10)
        val ambient = cand("m", "Ambient", mapOf("shoegaze" to 10, "ambient" to 5), fans = 5_000_000)
        val record = LadderFlavour(profile = tagProfileFromVotes(mapOf("shoegaze" to 10, "noise pop" to 6)))
        assertEquals("m", buildLadder("shoegaze", emptyList(), listOf(noise, ambient), rungs = 1).rungs.single().candidate.id)
        val rung = buildLadder("shoegaze", emptyList(), listOf(noise, ambient), rungs = 1, flavour = record).rungs.single()
        assertEquals("n", rung.candidate.id)
        assertTrue("Echoes the record's noise pop." in rung.why, rung.why)
    }

    @Test
    fun theFlavourNeverChangesWhoIsOfTheGenre() {
        val core = cand("c", "Core", mapOf("shoegaze" to 10), beginYear = 1960)
        val other = cand("x", "Other", mapOf("jazz" to 10, "shoegaze" to 1), beginYear = 1993)
        val ladder = buildLadder("shoegaze", emptyList(), listOf(core, other), rungs = 2, flavour = LadderFlavour(year = 1993))
        // Purity still orders the rungs, and the low-purity peer is below the bar while another candidate clears it.
        assertEquals(listOf("c"), ladder.rungs.map { it.candidate.id })
    }

    @Test
    fun noFlavourAndAnEmptyFlavourGiveThePlainLadder() {
        val pool = listOf(
            cand("a", "A", mapOf("shoegaze" to 9, "dream pop" to 7), beginYear = 1989),
            cand("b", "B", mapOf("shoegaze" to 6, "dream pop" to 3), beginYear = 1992),
            cand("c", "C", mapOf("shoegaze" to 8), beginYear = 2001),
        )
        val plain = buildLadder("shoegaze", emptyList(), pool)
        assertEquals(plain, buildLadder("shoegaze", emptyList(), pool, flavour = LadderFlavour()))
    }

    // endregion

    // region the ladders

    private val pool = listOf(
        cand("s1", "Slowdive", mapOf("alternative rock" to 30, "shoegaze" to 25)),
        cand("d1", "Cocteau Twins", mapOf("dream pop" to 12, "shoegaze" to 3), beginYear = 1979),
        cand("d2", "Beach House", mapOf("dream pop" to 9, "indie pop" to 4), beginYear = 2004),
        cand("d3", "Mazzy Star", mapOf("dream pop" to 8, "alternative rock" to 3), beginYear = 1989),
        cand("d4", "Lush", mapOf("dream pop" to 7, "shoegaze" to 7), beginYear = 1987),
        cand("d5", "Cranes", mapOf("dream pop" to 6, "gothic rock" to 2), beginYear = 1989),
        cand("r1", "Ride", mapOf("alternative rock" to 10, "shoegaze" to 5), beginYear = 1988),
        cand("r2", "Pixies", mapOf("alternative rock" to 15), beginYear = 1986),
    )

    @Test
    fun aRecordGivesADifferentLadderFromItsArtists() {
        val album = albumTarget("Souvlaki", "1993", artistTarget, souvlaki)
        val fromRecord = buildAlbumLadder(album, emptyList(), pool)
        val fromArtist = buildArtistLadder(artistTarget, emptyList(), pool)
        assertEquals("dream pop", fromRecord.target)
        assertEquals("alternative rock", fromArtist.target)
        assertNotEquals(fromArtist.rungs.map { it.candidate.id }, fromRecord.rungs.map { it.candidate.id })
        assertTrue(fromRecord.rungs.isNotEmpty())
    }

    @Test
    fun theRecordsArtistNeverStandsOnItsLadder() {
        val album = albumTarget("Souvlaki", "1993", artistTarget, null)
        val namesake = cand("zz", "slowdive", mapOf("alternative rock" to 9))
        val ladder = buildAlbumLadder(album, emptyList(), pool + namesake)
        assertTrue(ladder.rungs.none { it.candidate.id == "s1" || it.candidate.id == "zz" })
        assertTrue(ladder.rungs.isNotEmpty())
    }

    @Test
    fun theLadderIntoARecordAsksForTheBlendedGenresCandidates() = runBlocking<Unit> {
        val asked = ArrayList<String>()
        val out = ladderFromAlbum(
            resolver(), { g -> asked += g; pool }, "Souvlaki", "Slowdive", "1993", LadderMode.Into, emptyList(),
        )
        val ready = assertIs<AlbumLadderOutcome.Ready>(out)
        assertEquals(listOf("dream pop"), asked)
        assertEquals("dream pop", ready.ladder.target)
        assertEquals(LadderMode.Into, ready.mode)
        assertTrue(ready.ladder.rungs.isNotEmpty())
    }

    @Test
    fun leadingOutOfARecordGoesToOneOfItsOtherGenres() = runBlocking<Unit> {
        val asked = ArrayList<String>()
        val out = ladderFromAlbum(
            resolver(), { g -> asked += g; pool }, "Souvlaki", "Slowdive", "1993", LadderMode.OutFrom, emptyList(),
        )
        val ready = assertIs<AlbumLadderOutcome.Ready>(out)
        // Blend order is dream pop, shoegaze, alternative rock: out from dream pop leads into shoegaze first.
        assertEquals(listOf("shoegaze"), asked)
        assertEquals("shoegaze", ready.destination)
        assertEquals(listOf("alternative rock"), ready.alternatives)
        assertEquals(LadderMode.OutFrom, ready.mode)
    }

    @Test
    fun aChosenDestinationIsHonouredAndTheAlbumIsNotItsOwnNeighbour() = runBlocking<Unit> {
        val asked = ArrayList<String>()
        val out = ladderFromAlbum(
            resolver(), { g -> asked += g; pool }, "Souvlaki", "Slowdive", "1993", LadderMode.OutFrom, emptyList(),
            destination = "alternative rock",
        )
        val ready = assertIs<AlbumLadderOutcome.Ready>(out)
        assertEquals(listOf("alternative rock"), asked)
        assertEquals("alternative rock", ready.ladder.target)
        assertTrue(ready.ladder.rungs.none { it.candidate.id == "s1" })
        // Choosing the record's own genre is not leading out: the first other genre is used instead.
        val same = ladderFromAlbum(
            resolver(), { pool }, "Souvlaki", "Slowdive", "1993", LadderMode.OutFrom, emptyList(), destination = "dream pop",
        )
        assertEquals("shoegaze", assertIs<AlbumLadderOutcome.Ready>(same).destination)
    }

    @Test
    fun aRecordWithOneGenreLooksAtItsGenresNeighboursToLeadOut() = runBlocking<Unit> {
        val lone = cand("a9", "Loner", mapOf("dream pop" to 9))
        val details = AlbumDetails(genre = "Dream Pop")
        val asked = ArrayList<String>()
        val out = ladderFromAlbum(
            resolver(details, lone), { g -> asked += g; pool }, "Alone", "Loner", null, LadderMode.OutFrom, emptyList(),
        )
        val ready = assertIs<AlbumLadderOutcome.Ready>(out)
        // First the primary genre's pool is read to find neighbours, then the chosen neighbour's.
        assertEquals("dream pop", asked.first())
        assertEquals(2, asked.size)
        assertNotEquals("dream pop", ready.destination)
    }

    @Test
    fun noNeighbourGivesNoOutward() = runBlocking<Unit> {
        val lone = cand("a9", "Loner", mapOf("dream pop" to 9))
        val onlyDream = listOf(cand("d1", "One", mapOf("dream pop" to 5)), cand("d2", "Two", mapOf("dream pop" to 4)))
        val out = ladderFromAlbum(
            resolver(AlbumDetails(genre = "Dream Pop"), lone), { onlyDream }, "Alone", "Loner", null, LadderMode.OutFrom, emptyList(),
        )
        assertEquals(ArtistLadderFailure.NoOutward, assertIs<AlbumLadderOutcome.Failed>(out).reason)
    }

    @Test
    fun aSourceThatCannotBeReachedIsAFailureNotAnEmptyLadder() = runBlocking<Unit> {
        val out = ladderFromAlbum(resolver(), { null }, "Souvlaki", "Slowdive", "1993", LadderMode.Into, emptyList())
        assertEquals(ArtistLadderFailure.Unreachable, assertIs<AlbumLadderOutcome.Failed>(out).reason)
    }

    // endregion

    // region the lookup

    @Test
    fun anUnknownArtistFailsTheLookup() = runBlocking<Unit> {
        val none = AlbumTargetResolver(ArtistTargetResolver(now = { 0L }) { emptyList() }) { _, _ -> souvlaki }
        val out = none.resolve("Souvlaki", "Nobody", "1993")
        assertEquals(ArtistLadderFailure.NoArtist, assertIs<AlbumLookup.Failed>(out).reason)
    }

    @Test
    fun aDetailsLookupThatThrowsStillGivesATarget() = runBlocking<Unit> {
        val boom = AlbumTargetResolver(ArtistTargetResolver(now = { 0L }) { listOf(slowdive) }) { _, _ -> error("offline") }
        val album = assertIs<AlbumLookup.Found>(boom.resolve("Souvlaki", "Slowdive", "1993")).album
        assertTrue(!album.described)
        assertEquals("alternative rock", album.target.genre)
    }

    @Test
    fun theDetailsAreAskedForByTheArtistsNameAsMusicBrainzSpellsIt() = runBlocking<Unit> {
        var asked: Pair<String, String>? = null
        val r = AlbumTargetResolver(ArtistTargetResolver(now = { 0L }) { listOf(slowdive) }) { a, t -> asked = a to t; null }
        r.resolve("Souvlaki", "slowdive", null)
        assertEquals("Slowdive" to "Souvlaki", asked)
    }

    // endregion

    // region TheAudioDB

    private fun dbAlbum(title: String, genre: String? = null) = AudioDbAlbum(
        id = title, title = title, musicBrainzId = null, year = "1993", format = null, genre = genre, style = null,
        mood = null, label = null, description = null, art = com.nuvio.app.features.music.library.ReleaseArtwork(),
    )

    @Test
    fun anAlbumIsFoundByItsTitleIgnoringCaseAndPunctuation() {
        val albums = listOf(dbAlbum("Just for a Day"), dbAlbum("Souvlaki", "Dream Pop"))
        assertEquals("Souvlaki", pickAudioDbAlbum("souvlaki", albums)?.title)
        assertEquals("Just for a Day", pickAudioDbAlbum("Just For A Day!", albums)?.title)
    }

    @Test
    fun aEditionTailIsIgnoredOnEitherSide() {
        assertEquals("Souvlaki", pickAudioDbAlbum("Souvlaki (Deluxe Edition)", listOf(dbAlbum("Souvlaki")))?.title)
        assertEquals("Souvlaki [Remastered]", pickAudioDbAlbum("Souvlaki", listOf(dbAlbum("Souvlaki [Remastered]")))?.title)
    }

    @Test
    fun aNearTitleIsNeverGuessed() {
        assertNull(pickAudioDbAlbum("Souvlaki Space Station", listOf(dbAlbum("Souvlaki"))))
        assertNull(pickAudioDbAlbum("", listOf(dbAlbum("Souvlaki"))))
    }

    @Test
    fun anAlbumWithNothingToSayGivesNoDetails() {
        assertNull(AudioDbAlbum("1", "T", null, null, null, null, null, null, null, null, ReleaseArtwork()).toDetails())
        val d = dbAlbum("Souvlaki", "Dream Pop").toDetails()
        assertEquals("Dream Pop", d?.genre)
        assertEquals("1993", d?.year)
    }

    // endregion

    // region the screen model

    @Test
    fun theAlbumsTabOffersTheRecordItself() {
        val offers = ladderOffers("souvlaki", null, LadderAlbumRef("Souvlaki", "Slowdive", "1993"))
        assertEquals(listOf("Similar", "Opposite"), offers.map { it.label })
        assertEquals(LadderRequest.Album("Souvlaki", "Slowdive", "1993", LadderMode.Into), offers[0].request)
        assertEquals(LadderRequest.Album("Souvlaki", "Slowdive", "1993", LadderMode.OutFrom), offers[1].request)
    }

    @Test
    fun aGenreQueryStillGivesTheGenreOfferOnTheAlbumsTab() {
        val offers = ladderOffers("shoegaze", null, LadderAlbumRef("Souvlaki", "Slowdive", "1993"))
        assertEquals(listOf("Ladder into Shoegaze"), offers.map { it.label })
    }

    @Test
    fun noRecordOfferWithoutATitleOrAnArtist() {
        assertTrue(ladderOffers("something", null, LadderAlbumRef("", "Slowdive")).isEmpty())
        assertTrue(ladderOffers("something", null, LadderAlbumRef("Souvlaki", " ")).isEmpty())
        assertTrue(ladderOffers("something", null, null).isEmpty())
    }

    @Test
    fun anAlbumLadderIsReadyWithTheRecordInItsTitleAndSubtitle() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(slowdive) }
        val state = loadLadder(
            LadderRequest.Album("Souvlaki", "Slowdive", "1993"), emptyList(), resolver, { pool },
            albumDetails = { _, _ -> souvlaki },
        )
        val view = assertIs<LadderState.Ready>(state).view
        assertEquals("Into Souvlaki", view.title)
        assertEquals("Climbing into dream pop · Slowdive, 1993", view.subtitle)
        assertEquals("dream pop", view.ladder.target)
        assertTrue(view.note.orEmpty().contains("Nothing in your Library"))
    }

    @Test
    fun aRecordNoSourceKnowsSaysSoOnTheScreen() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(slowdive) }
        val state = loadLadder(LadderRequest.Album("Souvlaki", "Slowdive", "1993"), emptyList(), resolver, { pool })
        val view = assertIs<LadderState.Ready>(state).view
        assertTrue(view.note.orEmpty().startsWith("No details found for this record, so it follows Slowdive's genres and its year (1993)."))
        assertEquals("alternative rock", view.ladder.target)
    }

    @Test
    fun anAlbumOutFromLadderOffersTheOtherGenresAsChips() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { listOf(slowdive) }
        val state = loadLadder(
            LadderRequest.Album("Souvlaki", "Slowdive", "1993", LadderMode.OutFrom), emptyList(), resolver, { pool },
            albumDetails = { _, _ -> souvlaki },
        )
        val view = assertIs<LadderState.Ready>(state).view
        assertEquals("Out from Souvlaki", view.title)
        assertEquals("Leading out into shoegaze · Slowdive, 1993", view.subtitle)
        assertEquals(listOf("alternative rock"), view.alternatives)
    }

    @Test
    fun anAlbumByAnUnknownArtistIsAMessage() = runBlocking<Unit> {
        val resolver = ArtistTargetResolver(now = { 0L }) { emptyList() }
        val state = loadLadder(LadderRequest.Album("Souvlaki", "Nobody", null), emptyList(), resolver, { pool })
        assertEquals("MusicBrainz knows no artist called \"Nobody\".", assertIs<LadderState.Failed>(state).message)
    }

    @Test
    fun theDestinationChipsReachAlbumRequestsToo() {
        val album = LadderRequest.Album("Souvlaki", "Slowdive", "1993", LadderMode.OutFrom)
        assertEquals(album.copy(destination = "shoegaze"), album.withDestination("shoegaze"))
        assertTrue(album.hasDestinations)
        assertTrue(!LadderRequest.Genre("shoegaze").hasDestinations)
    }

    @Test
    fun progressIsKeptApartFromTheArtistsOwnLadder() {
        val into = progressKey(LadderRequest.Album("Souvlaki", "Slowdive", null))
        val out = progressKey(LadderRequest.Album("Souvlaki", "Slowdive", null, LadderMode.OutFrom, "Shoegaze"))
        val artist = progressKey(LadderRequest.Artist("Slowdive", LadderMode.Into))
        assertEquals(3, setOf(into, out, artist).size)
        assertEquals(into, progressKey(LadderRequest.Album("souvlaki", "SLOWDIVE", "1993")))
        assertEquals(out, progressKey(LadderRequest.Album("Souvlaki", "Slowdive", "1993", LadderMode.OutFrom, "shoegaze")))
    }

    // endregion

    @Test
    fun theTagSearchReadsTheYearAnArtistBegan() {
        val body = """{"artists":[
            {"id":"1","name":"Slowdive","life-span":{"begin":"1989-01-01"},"tags":[{"name":"shoegaze","count":5}]},
            {"id":"2","name":"Nameless","tags":[{"name":"shoegaze","count":2}]}]}"""
        val hits = parseMbTagSearch(body)
        assertEquals(1989, hits[0].beginYear)
        assertNull(hits[1].beginYear)
    }
}
