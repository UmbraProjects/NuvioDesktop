package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamAudioChannel
import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.debrid.DebridStreamEncode
import com.nuvio.app.features.debrid.DebridStreamQuality
import com.nuvio.app.features.debrid.DebridStreamResolution
import com.nuvio.app.features.debrid.DebridStreamVisualTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Release-name spellings checked against how Sonarr/Radarr parse them and how TRaSH's custom formats
 * match them. Each case is a name form that used to fall through to UNKNOWN (dropping points) or
 * match the wrong tag (adding them).
 */
class StreamReleaseNameParsingTest {

    private fun traits(name: String): StreamTraits =
        StreamTraitDetector.detect(
            StreamItem(
                name = name,
                addonName = "addon",
                addonId = "addon",
                url = "http://example.invalid/${name.hashCode()}",
                behaviorHints = StreamBehaviorHints(filename = name),
            ),
        )

    // --- Source ---

    @Test
    fun bareSceneWebIsWebDl() {
        assertEquals(DebridStreamQuality.WEB_DL, traits("Show.S01E01.1080p.WEB.h264-ETHEL").quality)
        assertEquals(DebridStreamQuality.WEB_DL, traits("Movie.2024.2160p.AMZN.WEB.DDP5.1.H.265-NTb").quality)
        assertEquals(DebridStreamQuality.WEB_DL, traits("Movie.2024.1080p.WEB-GROUP.mkv").quality)
        assertEquals(DebridStreamQuality.WEB_DL, traits("[SubsPlease] Show - 01 (WEB 1080p) [ABCD1234].mkv").quality)
    }

    @Test
    fun webRipAndWebDlKeepTheirOwnSource() {
        assertEquals(DebridStreamQuality.WEBRIP, traits("Movie.2024.1080p.WEB-Rip.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.WEBRIP, traits("Movie.2024.1080p.WEBRip.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.WEB_DL, traits("Movie.2024.1080p.WEB-DL.x264-GROUP").quality)
    }

    @Test
    fun webInATitleIsNotASource() {
        assertEquals(DebridStreamQuality.UNKNOWN, traits("Charlotte's Web 2006 1080p").quality)
        assertEquals(DebridStreamQuality.DVDRIP, traits("Charlottes.Web.1973.DVDRip.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.UNKNOWN, traits("Movie.2024.1080p.webm").quality)
    }

    @Test
    fun animeBdTagIsBluray() {
        assertEquals(DebridStreamQuality.BLURAY, traits("[Judas] Show - 01 (BD 1080p) [HEVC].mkv").quality)
    }

    @Test
    fun spelledOutPreReleaseSourcesAreJunk() {
        assertEquals(DebridStreamQuality.CAM, traits("Movie.2024.HDCAM.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.CAM, traits("Movie.2024.720p.CAMRip.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.TS, traits("Movie.2024.1080p.HDTS.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.TS, traits("Movie.2024.TELESYNC.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.SCR, traits("Movie.2024.DVDScr.x264-GROUP").quality)
        assertEquals(DebridStreamQuality.HDTV, traits("Show.S01E01.720p.HDTV.x264-GROUP").quality)
    }

    // --- Resolution ---

    @Test
    fun pixelCountBeatsResolutionWords() {
        assertEquals(DebridStreamResolution.P1080, traits("Movie.2019.1080p.UHD.BluRay.x264-GROUP").resolution)
        assertEquals(DebridStreamResolution.P1080, traits("Movie.1990.1080p.4K.Remaster.BluRay.x264-GROUP").resolution)
        assertEquals(DebridStreamResolution.P2160, traits("Movie.2024.UHD.BluRay.x265-GROUP").resolution)
    }

    @Test
    fun dtsHdIsNotA720pTag() {
        assertEquals(DebridStreamResolution.UNKNOWN, traits("Movie.2024.BluRay.DTS-HD.MA.x264-GROUP").resolution)
        assertEquals(DebridStreamResolution.P720, traits("Movie 2024 HD BluRay").resolution)
    }

    // --- Audio ---

    @Test
    fun audioCodecsGluedToTheLayoutAreDetected() {
        val ddp = traits("Movie.2024.1080p.WEB-DL.DDP5.1.H.264-GROUP")
        assertTrue(DebridStreamAudioTag.DD_PLUS in ddp.audioTags)
        assertTrue(DebridStreamAudioChannel.CH_5_1 in ddp.audioChannels)

        val dd = traits("Movie.2024.1080p.BluRay.DD5.1.x264-GROUP")
        assertTrue(DebridStreamAudioTag.DD in dd.audioTags)
        assertTrue(DebridStreamAudioChannel.CH_5_1 in dd.audioChannels)

        val aac = traits("Show.S01E01.1080p.WEB-DL.AAC2.0.H.264-GROUP")
        assertTrue(DebridStreamAudioTag.AAC in aac.audioTags)
        assertTrue(DebridStreamAudioChannel.CH_2_0 in aac.audioChannels)

        assertTrue(DebridStreamAudioTag.DTS in traits("Movie.2024.1080p.BluRay.DTS5.1.x264-GROUP").audioTags)
    }

    @Test
    fun eac3IsDolbyDigitalPlusNotPlainDolbyDigital() {
        val tags = traits("Movie.2024.1080p.WEB-DL.EAC3.5.1.x264-GROUP").audioTags
        assertTrue(DebridStreamAudioTag.DD_PLUS in tags)
        assertFalse(DebridStreamAudioTag.DD in tags)
    }

    @Test
    fun dottedDtsHdMaAndDtsXAreLossless() {
        assertTrue(DebridStreamAudioTag.DTS_HD_MA in traits("Movie.2024.1080p.BluRay.DTS-HD.MA.5.1.x264-GROUP").audioTags)
        assertTrue(DebridStreamAudioTag.DTS_HD_MA in traits("Movie.2024.1080p.BluRay.DTS-HD.5.1.x264-GROUP").audioTags)
        assertTrue(DebridStreamAudioTag.DTS_X in traits("Movie.2024.2160p.BluRay.REMUX.DTS-X.7.1-GROUP").audioTags)
        assertFalse(DebridStreamAudioTag.DTS_HD_MA in traits("Movie.2024.1080p.BluRay.DTS-HD.HRA.7.1.x264-GROUP").audioTags)
        assertFalse(DebridStreamAudioTag.DTS_X in traits("Movie.2024.1080p.BluRay.DTS.x264-GROUP").audioTags)
    }

    @Test
    fun dottedTrueHdIsDetected() {
        assertTrue(DebridStreamAudioTag.TRUEHD in traits("Movie.2024.2160p.BluRay.REMUX.True.HD.7.1-GROUP").audioTags)
    }

    @Test
    fun lossyDtsRowNoLongerFiresForDottedMa() {
        val profile = StreamScoreProfile(enabled = true, points = mapOf(StreamScoreTrait.AUDIO_DTS.id to 10))
        val stream = StreamItem(
            name = "Movie.2024.1080p.BluRay.DTS-HD.MA.5.1.x264-GROUP",
            addonName = "addon",
            addonId = "addon",
            url = "http://example.invalid/dts",
        )
        val components = StreamScorer.score(stream, profile, StreamScoreContext.MOVIE).components
        assertFalse(components.any { it.trait == StreamScoreTrait.AUDIO_DTS })
    }

    // --- Codec ---

    @Test
    fun dottedH264AndH265AreDetected() {
        assertEquals(DebridStreamEncode.AVC, traits("Show.S01E01.1080p.WEB.H.264-GROUP").encode)
        assertEquals(DebridStreamEncode.HEVC, traits("Movie.2024.2160p.WEB-DL.DDP5.1.H.265-GROUP").encode)
        assertEquals(DebridStreamEncode.HEVC, traits("Movie.2024.2160p.WEB-DL.x265-GROUP").encode)
    }

    // --- HDR / AI ---

    @Test
    fun hdr10pIsHdr10Plus() {
        assertTrue(DebridStreamVisualTag.HDR10_PLUS in traits("Movie.2024.2160p.WEB-DL.HDR10P.H.265-GROUP").visualTags)
    }

    @Test
    fun bareAiInATitleIsNotAnUpscale() {
        val profile = StreamScoreProfile(enabled = true, points = mapOf(StreamScoreTrait.AI_ENHANCED.id to -10))
        val stream = StreamItem(
            name = "Ai.no.Kusabi.S01E01.1080p.BluRay.x264-GROUP",
            addonName = "addon",
            addonId = "addon",
            url = "http://example.invalid/ai",
        )
        val components = StreamScorer.score(stream, profile, StreamScoreContext.EPISODE).components
        assertFalse(components.any { it.trait == StreamScoreTrait.AI_ENHANCED })
        assertTrue(DebridStreamVisualTag.AI in traits("Movie.2024.2160p.AI.Upscaled.HDR.HEVC-GROUP").visualTags)
    }
}
