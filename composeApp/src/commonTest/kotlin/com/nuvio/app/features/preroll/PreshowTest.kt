package com.nuvio.app.features.preroll

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreshowTest {
    @Test
    fun mapsMpaaAndTvRatingsToBbfc() {
        assertEquals("U", normalizeCertification("G", RatingRegion.Bbfc))
        assertEquals("12A", normalizeCertification("PG-13", RatingRegion.Bbfc))
        assertEquals("15", normalizeCertification("R", RatingRegion.Bbfc))
        assertEquals("18", normalizeCertification("TV-MA", RatingRegion.Bbfc))
        assertEquals("12A", normalizeCertification("12", RatingRegion.Bbfc))
    }

    @Test
    fun mapsBbfcToMpaa() {
        assertEquals("PG-13", normalizeCertification("12A", RatingRegion.Mpaa))
        assertEquals("R", normalizeCertification("15", RatingRegion.Mpaa))
        assertEquals("NC-17", normalizeCertification("18", RatingRegion.Mpaa))
        assertEquals("R", normalizeCertification("TV-MA", RatingRegion.Mpaa))
    }

    @Test
    fun unknownCertificateIsNull() {
        assertNull(normalizeCertification(null, RatingRegion.Bbfc))
        assertNull(normalizeCertification("  ", RatingRegion.Bbfc))
        assertNull(normalizeCertification("Not Rated", RatingRegion.Bbfc))
    }

    @Test
    fun certificateMatchDoesNotConfusePgWithPg13() {
        assertTrue(mentionsCertificate("bbfc-pg", "PG"))
        assertFalse(mentionsCertificate("PG-13", "PG"))
        assertTrue(mentionsCertificate("12A rating", "12A"))
        assertFalse(mentionsCertificate("125", "12"))
    }

    @Test
    fun parsesEmbeddedYoutubeVideosWithTheirContext() {
        val html = """
            <h3>12A rating</h3><iframe src="https://www.youtube.com/embed/aaaaaaaaaaa"></iframe>
            <h3>15 rating</h3><iframe src="https://www.youtube-nocookie.com/embed/bbbbbbbbbbb"></iframe>
            <a href="https://youtu.be/aaaaaaaaaaa">again</a>
        """.trimIndent()
        val clips = parseAvidClips(html)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), clips.map { it.videoId })
        assertEquals("bbbbbbbbbbb", selectRatingClip(clips, "15")?.videoId)
        assertNull(selectRatingClip(clips, "U"))
    }

    @Test
    fun orderKeepsSavedSequenceAndAddsMissingSlots() {
        val order = PreshowSettings.parseOrder("bumper,trivia,bogus,bumper")
        assertEquals(listOf(PreshowSlot.Bumper, PreshowSlot.Trivia), order.take(2))
        assertEquals(PreshowSlot.entries.toSet(), order.toSet())
        assertEquals(PreshowSlot.entries.size, order.size)
    }

    @Test
    fun ratingCardTitleCarriesTheYear() {
        assertEquals("Halloween (2018)", titleWithYear("Halloween", "2018"))
        assertEquals("Halloween (2018)", titleWithYear("Halloween", "2018-10-19"))
        assertEquals("Halloween (2018)", titleWithYear("Halloween (2018)", "2018"))
        assertEquals("Halloween", titleWithYear("Halloween", null))
        assertEquals("Halloween", titleWithYear("Halloween", "soon"))
    }
}
