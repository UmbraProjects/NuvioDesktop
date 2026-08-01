package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class OriginalLanguagePreferenceTest {

    @Test
    fun `original audio resolves to the title's own language`() {
        assertEquals(
            listOf("es"),
            resolvePreferredAudioLanguageTargets(
                preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
                secondaryPreferredAudioLanguage = null,
                deviceLanguages = listOf("en"),
                originalLanguage = "es",
            ),
        )
    }

    @Test
    fun `a different title gets a different answer from the same setting`() {
        fun targetsFor(original: String?) = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = "en",
            deviceLanguages = listOf("en"),
            originalLanguage = original,
        )

        assertEquals(listOf("ja", "en"), targetsFor("ja"))
        assertEquals(listOf("fr", "en"), targetsFor("fr"))
    }

    @Test
    fun `an unknown original language falls through to the secondary rather than guessing`() {
        assertEquals(
            listOf("en"),
            resolvePreferredAudioLanguageTargets(
                preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
                secondaryPreferredAudioLanguage = "en",
                deviceLanguages = listOf("de"),
                originalLanguage = null,
            ),
        )
    }

    @Test
    fun `original is normalised like any other language, not passed through raw`() {
        assertEquals(
            listOf("ja"),
            resolvePreferredSubtitleLanguageTargets(
                preferredSubtitleLanguage = SubtitleLanguageOption.ORIGINAL,
                secondaryPreferredSubtitleLanguage = null,
                deviceLanguages = emptyList(),
                originalLanguage = "jpn",
            ),
        )
    }

    @Test
    fun `original works as the secondary subtitle choice too`() {
        assertEquals(
            listOf("en", "ko"),
            resolvePreferredSubtitleLanguageTargets(
                preferredSubtitleLanguage = "en",
                secondaryPreferredSubtitleLanguage = SubtitleLanguageOption.ORIGINAL,
                deviceLanguages = emptyList(),
                originalLanguage = "ko",
            ),
        )
    }

    @Test
    fun `omitting the original language leaves every other preference untouched`() {
        assertEquals(
            listOf("en", "de"),
            resolvePreferredAudioLanguageTargets(
                preferredAudioLanguage = "en",
                secondaryPreferredAudioLanguage = "de",
                deviceLanguages = listOf("fr"),
            ),
        )
    }
}
