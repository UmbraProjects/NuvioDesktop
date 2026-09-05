package com.nuvio.app.features.discover

import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000_000L
private const val DAY = 24L * 60 * 60 * 1000

private fun film(name: String, daysAgo: Long) = WatchedItem(
    id = "tt$name",
    type = "movie",
    name = name,
    markedAtEpochMs = NOW - daysAgo * DAY,
)

/** [episodes] distinct episodes of one series, the newest [daysAgo] old. */
private fun series(name: String, daysAgo: Long, episodes: Int) = (1..episodes).map { number ->
    WatchedItem(
        id = "tt$name",
        type = "series",
        name = name,
        season = 1,
        episode = number,
        markedAtEpochMs = NOW - (daysAgo + episodes - number) * DAY,
    )
}

class DiscoverAiProposalsTest {

    @Test
    fun `a fresh install is offered nothing`() {
        // Offering a row the history cannot fill is worse than offering none: the user pays a
        // provider to be told the app knows nothing about them.
        assertEquals(emptyList(), proposeAiDiscoverRows(emptyList(), emptyList(), NOW))
    }

    @Test
    fun `a thin history is offered nothing`() {
        val history = listOf(film("Arrival", 2), film("Dune", 4))

        assertEquals(emptyList(), proposeAiDiscoverRows(history, emptyList(), NOW))
    }

    @Test
    fun `recent viewing earns the just-watched offer, and it names what it saw`() {
        val history = (1..6).map { film("Film$it", daysAgo = it.toLong()) }

        val proposal = proposeAiDiscoverRows(history, emptyList(), NOW)
            .single { it.preset == AiDiscoverPreset.JustWatched }

        assertEquals(6, proposal.seedCount)
        // Newest first, and only the few worth showing — the prompt gets all of them.
        assertEquals(listOf("Film1", "Film2", "Film3"), proposal.evidence)
    }

    @Test
    fun `viewing older than the mood window earns no just-watched offer`() {
        // The slice means "right now". Six films from last winter describe a taste, not a mood, and
        // that is what the phase-6 presets are already for.
        val history = (1..6).map { film("Film$it", daysAgo = 200L + it) }

        assertTrue(
            proposeAiDiscoverRows(history, emptyList(), NOW)
                .none { it.preset == AiDiscoverPreset.JustWatched },
        )
    }

    @Test
    fun `the clustered offer ranks commitment over recency`() {
        // The motivating case from the plan: shows finished across many episodes are the strong
        // evidence of a favourite; a film watched yesterday is weak evidence of anything.
        val history = buildList {
            addAll(series("Dark", daysAgo = 40, episodes = 26))
            addAll(series("Lost", daysAgo = 60, episodes = 40))
            addAll(series("FROM", daysAgo = 30, episodes = 20))
            repeat(5) { add(film("Filler$it", daysAgo = it.toLong())) }
        }

        val proposal = proposeAiDiscoverRows(history, emptyList(), NOW)
            .single { it.preset == AiDiscoverPreset.ClusteredFavourites }

        assertEquals(listOf("Lost", "Dark", "FROM"), proposal.evidence)
    }

    @Test
    fun `a preset already saved is not offered again`() {
        // A second row of the same preset asks the same question of the same slice, and costs a
        // second request to hear the same answer.
        val history = (1..6).map { film("Film$it", daysAgo = it.toLong()) }
        val saved = listOf(AiDiscoverRow(id = "a", preset = AiDiscoverPreset.JustWatched))

        assertTrue(
            proposeAiDiscoverRows(history, saved, NOW)
                .none { it.preset == AiDiscoverPreset.JustWatched },
        )
    }

    @Test
    fun `nothing is offered once the row limit is reached`() {
        val history = (1..6).map { film("Film$it", daysAgo = it.toLong()) }
        val full = (1..AI_DISCOVER_ROW_LIMIT).map { AiDiscoverRow(id = "row$it") }

        assertEquals(emptyList(), proposeAiDiscoverRows(history, full, NOW))
    }

    @Test
    fun `each preset carries its own slice of history`() {
        val history = buildList {
            addAll(series("Dark", daysAgo = 40, episodes = 26))
            repeat(12) { add(film("Filler$it", daysAgo = it.toLong())) }
        }

        val justWatched = aiSeedsForPreset(AiDiscoverPreset.JustWatched, history, NOW)
        val clustered = aiSeedsForPreset(AiDiscoverPreset.ClusteredFavourites, history, NOW)

        // The mood slice is short and strictly newest-first; Dark is 40 days stale and misses it.
        assertEquals(JUST_WATCHED_SEEDS, justWatched.size)
        assertEquals("Filler0", justWatched.first().title)
        assertTrue(justWatched.none { it.title == "Dark" })

        // The commitment slice leads with the thing actually committed to.
        assertEquals("Dark", clustered.first().title)
        assertEquals("series", clustered.first().type)
    }

    @Test
    fun `the phase-6 presets keep the whole recent window they always had`() {
        val history = (1..12).map { film("Film$it", daysAgo = it.toLong()) }

        assertEquals(
            aiSeedsFromHistory(history, NOW),
            aiSeedsForPreset(AiDiscoverPreset.HiddenGems, history, NOW),
        )
        assertEquals(
            aiSeedsFromHistory(history, NOW),
            aiSeedsForPreset(AiDiscoverPreset.Custom, history, NOW),
        )
    }

    @Test
    fun `a phase-8 preset with no slice is refused before a request is made`() {
        // Both phase-8 instructions open with "The list below is…", and the prompt omits the list
        // when there are no seeds — so the model would be handed a reference to nothing and would
        // answer with a question. That came back as "the provider returned nothing usable", which
        // blamed the provider for a prompt we made incoherent.
        assertTrue(aiRowLacksItsHistorySlice(AiDiscoverPreset.JustWatched, seedCount = 0))
        assertTrue(aiRowLacksItsHistorySlice(AiDiscoverPreset.ClusteredFavourites, seedCount = 0))
        assertFalse(aiRowLacksItsHistorySlice(AiDiscoverPreset.JustWatched, seedCount = 1))
    }

    @Test
    fun `a phase-6 preset with no slice is still a real question`() {
        // "Suggest lesser-known titles" stands on its own; it is merely impersonal without history.
        assertFalse(aiRowLacksItsHistorySlice(AiDiscoverPreset.HiddenGems, seedCount = 0))
        assertFalse(aiRowLacksItsHistorySlice(AiDiscoverPreset.Custom, seedCount = 0))
    }

    @Test
    fun `an empty offer list says which of the three reasons it is`() {
        // These want opposite things from the user — watch something, delete a row, or nothing at
        // all — so telling someone who has added both that they lack watch history is simply wrong.
        assertEquals(
            AiProposalEmptyReason.NotEnoughHistory,
            aiProposalEmptyReason(emptyList()),
        )
        assertEquals(
            AiProposalEmptyReason.AllAdded,
            aiProposalEmptyReason(
                listOf(
                    AiDiscoverRow(id = "a", preset = AiDiscoverPreset.JustWatched),
                    AiDiscoverRow(id = "b", preset = AiDiscoverPreset.ClusteredFavourites),
                ),
            ),
        )
        assertEquals(
            AiProposalEmptyReason.RowLimitReached,
            aiProposalEmptyReason((1..AI_DISCOVER_ROW_LIMIT).map { AiDiscoverRow(id = "row$it") }),
        )
    }

    @Test
    fun `one proposable preset saved is not all of them`() {
        assertEquals(
            AiProposalEmptyReason.NotEnoughHistory,
            aiProposalEmptyReason(listOf(AiDiscoverRow(id = "a", preset = AiDiscoverPreset.JustWatched))),
        )
    }
}
