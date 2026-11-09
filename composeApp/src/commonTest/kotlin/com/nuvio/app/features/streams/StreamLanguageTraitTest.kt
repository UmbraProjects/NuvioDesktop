package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Language words in release names, without reading the title's own words as a language. */
class StreamLanguageTraitTest {

    private fun languagesOf(filename: String, title: String? = null): List<DebridStreamLanguage> =
        StreamTraitDetector.detect(
            StreamItem(
                name = "addon",
                addonName = "addon",
                addonId = "addon",
                url = "http://example.invalid/${filename.hashCode()}",
                behaviorHints = StreamBehaviorHints(filename = filename),
                clientResolve = title?.let { StreamClientResolve(title = it) },
            ),
        ).languages

    @Test
    fun languageWordsInReleaseNamesAreRecognised() {
        assertEquals(listOf(DebridStreamLanguage.IT), languagesOf("Movie.2024.1080p.WEB-DL.ITA.x264-GRP.mkv"))
        assertEquals(listOf(DebridStreamLanguage.FR), languagesOf("Movie.2024.TRUEFRENCH.1080p.WEB-DL.x264-GRP.mkv"))
        assertEquals(listOf(DebridStreamLanguage.DE), languagesOf("Movie.2024.German.DL.1080p.BluRay.x264-GRP.mkv"))
        assertEquals(listOf(DebridStreamLanguage.EN), languagesOf("Movie.2024.1080p.BluRay.ENG.x264-GRP.mkv"))
    }

    @Test
    fun titleWordsAreNotReadAsTheLanguage() {
        assertEquals(
            emptyList(),
            languagesOf("The.French.Dispatch.2021.1080p.BluRay.x264-GRP.mkv", title = "The French Dispatch"),
        )
        assertEquals(
            listOf(DebridStreamLanguage.EN),
            languagesOf("The.Italian.Job.2003.1080p.BluRay.ENG.x264-GRP.mkv", title = "The Italian Job"),
        )
        assertEquals(
            listOf(DebridStreamLanguage.IT),
            languagesOf("The Italian Job (2003) 1080p BluRay ITA AC3.mkv", title = "The Italian Job"),
        )
        // The title is taken out once; the second "French" is the language.
        assertEquals(
            listOf(DebridStreamLanguage.FR),
            languagesOf("French.2020.FRENCH.1080p.WEB-DL.x264-GRP.mkv", title = "French"),
        )
    }
}
