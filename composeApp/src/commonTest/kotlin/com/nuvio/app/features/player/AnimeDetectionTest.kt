package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimeDetectionTest {

    @Test
    fun `an explicit anime genre is decisive regardless of provenance`() {
        // Addons that tag "Anime" have already made the judgement; a US-licensed entry that still
        // carries the tag must not be talked out of it by its country field.
        assertTrue(
            isAnimeFromGenres(
                genres = listOf("Anime", "Action"),
                originalLanguage = "en",
                originCountries = listOf("US"),
            ),
        )
    }

    @Test
    fun `japanese animation is anime`() {
        assertTrue(
            isAnimeFromGenres(
                genres = listOf("Animation", "Action"),
                originalLanguage = "ja",
                originCountries = listOf("JP"),
            ),
        )
    }

    @Test
    fun `western animation is not anime`() {
        // South Park (tt0121955) and Spider-Verse (tt9362722): both tagged Animation, both English.
        // Detecting these auto-applied the anime shader chain and SVP interpolation to them.
        assertFalse(
            isAnimeFromGenres(
                genres = listOf("Animation", "Comedy"),
                originalLanguage = "en",
                originCountries = listOf("US"),
            ),
        )
        assertFalse(
            isAnimeFromGenres(
                genres = listOf("Animation", "Action", "Adventure"),
                originalLanguage = "en",
                originCountries = listOf("US"),
            ),
        )
    }

    @Test
    fun `animation with unknown provenance stays anime`() {
        // The lenient half of the rule: a thin addon meta must never cost a real anime its
        // detection. Only positive evidence of non-Japanese origin rejects.
        assertTrue(isAnimeFromGenres(genres = listOf("Animation")))
        assertTrue(isAnimeFromGenres(genres = listOf("Animation"), originalLanguage = "   "))
        assertTrue(isAnimeFromGenres(genres = listOf("Animation"), originCountries = listOf("", "  ")))
    }

    @Test
    fun `a japanese language beats a non-japanese country`() {
        // Co-productions are common; either signal saying Japan is enough.
        assertTrue(
            isAnimeFromGenres(
                genres = listOf("Animation"),
                originalLanguage = "ja",
                originCountries = listOf("US"),
            ),
        )
    }

    @Test
    fun `a comma joined country string is split`() {
        // MetaDetails.country arrives as one joined string ("JP, US"), not a list — comparing it
        // whole would make every co-production look non-Japanese.
        assertTrue(
            isAnimeFromGenres(
                genres = listOf("Animation"),
                originalLanguage = "en",
                originCountries = listOf("US, JP"),
            ),
        )
        assertFalse(
            isAnimeFromGenres(
                genres = listOf("Animation"),
                originalLanguage = "en",
                originCountries = listOf("US, CA"),
            ),
        )
    }

    @Test
    fun `language code variants are recognised`() {
        assertTrue(isAnimeFromGenres(listOf("Animation"), originalLanguage = "JPN"))
        assertTrue(isAnimeFromGenres(listOf("Animation"), originalLanguage = "ja-JP"))
        assertTrue(isAnimeFromGenres(listOf("Animation"), originCountries = listOf("Japan")))
    }

    @Test
    fun `live action is never anime`() {
        assertFalse(
            isAnimeFromGenres(
                genres = listOf("Drama", "Crime"),
                originalLanguage = "ja",
                originCountries = listOf("JP"),
            ),
        )
        assertFalse(isAnimeFromGenres(genres = emptyList()))
    }

    @Test
    fun `genre matching ignores case and padding`() {
        assertTrue(isAnimeFromGenres(genres = listOf("  ANIME  ")))
        assertTrue(isAnimeFromGenres(genres = listOf(" animation "), originalLanguage = "ja"))
    }
}

class AnimeContentKindTest {

    @Test
    fun `the three kinds are distinguished`() {
        assertEquals(
            AnimeContentKind.Anime,
            classifyAnimeContent(listOf("Anime"), originalLanguage = "en"),
        )
        assertEquals(
            AnimeContentKind.Anime,
            classifyAnimeContent(listOf("Animation"), originalLanguage = "ja"),
        )
        assertEquals(
            AnimeContentKind.WesternAnimation,
            classifyAnimeContent(listOf("Animation"), originalLanguage = "en", originCountries = listOf("US")),
        )
        assertEquals(
            AnimeContentKind.NotAnimation,
            classifyAnimeContent(listOf("Drama"), originalLanguage = "ja"),
        )
    }

    @Test
    fun `unknown provenance classifies as anime, not western animation`() {
        // The cache stores this kind, so getting it wrong here would make the preference below
        // unable to tell "we judged it Japanese" from "we could not judge it at all".
        assertEquals(AnimeContentKind.Anime, classifyAnimeContent(listOf("Animation")))
    }
}

class IncludeWesternAnimationPreferenceTest {

    private val westernCartoon = listOf("Animation", "Comedy")
    private val usProvenance = listOf("US")

    @Test
    fun `off leaves western animation undetected`() {
        assertFalse(
            isAnimeFromGenres(
                genres = westernCartoon,
                originalLanguage = "en",
                originCountries = usProvenance,
                treatAnimationAsAnime = false,
            ),
        )
    }

    @Test
    fun `on restores the old animation-is-anime behaviour`() {
        assertTrue(
            isAnimeFromGenres(
                genres = westernCartoon,
                originalLanguage = "en",
                originCountries = usProvenance,
                treatAnimationAsAnime = true,
            ),
        )
    }

    @Test
    fun `on does not make live action anime`() {
        // The preference widens animation, not everything: a Japanese live-action drama must stay
        // out regardless, or it would pick up the shader chain and SVP.
        assertFalse(
            isAnimeFromGenres(
                genres = listOf("Drama", "Crime"),
                originalLanguage = "ja",
                originCountries = listOf("JP"),
                treatAnimationAsAnime = true,
            ),
        )
    }

    @Test
    fun `real anime is unaffected either way`() {
        for (preference in listOf(false, true)) {
            assertTrue(
                isAnimeFromGenres(
                    genres = listOf("Animation", "Action"),
                    originalLanguage = "ja",
                    originCountries = listOf("JP"),
                    treatAnimationAsAnime = preference,
                ),
            )
            assertTrue(
                isAnimeFromGenres(genres = listOf("Anime"), treatAnimationAsAnime = preference),
            )
        }
    }

    @Test
    fun `the preference is applied to a stored kind, not baked into it`() {
        // AnimeContentCache stores the kind and applies the preference on read, so flipping the
        // toggle must change the answer for a title already classified this session.
        val kind = classifyAnimeContent(westernCartoon, originalLanguage = "en", originCountries = usProvenance)
        assertFalse(kind.isAnime(treatAnimationAsAnime = false))
        assertTrue(kind.isAnime(treatAnimationAsAnime = true))
    }
}
