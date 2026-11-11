package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchPredictionsTest {
    @Test
    fun readsTheSuggestionsOutOfAnAutocompleteAnswer() {
        val body = """["bat",["batman","batman begins trailer","bat sounds"],[],{"google:suggestsubtypes":[[512]]}]"""
        assertEquals(listOf("batman", "batman begins trailer", "bat sounds"), parseYouTubeSuggestions(body))
    }

    @Test
    fun anAnswerThatIsNotTheExpectedShapeGivesNothing() {
        assertEquals(emptyList(), parseYouTubeSuggestions("not json"))
        assertEquals(emptyList(), parseYouTubeSuggestions("""{"q":"bat"}"""))
        assertEquals(emptyList(), parseYouTubeSuggestions("""["bat"]"""))
    }
}
