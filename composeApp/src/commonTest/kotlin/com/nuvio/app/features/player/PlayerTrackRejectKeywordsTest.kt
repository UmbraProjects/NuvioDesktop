package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTrackRejectKeywordsTest {
    private fun subtitleSettings(
        rejected: Set<SubtitleRejectKeyword>,
        showOnlyPreferred: Boolean = false,
        preferred: String = SubtitleLanguageOption.NONE,
        useForced: Boolean = false,
    ) = PlayerSettingsUiState(
        preferredSubtitleLanguage = preferred,
        rejectedSubtitleKeywords = rejected,
        subtitleStyle = SubtitleStyleState(
            showOnlyPreferredLanguages = showOnlyPreferred,
            useForcedSubtitles = useForced,
        ),
    )

    @Test
    fun `signs and songs tracks are matched however they are punctuated`() {
        val rejected = setOf(SubtitleRejectKeyword.SIGNS)
        listOf("English [Signs & Songs]", "eng.signs_songs", "Signs/Songs", "English (S&S)").forEach { label ->
            assertTrue(
                rejected.rejectsSubtitleTrack(SubtitleTrack(index = 0, id = "1", label = label)),
                "expected \"$label\" to be rejected",
            )
        }
    }

    @Test
    fun `a keyword only matches whole words`() {
        val rejected = setOf(SubtitleRejectKeyword.SIGNS, SubtitleRejectKeyword.SONGS)
        assertFalse(
            rejected.rejectsSubtitleTrack(
                SubtitleTrack(index = 0, id = "1", label = "Designed Subtitles", language = "eng"),
            ),
        )
    }

    @Test
    fun `a forced track is rejected from its container flag alone`() {
        val rejected = setOf(SubtitleRejectKeyword.FORCED)
        assertTrue(
            rejected.rejectsSubtitleTrack(
                // Nothing in the name says forced; only the flag does.
                SubtitleTrack(index = 0, id = "1", label = "English", language = "eng", isForced = true),
            ),
        )
    }

    @Test
    fun `preferring forced subtitles overrides a blanket forced rejection`() {
        val settings = subtitleSettings(
            rejected = setOf(SubtitleRejectKeyword.FORCED, SubtitleRejectKeyword.SIGNS),
            useForced = true,
        )
        assertEquals(setOf(SubtitleRejectKeyword.SIGNS), settings.effectiveRejectedSubtitleKeywords())
    }

    @Test
    fun `rejected built-in subtitles are hidden even with the preferred-only filter off`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English", language = "eng"),
            SubtitleTrack(index = 1, id = "2", label = "English (Signs & Songs)", language = "eng"),
        )
        val result = filterBuiltInSubtitlesForSettings(
            tracks = tracks,
            settings = subtitleSettings(rejected = setOf(SubtitleRejectKeyword.SIGNS)),
            selectedIndex = -1,
        )
        assertEquals(listOf(0), result.map { it.index })
    }

    @Test
    fun `a rejected built-in subtitle stays visible while it is the selected one`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English", language = "eng"),
            SubtitleTrack(index = 1, id = "2", label = "English Songs", language = "eng"),
        )
        val result = filterBuiltInSubtitlesForSettings(
            tracks = tracks,
            settings = subtitleSettings(rejected = setOf(SubtitleRejectKeyword.SONGS)),
            selectedIndex = 1,
        )
        assertEquals(listOf(0, 1), result.map { it.index })
    }

    @Test
    fun `automatic subtitle selection skips a rejected track for a later match`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English [Signs]", language = "eng"),
            SubtitleTrack(index = 1, id = "2", label = "English (Full)", language = "eng"),
        )
        val rejected = setOf(SubtitleRejectKeyword.SIGNS)
        assertEquals(
            1,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                isRejected = { rejected.rejectsSubtitleTrack(it) },
            ),
        )
    }

    @Test
    fun `automatic subtitle selection reports no match when every candidate is rejected`() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "1", label = "English Karaoke", language = "eng"),
        )
        val rejected = setOf(SubtitleRejectKeyword.KARAOKE)
        assertEquals(
            -1,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                isRejected = { rejected.rejectsSubtitleTrack(it) },
            ),
        )
    }

    @Test
    fun `commentary and audio-description tracks are rejected by their own keywords`() {
        assertTrue(
            setOf(AudioRejectKeyword.COMMENTARY).rejectsAudioTrack(
                AudioTrack(index = 1, id = "2", label = "English - Director's Commentary", language = "eng"),
            ),
        )
        assertTrue(
            setOf(AudioRejectKeyword.DESCRIPTIVE_AUDIO).rejectsAudioTrack(
                AudioTrack(index = 2, id = "3", label = "English Audio Description", language = "eng"),
            ),
        )
        assertTrue(
            setOf(AudioRejectKeyword.VISUALLY_IMPAIRED).rejectsAudioTrack(
                AudioTrack(index = 3, id = "4", label = "English (visually impaired)", language = "eng"),
            ),
        )
        assertFalse(
            AudioRejectKeyword.entries.toSet().rejectsAudioTrack(
                AudioTrack(index = 0, id = "1", label = "English 5.1 AC3", language = "eng"),
            ),
        )
    }

    @Test
    fun `a Vietnamese audio track survives the visually-impaired rejection`() {
        // "vi" is a language code as well as an abbreviation for the narration track, so it is
        // deliberately not one of the matched phrases.
        assertFalse(
            setOf(AudioRejectKeyword.VISUALLY_IMPAIRED).rejectsAudioTrack(
                AudioTrack(index = 0, id = "1", label = "Vietnamese", language = "vi"),
            ),
        )
    }

    @Test
    fun `audio filtering keeps the selected track and never empties the list`() {
        val settings = PlayerSettingsUiState(rejectedAudioKeywords = setOf(AudioRejectKeyword.COMMENTARY))
        val tracks = listOf(
            AudioTrack(index = 0, id = "1", label = "English", language = "eng"),
            AudioTrack(index = 1, id = "2", label = "Commentary", language = "eng"),
        )

        assertEquals(
            listOf(0),
            filterAudioTracksForSettings(tracks, settings, selectedIndex = 0).map { it.index },
        )
        assertEquals(
            listOf(0, 1),
            filterAudioTracksForSettings(tracks, settings, selectedIndex = 1).map { it.index },
        )
        // Every track rejected: showing them all beats leaving the picker empty.
        val commentaryOnly = listOf(AudioTrack(index = 0, id = "1", label = "Commentary", language = "eng"))
        assertEquals(commentaryOnly, filterAudioTracksForSettings(commentaryOnly, settings, selectedIndex = -1))
    }

    @Test
    fun `storage round-trips the selected keywords and drops unknown ones`() {
        val subtitles = setOf(SubtitleRejectKeyword.SIGNS, SubtitleRejectKeyword.FORCED)
        assertEquals(
            subtitles,
            parseSubtitleRejectKeywords(subtitles.map { it.storageValue }.toSet() + "not_a_keyword"),
        )
        val audio = setOf(AudioRejectKeyword.VISUALLY_IMPAIRED)
        assertEquals(audio, parseAudioRejectKeywords(audio.map { it.storageValue }.toSet() + ""))
    }
}
