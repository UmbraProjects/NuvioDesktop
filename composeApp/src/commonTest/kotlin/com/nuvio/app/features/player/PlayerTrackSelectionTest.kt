package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTrackSelectionTest {
    private val englishSpanishTracks = listOf(
        SubtitleTrack(index = 0, id = "1", label = "English", language = "eng"),
        SubtitleTrack(index = 1, id = "2", label = "Spanish", language = "spa"),
        SubtitleTrack(index = 2, id = "3", label = "French forced", language = "fre", isForced = true),
    )

    private fun settings(
        showOnlyPreferred: Boolean,
        preferred: String = "en",
        secondary: String? = null,
        useForced: Boolean = false,
    ) = PlayerSettingsUiState(
        preferredSubtitleLanguage = preferred,
        secondaryPreferredSubtitleLanguage = secondary,
        subtitleStyle = SubtitleStyleState(
            showOnlyPreferredLanguages = showOnlyPreferred,
            useForcedSubtitles = useForced,
        ),
    )

    @Test
    fun `built-in subtitles are untouched when the preferred-only filter is off`() {
        val result = filterBuiltInSubtitlesForSettings(
            tracks = englishSpanishTracks,
            settings = settings(showOnlyPreferred = false),
            selectedIndex = -1,
        )
        assertEquals(englishSpanishTracks, result)
    }

    @Test
    fun `built-in subtitles keep only the preferred language when the filter is on`() {
        val result = filterBuiltInSubtitlesForSettings(
            tracks = englishSpanishTracks,
            settings = settings(showOnlyPreferred = true, preferred = "en"),
            selectedIndex = -1,
        )
        assertEquals(listOf(0), result.map { it.index })
    }

    @Test
    fun `the selected built-in track is never hidden by the preferred-only filter`() {
        val result = filterBuiltInSubtitlesForSettings(
            tracks = englishSpanishTracks,
            settings = settings(showOnlyPreferred = true, preferred = "en"),
            // Spanish is not preferred, but the user has it selected, so it must stay visible.
            selectedIndex = 1,
        )
        assertEquals(listOf(0, 1), result.map { it.index })
    }

    @Test
    fun `forced tracks survive the preferred-only filter while forced subtitles are on`() {
        // With forced mode on the primary preference resolves to FORCED (dropped), so a secondary
        // language is what supplies the concrete target — English here keeps track 0, and the
        // forced French track is kept because forced-subtitle mode is on.
        val result = filterBuiltInSubtitlesForSettings(
            tracks = englishSpanishTracks,
            settings = settings(showOnlyPreferred = true, secondary = "en", useForced = true),
            selectedIndex = -1,
        )
        assertEquals(listOf(0, 2), result.map { it.index })
    }

    @Test
    fun `built-in subtitles are untouched when no preferred languages resolve`() {
        val result = filterBuiltInSubtitlesForSettings(
            tracks = englishSpanishTracks,
            settings = settings(showOnlyPreferred = true, preferred = SubtitleLanguageOption.NONE),
            selectedIndex = -1,
        )
        assertEquals(englishSpanishTracks, result)
    }

    @Test
    fun `persisted subtitle language wins when a reused id changes language`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "Spanish", language = "spa"),
            SubtitleTrack(index = 1, id = "2", label = "English", language = "eng"),
        )
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            subtitleName = "English",
            subtitleTrackId = "1",
        )

        assertEquals(1, findPersistedSubtitleTrackIndex(tracks, preference))
    }

    @Test
    fun `persisted audio language wins when a reused id changes language`() {
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "Japanese", language = "jpn"),
            AudioTrack(index = 1, id = "2", label = "English", language = "eng"),
        )
        val preference = PersistedPlayerTrackPreference(
            audioLanguage = "eng",
            audioName = "English",
            audioTrackId = "1",
        )

        assertEquals(1, findPersistedAudioTrackIndex(tracks, preference))
    }

    @Test
    fun `track id remains a fallback for legacy preferences`() {
        val tracks = listOf(
            SubtitleTrack(index = 4, id = "9", label = "Unknown"),
        )

        assertEquals(
            4,
            findPersistedSubtitleTrackIndex(
                tracks,
                PersistedPlayerTrackPreference(subtitleTrackId = "9"),
            ),
        )
    }

    @Test
    fun `automatic addon subtitle waits for persisted track restoration`() {
        assertFalse(
            canApplyPreferredAddonSubtitle(
                trackPreferenceRestoreApplied = false,
                preferredSubtitleSelectionApplied = false,
                playbackIsLoading = false,
            ),
        )
        assertTrue(
            canApplyPreferredAddonSubtitle(
                trackPreferenceRestoreApplied = true,
                preferredSubtitleSelectionApplied = false,
                playbackIsLoading = false,
            ),
        )
    }

    @Test
    fun `automatic addon subtitle stays blocked after another preference wins`() {
        assertFalse(
            canApplyPreferredAddonSubtitle(
                trackPreferenceRestoreApplied = true,
                preferredSubtitleSelectionApplied = true,
                playbackIsLoading = false,
            ),
        )
    }
}
