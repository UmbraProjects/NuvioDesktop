package com.nuvio.app.features.usenet

import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.isScorableStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsenetStreamFormatTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun release(name: String, size: Long = 0) = StreamItem(
        name = "Usenet",
        addonName = BuiltInUsenet.GROUP_NAME,
        addonId = BuiltInUsenet.GROUP_ID,
        behaviorHints = StreamBehaviorHints(filename = name, videoSize = size.takeIf { it > 0 }),
        nzbUrl = "https://indexer.example/getnzb/1",
    )

    @Test
    fun formatsAFilmLikeTheAioStreamsRows() {
        val stream = UsenetStreamFormat.format(
            release("Midsommar.2019.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HDR10P.HEVC.HYBRID.REMUX-FraMeSToR", size = 78L shl 30),
            subject = UsenetStreamFormat.Subject("Midsommar", 2019, series = false),
            indexerName = "AltHub",
            publishedAt = now - 280 * day,
            showReleaseName = false,
            now = now,
        )
        assertEquals("📀 2160p", stream.name)
        val lines = stream.description!!.lines()
        assertEquals(3, lines.size, stream.description)
        assertEquals("⚡Midsommar | 2019", lines[0])
        assertEquals("🌞 DV · HDR | 9 months | AltHub", lines[1])
        assertTrue(lines[2].startsWith("🥇 FraMeSToR 🎧 DTS-HD MA"), lines[2])
        assertTrue(lines[2].endsWith("5.1"), lines[2])
        assertTrue(" • DTS-HD •" !in lines[2], lines[2])
    }

    @Test
    fun anEpisodeShowsSeasonAndEpisodeAndAnUnknownGroupIsANinja() {
        val stream = UsenetStreamFormat.format(
            release("Some.Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NOBODYHEARD"),
            subject = UsenetStreamFormat.Subject("A Very Long Series Title That Goes On", 2020, series = true),
            indexerName = "NZBgeek",
            publishedAt = 0,
            showReleaseName = true,
            now = now,
        )
        assertEquals("🌐 1080p", stream.name)
        val lines = stream.description!!.lines()
        assertEquals("⚡A Very Long Series Title… | S01 E02", lines[0])
        assertEquals("🌑 SDR | NZBgeek", lines[1])
        assertTrue(lines[2].startsWith("🥷 NOBODYHEARD"), lines[2])
        assertEquals("Some.Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NOBODYHEARD", lines.last())
    }

    @Test
    fun releaseGroups() {
        assertEquals("FraMeSToR", UsenetStreamFormat.releaseGroup("Film.2019.2160p.DTS-HD.MA.5.1.REMUX-FraMeSToR"))
        assertEquals("NTb", UsenetStreamFormat.releaseGroup("Show.S01E01.1080p.WEB-DL-NTb.mkv"))
        assertEquals(null, UsenetStreamFormat.releaseGroup("Film.2019.1080p.BluRay.x264"))
        // A Sonarr rename: the group is the last bracket, not "1080p]" after the dash in [Bluray-1080p].
        assertEquals("PFa", UsenetStreamFormat.releaseGroup("Wentworth (2013) (264234) - S06E02 - The Boxer - [Bluray-1080p] [AAC] [PFa]"))
        // Spaces and no group dash: the word after the codec, not "DL" from WEB-DL; trailing language words skipped.
        assertEquals("NTb", UsenetStreamFormat.releaseGroup("Wentworth S06 1080p NF WEB-DL DD2.0 x264 NTb NL sub"))
        assertEquals("GRP", UsenetStreamFormat.releaseGroup("Movie.2020.1080p.WEB-DL-GRP [rarbg]"))
        assertEquals(null, UsenetStreamFormat.releaseGroup("Movie 2020 1080p WEB-DL"))
        assertEquals("Ben.The.Men", UsenetStreamFormat.releaseGroup("Other.Mommy.2026.2160p.AMZN.WEB-DL.DV.HDR10+-Ben.The.Men"))
        assertEquals("SumVision", UsenetStreamFormat.releaseGroup("Midsommar.2019.Directors.Cut.2160p.UHD.Blu-ray.Remux.HEVC.DTS-HD.MA.5.1.Single.Layer.DoVi.SumVision"))
        assertEquals("HONE", UsenetStreamFormat.releaseGroup("Midsommar (2019) Director's Cut (2160p UHD BluRay x265 DV HDR DD 5.1 English - Weasley HONE)"))
        assertEquals("KRaLiMaRKo", UsenetStreamFormat.releaseGroup("Midsommar.2019.Directors.Cut.2160p.UHD.Blu-ray.Remux.HEVC.DTS-HD.MA.5.1.KRaLiMaRKo"))
        assertEquals("x0r", UsenetStreamFormat.releaseGroup("Midsommar.2019.720p.BluRay.X264-x0r[FEATUETTE-Let the Festivities Begin: Manifesting Midsommar]"))
        assertEquals(null, UsenetStreamFormat.releaseGroup("Movie.Title.2020.1080p.BluRay.x264"))
    }

    @Test
    fun bestAudioDropsTheTagsItContains() {
        assertEquals(
            listOf("DTS-HD MA"),
            UsenetStreamFormat.bestAudio(listOf(DebridStreamAudioTag.DTS_HD_MA, DebridStreamAudioTag.DTS_HD, DebridStreamAudioTag.DTS)),
        )
        assertEquals(
            listOf("Atmos", "TrueHD"),
            UsenetStreamFormat.bestAudio(listOf(DebridStreamAudioTag.ATMOS, DebridStreamAudioTag.TRUEHD)),
        )
    }

    @Test
    fun usenetRowsAreScored() {
        assertTrue(release("Movie.2024.1080p.WEB-DL").isScorableStream)
    }

    @Test
    fun ageLabels() {
        assertEquals(null, UsenetStreamFormat.ageLabel(0, now))
        assertEquals("today", UsenetStreamFormat.ageLabel(now - 1000, now))
        assertEquals("1 day", UsenetStreamFormat.ageLabel(now - day, now))
        assertEquals("4 months", UsenetStreamFormat.ageLabel(now - 125 * day, now))
        assertEquals("3 years", UsenetStreamFormat.ageLabel(now - 1100 * day, now))
    }
}
