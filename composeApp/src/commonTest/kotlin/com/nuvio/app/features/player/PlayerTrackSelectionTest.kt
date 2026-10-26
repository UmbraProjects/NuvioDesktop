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
        trackKind: SubtitleTrackKind = SubtitleTrackKind.DEFAULT,
    ) = PlayerSettingsUiState(
        preferredSubtitleLanguage = preferred,
        secondaryPreferredSubtitleLanguage = secondary,
        preferredSubtitleTrackKind = trackKind,
        subtitleStyle = SubtitleStyleState(showOnlyPreferredLanguages = showOnlyPreferred),
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
    fun `untagged built-in tracks stay listed under the preferred-only filter`() {
        // mpv's EIA-608 closed-caption track carries no language; it could be any of them.
        val tracks = englishSpanishTracks + listOf(
            SubtitleTrack(index = 3, id = "4", label = "EIA-608", language = null),
            SubtitleTrack(index = 4, id = "5", label = "Subtitle", language = "und"),
        )
        val result = filterBuiltInSubtitlesForSettings(
            tracks = tracks,
            settings = settings(showOnlyPreferred = true, preferred = "en"),
            selectedIndex = -1,
        )
        assertEquals(listOf(0, 3, 4), result.map { it.index })
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
    fun `the preferred track kind never widens the preferred-only filter`() {
        // The reported case: English preferred, forced preferred, filter on. The kind is a
        // selection tiebreaker, so the list still shows English alone — not every language, and
        // not the forced French track either.
        val result = filterBuiltInSubtitlesForSettings(
            tracks = englishSpanishTracks,
            settings = settings(showOnlyPreferred = true, trackKind = SubtitleTrackKind.FORCED),
            selectedIndex = -1,
        )
        assertEquals(listOf(0), result.map { it.index })

        val addons = listOf(
            addonSubtitle(id = "pt", language = "pob"),
            addonSubtitle(id = "en", language = "eng"),
        )
        assertEquals(
            listOf("en"),
            filterAddonSubtitlesForSettings(
                subtitles = addons,
                settings = settings(showOnlyPreferred = true, trackKind = SubtitleTrackKind.FORCED),
                selectedAddonSubtitleId = null,
            ).map { it.id },
        )
    }

    @Test
    fun `a legacy forced language value resolves to no target rather than a language`() {
        assertEquals(
            listOf("en"),
            resolvePreferredSubtitleLanguageTargets(
                preferredSubtitleLanguage = SubtitleLanguageOption.FORCED,
                secondaryPreferredSubtitleLanguage = "en",
                deviceLanguages = emptyList(),
            ),
        )
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
                trackKind = SubtitleTrackKind.SDH,
            ),
        )
    }

    @Test
    fun `forced preference chooses the forced track in the preferred language`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "French forced", language = "fre", isForced = true),
            SubtitleTrack(index = 1, id = "2", label = "English", language = "eng"),
            SubtitleTrack(index = 2, id = "3", label = "English", language = "eng", isForced = true),
        )

        // A forced track in another language is not a match; the English forced one is.
        assertEquals(
            2,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                trackKind = SubtitleTrackKind.FORCED,
            ),
        )
    }

    @Test
    fun `forced preference falls back to a full track in the same language before the secondary`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "Spanish forced", language = "spa", isForced = true),
            SubtitleTrack(index = 1, id = "2", label = "English SDH", language = "eng"),
            SubtitleTrack(index = 2, id = "3", label = "English", language = "eng"),
        )

        // No English forced track: plain English outranks English SDH, and both outrank the
        // forced track in the secondary language.
        assertEquals(
            2,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en", "es"),
                trackKind = SubtitleTrackKind.FORCED,
            ),
        )
    }

    @Test
    fun `forced tracks are avoided under the standard and SDH preferences`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English", language = "eng", isForced = true),
            SubtitleTrack(index = 1, id = "2", label = "English SDH", language = "eng"),
            SubtitleTrack(index = 2, id = "3", label = "English", language = "eng"),
        )

        assertEquals(
            2,
            findPreferredSubtitleTrackIndex(tracks, listOf("en"), trackKind = SubtitleTrackKind.STANDARD),
        )
        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(tracks, listOf("en"), trackKind = SubtitleTrackKind.SDH),
        )
        // With only forced and SDH on offer, a Standard viewer gets the full SDH transcript
        // rather than the fragment.
        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(tracks.take(2), listOf("en"), trackKind = SubtitleTrackKind.STANDARD),
        )
    }

    @Test
    fun `addon selection applies the same kind cascade`() {
        val subtitles = listOf(
            addonSubtitle(id = "sdh", language = "eng", display = "English SDH"),
            addonSubtitle(id = "plain", language = "eng", display = "English"),
        )

        assertEquals(
            "sdh",
            findPreferredAddonSubtitle(subtitles, listOf("en"), trackKind = SubtitleTrackKind.SDH)?.id,
        )
        assertEquals(
            "plain",
            findPreferredAddonSubtitle(subtitles, listOf("en"), trackKind = SubtitleTrackKind.STANDARD)?.id,
        )
        // Addons serve no forced tracks, so the forced viewer simply gets the plain one.
        assertEquals(
            "plain",
            findPreferredAddonSubtitle(subtitles, listOf("en"), trackKind = SubtitleTrackKind.FORCED)?.id,
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
                trackKind = SubtitleTrackKind.STANDARD,
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
                trackKind = SubtitleTrackKind.STANDARD,
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
    fun `a forced preference still forwards the preferred language to an external player`() {
        assertEquals(
            listOf("en"),
            externalPlayerSubtitleTargets(
                settings = settings(showOnlyPreferred = false, trackKind = SubtitleTrackKind.FORCED),
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
    fun `built-in match settles the automatic pass unless addon subtitles are preferred`() {
        // Default order: a confirmed built-in match closes the pass, no match leaves it open.
        assertTrue(settlesAutoPass(builtInMatchConfirmed = true, preferAddonSubtitles = false))
        assertFalse(settlesAutoPass(builtInMatchConfirmed = false, preferAddonSubtitles = false))
        // Prefer addons: even a confirmed built-in match waits for the addon fetch.
        assertFalse(settlesAutoPass(builtInMatchConfirmed = true, preferAddonSubtitles = true))
        assertFalse(settlesAutoPass(builtInMatchConfirmed = false, preferAddonSubtitles = true))
    }

    @Test
    fun `automatic pass settles at once when no addon fetch is coming`() {
        for (preferAddons in listOf(false, true)) {
            assertTrue(
                settlesAutoPass(
                    builtInMatchConfirmed = false,
                    preferAddonSubtitles = preferAddons,
                    startupMode = AddonSubtitleStartupMode.FAST_STARTUP,
                ),
            )
            assertTrue(
                settlesAutoPass(
                    builtInMatchConfirmed = false,
                    preferAddonSubtitles = preferAddons,
                    addonFetchPossible = false,
                ),
            )
        }
    }

    private fun settlesAutoPass(
        builtInMatchConfirmed: Boolean,
        preferAddonSubtitles: Boolean,
        startupMode: AddonSubtitleStartupMode = AddonSubtitleStartupMode.ALL_SUBTITLES,
        addonFetchPossible: Boolean = true,
    ) = builtInSubtitleSelectionSettlesAutoPass(
        builtInMatchConfirmed = builtInMatchConfirmed,
        preferAddonSubtitles = preferAddonSubtitles,
        addonSubtitleStartupMode = startupMode,
        addonFetchPossible = addonFetchPossible,
    )

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
