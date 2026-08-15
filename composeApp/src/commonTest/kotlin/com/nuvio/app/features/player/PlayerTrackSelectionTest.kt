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

    private fun addonSubtitle(
        id: String,
        language: String,
        display: String = language,
    ) = AddonSubtitle(
        id = id,
        url = "https://example.test/$id.srt",
        language = language,
        display = display,
        addonName = "OpenSubtitles",
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
    fun `hearing impaired preference chooses SDH among matching languages`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English", language = "eng"),
            SubtitleTrack(index = 1, id = "2", label = "English SDH", language = "eng"),
        )

        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                preferHearingImpaired = true,
            ),
        )
    }

    @Test
    fun `persisted addon subtitle is rematched instead of reusing prior episode url`() {
        val nextEpisodeSubtitles = listOf(
            AddonSubtitle(
                id = "episode-2-english",
                url = "https://example.test/episode-2.vtt",
                language = "eng",
                display = "English SDH",
                addonName = "OpenSubtitles",
            ),
        )
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            subtitleName = "English SDH",
            addonSubtitleId = "episode-1-english",
            addonSubtitleUrl = "https://example.test/episode-1.vtt",
            addonSubtitleAddonName = "OpenSubtitles",
        )

        assertEquals(nextEpisodeSubtitles.single(), findPersistedAddonSubtitle(nextEpisodeSubtitles, preference))
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
    fun `addon subtitles survive the preferred-only filter when no preferred language resolves`() {
        val subtitles = listOf(
            addonSubtitle(id = "a", language = "eng"),
            addonSubtitle(id = "b", language = "spa"),
        )

        val result = filterAddonSubtitlesForSettings(
            subtitles = subtitles,
            // "None" with no secondary is the shipped default, and it resolves to no target at all.
            settings = settings(showOnlyPreferred = true, preferred = SubtitleLanguageOption.NONE),
            selectedAddonSubtitleId = null,
        )

        assertEquals(subtitles, result)
    }

    @Test
    fun `rejected addon subtitles are still dropped when no preferred language resolves`() {
        val subtitles = listOf(
            addonSubtitle(id = "a", language = "eng"),
            addonSubtitle(id = "b", language = "eng", display = "English forced"),
        )

        val result = filterAddonSubtitlesForSettings(
            subtitles = subtitles,
            settings = settings(showOnlyPreferred = true, preferred = SubtitleLanguageOption.NONE)
                .copy(rejectedSubtitleKeywords = setOf(SubtitleRejectKeyword.FORCED)),
            selectedAddonSubtitleId = null,
        )

        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun `an addon named after a reject keyword does not reject its own subtitles`() {
        val subtitle = AddonSubtitle(
            id = "a",
            url = "https://example.test/a.srt",
            language = "eng",
            display = "English",
            addonName = "Signs & Songs Subtitles",
        )

        assertFalse(
            setOf(SubtitleRejectKeyword.SIGNS, SubtitleRejectKeyword.SONGS)
                .rejectsAddonSubtitle(subtitle),
        )
    }

    @Test
    fun `the exact regional variant wins over a looser match`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "Portuguese", language = "pt"),
            SubtitleTrack(index = 1, id = "2", label = "Portuguese (Brazil)", language = "pt-BR"),
        )

        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(tracks = tracks, targets = listOf("pt-BR")),
        )
        // The loose match is still what makes a plain "pt" release usable for that preference.
        assertEquals(
            0,
            findPreferredSubtitleTrackIndex(
                tracks = tracks.take(1),
                targets = listOf("pt-BR"),
            ),
        )
    }

    @Test
    fun `an earlier target still outranks an exact match on a later one`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "German", language = "deu"),
            SubtitleTrack(index = 1, id = "2", label = "Portuguese", language = "pt"),
        )

        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(tracks = tracks, targets = listOf("pt-BR", "de")),
        )
    }

    @Test
    fun `SDH tracks are avoided when the hearing impaired preference is off`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English SDH", language = "eng"),
            SubtitleTrack(index = 1, id = "2", label = "English", language = "eng"),
        )

        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                preferHearingImpaired = false,
            ),
        )
    }

    @Test
    fun `an SDH track is still used when it is the only one in the language`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English SDH", language = "eng"),
        )

        assertEquals(
            0,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                preferHearingImpaired = false,
            ),
        )
    }

    @Test
    fun `a release that says it is not hearing impaired is not read as SDH`() {
        assertTrue(subtitleLooksHearingImpaired("Friends S01E07 The One with the Blackout.DVDRip.HI.cc"))
        assertFalse(subtitleLooksHearingImpaired("Friends S01E07 The One with the Blackout.DVDRip.NonHI.cc"))
    }

    @Test
    fun `the secondary subtitle language resolves Original against the title`() {
        assertEquals(
            "ja",
            resolveSecondarySubtitleLanguage(
                language = ORIGINAL_LANGUAGE_OPTION,
                originalLanguage = "jpn",
                deviceLanguages = listOf("en"),
            ),
        )
        // No TMDB language for this title: contribute nothing rather than a wrong language.
        assertEquals(
            null,
            resolveSecondarySubtitleLanguage(
                language = ORIGINAL_LANGUAGE_OPTION,
                originalLanguage = null,
                deviceLanguages = listOf("en"),
            ),
        )
    }

    @Test
    fun `the secondary language is reserved for the secondary track while dual subtitles are on`() {
        val dualSubtitleSettings = settings(showOnlyPreferred = false, preferred = "en", secondary = "de")
            .copy(dualSubtitlesEnabled = true)

        assertEquals(listOf("en"), primarySubtitleTargetsForSettings(dualSubtitleSettings, originalLanguage = null))
        // The list filters keep it, or the track wanted *as* the secondary is hidden from the menu.
        assertEquals(
            listOf("en", "de"),
            preferredSubtitleTargetsForSettings(dualSubtitleSettings, originalLanguage = null),
        )
    }

    @Test
    fun `forced-subtitle mode forwards nothing to an external player`() {
        assertEquals(
            emptyList(),
            externalPlayerSubtitleTargets(
                settings = settings(showOnlyPreferred = false, secondary = "en", useForced = true),
                originalLanguage = null,
            ),
        )
    }

    @Test
    fun `a secondary language alone is enough to forward subtitles to an external player`() {
        assertEquals(
            listOf("de"),
            externalPlayerSubtitleTargets(
                settings = settings(
                    showOnlyPreferred = false,
                    preferred = SubtitleLanguageOption.NONE,
                    secondary = "de",
                ),
                originalLanguage = null,
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
