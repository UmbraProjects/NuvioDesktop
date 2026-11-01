package com.nuvio.app.features.preroll

import com.nuvio.app.features.discover.normalizeTitleForMatch
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrailerSuggestionsTest {
    private fun film(id: String, name: String, year: String? = null) =
        MetaPreview(id = id, type = "movie", name = name, releaseInfo = year)

    private fun lineup(
        suggestions: List<MetaPreview>,
        named: List<MetaPreview>,
        filmName: String = "The Dark Knight",
        excluded: Set<String> = setOf("tmdb:155"),
    ) = mergeTrailerLineup(
        excludedIds = excluded,
        filmTitleKey = normalizeTitleForMatch(filmName),
        baseTokens = franchiseTokens(filmName),
        suggestions = suggestions,
        named = named,
    ).map { it.name }

    @Test
    fun readsTitlesAndYears() {
        val raw = """{"films": [{"title": "Wonder Woman", "year": 2017}, {"title": "Aquaman", "year": "2018"}, {"title": "Joker"}]}"""
        assertEquals(
            listOf(TrailerSuggestion("Wonder Woman", 2017), TrailerSuggestion("Aquaman", 2018), TrailerSuggestion("Joker", null)),
            parseTrailerSuggestions(raw),
        )
    }

    @Test
    fun toleratesFencesAndDropsRepeatsAndJunkYears() {
        val raw = "```json\n{\"films\": [{\"title\": \"The Flash\", \"year\": 2023}, {\"title\": \"Flash\", \"year\": 1}, {\"title\": \"\"}]}\n```"
        assertEquals(listOf(TrailerSuggestion("The Flash", 2023)), parseTrailerSuggestions(raw)?.take(1))
        assertEquals(1, parseTrailerSuggestions(raw)?.size)
    }

    @Test
    fun unreadableRepliesGiveNull() {
        assertNull(parseTrailerSuggestions("Sorry, I can't do that"))
        assertNull(parseTrailerSuggestions("""{"titles": ["Aquaman"]}"""))
    }

    @Test
    fun suggestionsComeFirstThenWhatMoreLikeThisHasLeft() {
        val result = lineup(
            suggestions = listOf(film("tmdb:297762", "Wonder Woman", "2017"), film("tmdb:297802", "Aquaman", "2018")),
            named = listOf(film("tmdb:9", "Heat", "1995")),
        )
        assertEquals(listOf("Wonder Woman", "Aquaman", "Heat"), result)
    }

    @Test
    fun theFilmItselfAndItsCollectionAreNeverSuggested() {
        val result = lineup(
            suggestions = listOf(film("tmdb:155", "The Dark Knight"), film("tmdb:1", "Dark Knight"), film("tmdb:2", "Aquaman")),
            named = emptyList(),
        )
        assertEquals(listOf("Aquaman"), result)
    }

    @Test
    fun aSuggestionSharingTheFranchiseNameIsDropped() {
        val result = lineup(
            suggestions = listOf(film("tmdb:3", "The Dark Knight Rises"), film("tmdb:4", "Shazam!")),
            named = emptyList(),
        )
        assertEquals(listOf("Shazam!"), result)
    }

    @Test
    fun aTitleInBothListsPlaysOnce() {
        val result = lineup(
            suggestions = listOf(film("tmdb:5", "Aquaman", "2018")),
            named = listOf(film("tmdb:5", "Aquaman", "2018"), film("tmdb:6", "Aquaman", "2018"), film("tmdb:7", "Heat", "1995")),
        )
        assertEquals(listOf("Aquaman", "Heat"), result)
    }
}
