package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the model is actually asked (plan §5) — the part hardest to check by eye. */
class DiscoverAiPromptTest {
    private val seeds = listOf(
        DiscoverAiSeed("Severance", 2022, "series", 9.0),
        DiscoverAiSeed("Arrival", 2016, "movie", 8.5),
    )

    @Test
    fun `seeds carry title, year, type and rating`() {
        val prompt = buildDiscoverAiPrompt(DiscoverAiPreset.HiddenGems, seeds = seeds)

        assertTrue(prompt.user.contains("- Severance (2022) [series] rated 9.0/10"), prompt.user)
        assertTrue(prompt.user.contains("- Arrival (2016) [movie] rated 8.5/10"), prompt.user)
    }

    @Test
    fun `a seed with no year or rating still describes itself`() {
        val prompt = buildDiscoverAiPrompt(
            DiscoverAiPreset.HiddenGems,
            seeds = listOf(DiscoverAiSeed("Unknown Thing", type = "movie")),
        )

        assertTrue(prompt.user.contains("- Unknown Thing [movie]"), prompt.user)
        assertFalse(prompt.user.contains("rated"), prompt.user)
    }

    @Test
    fun `seeds are capped so the prompt cannot grow without bound`() {
        val many = (1..200).map { DiscoverAiSeed("Title $it", 2000, "movie") }
        val prompt = buildDiscoverAiPrompt(DiscoverAiPreset.HiddenGems, seeds = many)

        assertTrue(prompt.user.contains("- Title $DISCOVER_AI_MAX_SEEDS ("), prompt.user)
        assertFalse(prompt.user.contains("- Title ${DISCOVER_AI_MAX_SEEDS + 1} ("), prompt.user)
    }

    @Test
    fun `exclusions are named, deduped and capped`() {
        val prompt = buildDiscoverAiPrompt(
            DiscoverAiPreset.HiddenGems,
            exclusions = listOf("Dune", "Dune", "  ", "Heat"),
        )

        assertTrue(prompt.user.contains("already been watched"), prompt.user)
        assertEquals(1, Regex("Dune").findAll(prompt.user).count(), prompt.user)
        assertTrue(prompt.user.contains("Heat"), prompt.user)
    }

    @Test
    fun `no exclusion section when there is nothing to exclude`() {
        val prompt = buildDiscoverAiPrompt(DiscoverAiPreset.HiddenGems, exclusions = listOf("", " "))

        assertFalse(prompt.user.contains("already been watched"), prompt.user)
    }

    @Test
    fun `a custom preset uses the user's own instruction`() {
        val prompt = buildDiscoverAiPrompt(
            DiscoverAiPreset.Custom,
            customInstruction = "Only French crime films from the 1970s",
        )

        assertTrue(prompt.user.startsWith("Only French crime films from the 1970s"), prompt.user)
    }

    @Test
    fun `a blank custom instruction still asks a real question`() {
        val prompt = buildDiscoverAiPrompt(DiscoverAiPreset.Custom, customInstruction = "   ")

        // An empty instruction would leave the model with only the seed list and no task at all.
        assertTrue(prompt.user.isNotBlank())
        assertTrue(prompt.user.startsWith("Suggest titles"), prompt.user)
    }

    @Test
    fun `the system prompt demands bare JSON and names every key`() {
        val prompt = buildDiscoverAiPrompt(DiscoverAiPreset.ComfortWatches)

        assertTrue(prompt.system.contains("JSON array"), prompt.system)
        listOf("title", "year", "type", "reason").forEach { key ->
            assertTrue(prompt.system.contains("\"$key\""), "system prompt is missing $key")
        }
    }

    @Test
    fun `the requested count is bounded`() {
        val huge = buildDiscoverAiPrompt(DiscoverAiPreset.HiddenGems, requestedItems = 5000)
        val zero = buildDiscoverAiPrompt(DiscoverAiPreset.HiddenGems, requestedItems = 0)

        assertTrue(huge.user.contains("Return exactly 50 suggestions"), huge.user)
        assertTrue(zero.user.contains("Return exactly 1 suggestions"), zero.user)
    }

    @Test
    fun `the just-watched prompt asks about this mood, not an all-time taste`() {
        val prompt = buildDiscoverAiPrompt(
            preset = DiscoverAiPreset.JustWatched,
            seeds = listOf(DiscoverAiSeed(title = "Dark", type = "series")),
        )

        // The distinction the whole preset exists for: a recommendation feed built from a profile
        // can already answer "what suits me", and cannot answer "what next, right now".
        assertTrue(prompt.user.contains("most recently"))
        assertTrue(prompt.user.contains("all-time taste"))
        assertTrue(prompt.user.contains("Dark"))
    }

    @Test
    fun `the clustered prompt asks for the shared quality to be named`() {
        val prompt = buildDiscoverAiPrompt(preset = DiscoverAiPreset.ClusteredFavourites)

        // Naming it is what makes a wrong cluster visible instead of merely disappointing.
        assertTrue(prompt.user.contains("as a group"))
        assertTrue(prompt.user.contains("not their genre"))
        assertTrue(prompt.user.contains("name the quality"))
    }
}
