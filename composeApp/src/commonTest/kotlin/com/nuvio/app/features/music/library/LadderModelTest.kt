package com.nuvio.app.features.music.library

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderModelTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    private fun length(p: TagProfile) = sqrt(p.values.sumOf { it * it })

    private fun artist(
        genres: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        mood: String? = null,
        style: String? = null,
        favourite: Boolean = false,
    ) = LibraryArtist(id = "a", name = "a", genres = genres, tags = tags, mood = mood, style = style, favourite = favourite)

    // region profiles

    @Test
    fun aProfileHasLengthOneAndEmptyInputGivesAnEmptyProfile() {
        near(1.0, length(tagProfile(listOf("shoegaze", "dream pop"), listOf("noise rock"))))
        assertEquals(emptyMap(), tagProfile(emptyList()))
        assertEquals(emptyMap(), tagProfile(listOf("seen live", "90s"), listOf("british")))
    }

    @Test
    fun earlierGenresWeighMoreThanLaterOnes() {
        val p = tagProfile(listOf("shoegaze", "dream pop", "ambient"))
        assertTrue(p.getValue("shoegaze") > p.getValue("dreampop"))
        assertTrue(p.getValue("dreampop") > p.getValue("ambient"))
    }

    @Test
    fun genresWeighMoreThanTags() {
        val p = tagProfile(genres = listOf("shoegaze"), tags = listOf("dream pop"))
        assertTrue(p.getValue("shoegaze") > p.getValue("dreampop"))
    }

    @Test
    fun spellingVariantsLandOnOneKey() {
        val p = tagProfile(listOf("post-punk"), tags = listOf("postpunk", "Post Punk"))
        assertEquals(setOf("postpunk"), p.keys)
    }

    @Test
    fun moodIsKeptApartFromGenres() {
        val p = tagProfile(listOf("ambient"), mood = "Melancholy, Dreamy")
        assertTrue("mood:melancholy" in p)
        assertTrue("mood:dreamy" in p)
        assertTrue("melancholy" !in p)
    }

    @Test
    fun styleIsSplitAndCleaned() {
        val p = tagProfile(emptyList(), style = "Alternative/Indie")
        assertEquals(setOf("alternative", "indie"), p.keys)
    }

    @Test
    fun aLongTailOfTagsIsCapped() {
        val tags = (1..30).map { "tag$it" }
        val p = tagProfile(emptyList(), tags)
        assertEquals(8, p.size)
    }

    @Test
    fun voteProfileScalesToTheTopTagAndMergesVariants() {
        val p = tagProfileFromVotes(mapOf("shoegaze" to 10, "post punk" to 3, "post-punk" to 2, "seen live" to 99))
        assertEquals(setOf("shoegaze", "postpunk"), p.keys)
        near(length(p), 1.0)
        // 5 votes against 10: the ratio survives scaling.
        near(0.5, p.getValue("postpunk") / p.getValue("shoegaze"))
    }

    @Test
    fun voteProfileIgnoresZeroVotesAndEmptyInput() {
        assertEquals(emptyMap(), tagProfileFromVotes(emptyMap()))
        assertEquals(emptyMap(), tagProfileFromVotes(mapOf("shoegaze" to 0)))
    }

    // endregion

    // region footprint

    @Test
    fun footprintOfNoArtistsIsEmpty() {
        assertEquals(emptyMap(), footprint(emptyList()))
        assertEquals(emptyMap(), footprint(listOf(artist())))
    }

    @Test
    fun footprintOfOneArtistIsItsOwnProfile() {
        val a = artist(genres = listOf("shoegaze", "dream pop"), mood = "dreamy")
        val f = footprint(listOf(a))
        for ((k, v) in a.tagProfile()) near(v, f.getValue(k))
    }

    @Test
    fun aFavouriteMovesTheFootprintMore() {
        val metal = artist(genres = listOf("metal"))
        val folk = artist(genres = listOf("folk"))
        val even = footprint(listOf(metal, folk))
        near(even.getValue("metal"), even.getValue("folk"))
        val tilted = footprint(listOf(metal.copy(favourite = true), folk))
        assertTrue(tilted.getValue("metal") > tilted.getValue("folk"))
    }

    // endregion

    // region cosine

    @Test
    fun cosineOfIdenticalIsOneAndDisjointIsZero() {
        val a = tagProfile(listOf("shoegaze", "ambient"))
        near(1.0, cosine(a, a))
        near(0.0, cosine(tagProfile(listOf("metal")), tagProfile(listOf("folk"))))
    }

    @Test
    fun cosineWithAnEmptySideIsZero() {
        near(0.0, cosine(emptyMap(), tagProfile(listOf("metal"))))
        near(0.0, cosine(tagProfile(listOf("metal")), emptyMap()))
        near(0.0, cosine(emptyMap(), emptyMap()))
    }

    @Test
    fun cosineIsSymmetric() {
        val a = tagProfile(listOf("shoegaze", "dream pop", "ambient"))
        val b = tagProfile(listOf("dream pop", "folk"))
        near(cosine(a, b), cosine(b, a))
    }

    // endregion

    // region proximity

    @Test
    fun proximityIgnoresTheTargetOnBothSides() {
        val me = footprint(listOf(artist(genres = listOf("shoegaze", "ambient"))))
        // Shares only the target with me: nothing is left to compare.
        val onlyTarget = tagProfile(listOf("shoegaze", "noise rock"))
        // Shares the ambient side as well.
        val sharesAmbient = tagProfile(listOf("shoegaze", "ambient"))
        val excluded = setOf("shoegaze")
        near(0.0, proximity(onlyTarget, me, excluded))
        assertTrue(proximity(sharesAmbient, me, excluded) > 0.99)
    }

    @Test
    fun withNoFootprintProximityIsZero() {
        near(0.0, proximity(tagProfile(listOf("shoegaze")), emptyMap(), emptySet()))
    }

    @Test
    fun aCandidateThatHasOnlyTheTargetHasNoProximity() {
        val me = footprint(listOf(artist(genres = listOf("shoegaze", "ambient"))))
        near(0.0, proximity(tagProfile(listOf("shoegaze")), me, setOf("shoegaze")))
    }

    // endregion

    // region purity

    @Test
    fun purityIsTheTargetsShareOfVotes() {
        near(0.5, purity(mapOf("shoegaze" to 5, "noise rock" to 3, "indie rock" to 2), "shoegaze"))
        near(1.0, purity(mapOf("shoegaze" to 5), "shoegaze"))
        near(0.0, purity(mapOf("metal" to 5), "shoegaze"))
    }

    @Test
    fun purityMergesVariantsAndIgnoresJunk() {
        val votes = mapOf("post-punk" to 3, "postpunk" to 3, "seen live" to 100, "90s" to 100, "new wave" to 4)
        near(0.6, purity(votes, "postpunk"))
    }

    @Test
    fun purityOfNoUsableVotesIsZero() {
        near(0.0, purity(emptyMap(), "shoegaze"))
        near(0.0, purity(mapOf("seen live" to 9), "shoegaze"))
    }

    @Test
    fun targetKeyGoesThroughTheCanonicalName() {
        assertEquals("drumandbass", targetGenreKey("DnB"))
        assertEquals("postpunk", targetGenreKey("Post Punk"))
        assertNull(targetGenreKey("seen live"))
        // "dnb" votes and a "drum and bass" target meet on one key.
        near(1.0, purity(mapOf("dnb" to 4), targetGenreKey("drum and bass")!!))
    }

    // endregion

    // region the ladder's two axes together

    @Test
    fun aDreamPopListenerIsCloserToSlowdiveThanToMyBloodyValentine() {
        val me = footprint(
            listOf(
                artist(genres = listOf("dream pop", "ambient"), mood = "dreamy"),
                artist(genres = listOf("dream pop", "slowcore")),
            ),
        )
        val target = targetGenreKey("shoegaze")!!
        val slowdiveVotes = mapOf("shoegaze" to 6, "dream pop" to 8, "ambient" to 4)
        val mbvVotes = mapOf("shoegaze" to 12, "noise rock" to 7, "indie rock" to 3)
        val slowdive = proximity(tagProfileFromVotes(slowdiveVotes, mood = "dreamy"), me, setOf(target))
        val mbv = proximity(tagProfileFromVotes(mbvVotes), me, setOf(target))
        assertTrue(slowdive > mbv, "slowdive=$slowdive mbv=$mbv")
        // And the other way round on purity: that is the ladder's whole shape.
        assertTrue(purity(mbvVotes, target) > purity(slowdiveVotes, target))
    }

    // endregion

    @Test
    fun shrunkPurityCountsTheEvidence() {
        val one = mapOf("shoegaze" to 1)
        val forty = mapOf("shoegaze" to 40)
        near(1.0, shrunkPurity(one, "shoegaze", 0.0))
        near(0.25, shrunkPurity(one, "shoegaze", 3.0))
        assertTrue(shrunkPurity(forty, "shoegaze", 3.0) > 0.9)
        near(0.0, shrunkPurity(emptyMap(), "shoegaze", 3.0))
    }
}
