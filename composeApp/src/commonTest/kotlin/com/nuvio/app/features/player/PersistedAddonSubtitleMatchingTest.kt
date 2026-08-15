package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Restoring a saved addon subtitle asks two different questions. On the same video the persisted id
 * and URL name one specific result. Across episodes those change every time, so only the descriptive
 * fields survive — and display names are not unique, which is what made the old field-by-field
 * matching pick by list order.
 */
class PersistedAddonSubtitleMatchingTest {

    private val opensubtitlesEnglish = AddonSubtitle(
        id = "os-1",
        url = "https://opensubtitles.test/1.srt",
        language = "eng",
        display = "English",
        addonName = "OpenSubtitles",
    )
    private val podnapisiEnglish = AddonSubtitle(
        id = "pod-1",
        url = "https://podnapisi.test/1.srt",
        language = "eng",
        display = "English",
        addonName = "Podnapisi",
    )
    private val opensubtitlesEnglishSdh = AddonSubtitle(
        id = "os-2",
        url = "https://opensubtitles.test/2.srt",
        language = "eng",
        display = "English SDH",
        addonName = "OpenSubtitles",
    )
    private val spanish = AddonSubtitle(
        id = "os-3",
        url = "https://opensubtitles.test/3.srt",
        language = "spa",
        display = "Spanish",
        addonName = "OpenSubtitles",
    )

    private val all = listOf(opensubtitlesEnglish, podnapisiEnglish, opensubtitlesEnglishSdh, spanish)

    @Test
    fun `the persisted id wins outright`() {
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            subtitleName = "English",
            // Deliberately points at the other addon: the id is the stronger evidence and the
            // addon-name field must not pre-empt it, which it used to.
            addonSubtitleAddonName = "Podnapisi",
            addonSubtitleId = "os-1",
        )

        assertEquals(opensubtitlesEnglish, findPersistedAddonSubtitle(all, preference))
    }

    @Test
    fun `the persisted url resolves when the id is gone`() {
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            addonSubtitleUrl = "https://podnapisi.test/1.srt",
        )

        assertEquals(podnapisiEnglish, findPersistedAddonSubtitle(all, preference))
    }

    @Test
    fun `a duplicate display name is disambiguated by the addon`() {
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            subtitleName = "English",
            addonSubtitleAddonName = "Podnapisi",
            // Next episode: the id no longer exists.
            addonSubtitleId = "os-1-previous-episode",
        )

        assertEquals(podnapisiEnglish, findPersistedAddonSubtitle(all, preference))
    }

    @Test
    fun `SDH status separates two results from the same addon`() {
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            subtitleName = "English SDH",
            addonSubtitleAddonName = "OpenSubtitles",
        )

        assertEquals(opensubtitlesEnglishSdh, findPersistedAddonSubtitle(all, preference))
    }

    @Test
    fun `an unresolvable name falls back to the preferred language rather than guessing`() {
        // Two addons offer "English" and the preference names neither. Picking one would be list
        // order dressed up as a restore; the language fallback is honest about it.
        val preference = PersistedPlayerTrackPreference(
            subtitleLanguage = "eng",
            subtitleName = "English",
        )

        val result = findPersistedAddonSubtitle(listOf(opensubtitlesEnglish, podnapisiEnglish), preference)

        assertEquals(opensubtitlesEnglish, result)
    }

    @Test
    fun `nothing identifying falls back to the language match`() {
        val preference = PersistedPlayerTrackPreference(subtitleLanguage = "spa")

        assertEquals(spanish, findPersistedAddonSubtitle(all, preference))
    }

    @Test
    fun `an empty list resolves to nothing`() {
        val preference = PersistedPlayerTrackPreference(subtitleLanguage = "eng", subtitleName = "English")

        assertNull(findPersistedAddonSubtitle(emptyList(), preference))
    }
}
