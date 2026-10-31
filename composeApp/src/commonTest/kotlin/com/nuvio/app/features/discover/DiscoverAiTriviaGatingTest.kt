package com.nuvio.app.features.discover

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverAiTriviaGatingTest {
    @Test
    fun triviaOnWithoutKeyUsesWikipediaNotTheProvider() {
        val settings = DiscoverAiSettings(triviaEnabled = true)
        assertTrue(settings.triviaEnabled)
        assertFalse(settings.isConfigured)
        assertFalse(settings.isTriviaReady)
    }

    @Test
    fun providerIsAskedOnlyWithKeyAndConsent() {
        val keyed = DiscoverAiSettings(triviaEnabled = true, apiKey = "k", consentGiven = false)
        assertFalse(keyed.isTriviaReady)
        assertTrue(keyed.copy(consentGiven = true).isTriviaReady)
        assertFalse(keyed.copy(consentGiven = true, triviaEnabled = false).isTriviaReady)
    }

    @Test
    fun groqUsesItsOwnHostAndDefaultModel() {
        val groq = DiscoverAiSettings(provider = DiscoverAiProvider.Groq)
        assertTrue(groq.effectiveBaseUrl == DISCOVER_AI_GROQ_BASE_URL)
        assertTrue(groq.effectiveModel == "openai/gpt-oss-120b")
    }
}
