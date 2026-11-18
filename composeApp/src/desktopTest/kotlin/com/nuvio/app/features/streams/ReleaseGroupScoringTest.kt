package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scoring reads a release's group the way its row shows it (ReleaseGroupName); the detector works in lower case. */
class ReleaseGroupScoringTest {
    private fun traitsOf(filename: String) = StreamTraitDetector.detect(
        StreamItem(name = "Usenet", addonName = "Built-in Usenet", addonId = "usenet:built-in",
            behaviorHints = StreamBehaviorHints(filename = filename, videoSize = 80_000_000_000L)),
    )

    @Test
    fun `a sonarr renamed release gets its group's tier`() {
        val tier = StreamReleaseGroups.tierOf("FraMeSToR")
        assertTrue(tier != null, "FraMeSToR is on the tier lists")
        val traits = traitsOf("Midsommar (2019) - [Remux-2160p] [DTS-HD MA 5.1] [FraMeSToR]")
        assertEquals("framestor", traits.releaseGroup.lowercase())
        assertEquals(tier, traits.groupTier)
        assertTrue(traits.isTrustedGroup)
    }

    @Test
    fun `groups written several ways are read for scoring`() {
        assertEquals("kralimarko", traitsOf("Midsommar.2019.Directors.Cut.2160p.UHD.Blu-ray.Remux.HEVC.DTS-HD.MA.5.1.KRaLiMaRKo").releaseGroup.lowercase())
        assertEquals("ntb", traitsOf("Wentworth S06 1080p NF WEB-DL DD2.0 x264 NTb NL sub").releaseGroup.lowercase())
        assertEquals("ben.the.men", traitsOf("Other.Mommy.2026.2160p.AMZN.WEB-DL.DV.HDR10+-Ben.The.Men").releaseGroup.lowercase())
    }

    @Test
    fun `trailing language and subtitle notes are not the group`() {
        assertEquals("XEBEC", ReleaseGroupName.of(
            "Black.Clover.Sword.of.the.Wizard.King.2023.1080p.NF.WEB-DL.DDP5.1.H.264-XEBEC.[Japanese.-.English].[Multiple.Subtitle]"))
        assertEquals("XEBEC", ReleaseGroupName.of("Show.S01E02.1080p.WEB-DL.x264-XEBEC [Multi-Subs]"))
        assertNotEquals("Multiple.Subtitle", ReleaseGroupName.of("Show.S01E02.1080p.WEB.[Multiple.Subtitle]"))
        // A real group in brackets stays the group.
        assertEquals("PFa", ReleaseGroupName.of("Show - S01E02 - Title [Bluray-1080p] [AAC] [PFa]"))
    }

    @Test
    fun `fansub names lead with their group`() {
        assertEquals("NoobSubs", ReleaseGroupName.of("[NoobSubs] Violet Evergarden 01-13 + OVA (720p Blu-ray 8bit AAC MP4)"))
        assertEquals("HorribleSubs", ReleaseGroupName.of("[HorribleSubs] Violet Evergarden - 01 [1080p]"))
        assertEquals("Fall-VS", ReleaseGroupName.of("[Fall-VS] Violet Evergarden 01 [VOSTFR 1080p GammaFixed]"))
        assertEquals("TheComicsHQ", ReleaseGroupName.of("[TheComicsHQ] Violet Evergarden 01 [BD 1920x1080 x265 10Bit AC3]"))
        assertEquals("Samir755", ReleaseGroupName.of("[Samir755] Violet Evergarden - 01 - I Love You and Auto Memory Dolls"))
        assertEquals("SubsPlease", ReleaseGroupName.of("[SubsPlease] Jujutsu Kaisen - 29 (1080p) [A1B2C3D4].mkv"))
        assertNull(ReleaseGroupName.of("Violet Evergarden - 01 (1280x720 HEVC2 EAC3)"))
    }

    @Test
    fun `versions, checksums, multisub and raws are read the way groups use them`() {
        assertEquals("Erai-raws", ReleaseGroupName.of("Erai-raws horemajo - 01 1080p CR WEB-DL AVC AAC MultiSub DD7757A3"))
        assertEquals("SubsPlease", ReleaseGroupName.of("SubsPlease Dragon Ball Super - Beerus - 01 720p EABC6046"))
        assertEquals("Mr.Yosha", ReleaseGroupName.of("Dragon.Ball.Super.Beerus.S01E01.CR.WEB.1080p.DDP.2.0.x264.Mr.Yosha.v2"))
        assertEquals("Tsundere-Raws", ReleaseGroupName.of("Dragon Ball Super Beerus S01E01 1080p AMZN WEB-DL DDP2.0 H.265-Tsundere-Raws"))
        assertEquals("H3LL", ReleaseGroupName.of("Dragon Ball Super Beerus - S01E02 [H3LL][1080p][x264][10bit][AAC][Multi-Subs]"))
        assertEquals("NTb", ReleaseGroupName.of("Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTb-RP"))
        assertEquals("NTb", ReleaseGroupName.of("Show.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTb.v2"))
    }

    @Test
    fun `a group written before the title`() {
        assertEquals("MegaMTBB", ReleaseGroupName.of("MegaMTBB-Kimi no Na wa.2016-BD 1080p-CE49A996"))
        assertEquals("izzybell", ReleaseGroupName.of("izzybell-Your Name.2016-1080p-Dual-Audio-DCP + BD-x265-FLAC"))
        assertNull(ReleaseGroupName.of("Spider-Man.2002.1080p.BluRay.x264"))
    }

    @Test
    fun `quotes, extensions, part numbers and stray dashes are not the group`() {
        assertEquals("BiOMA", ReleaseGroupName.of("[1 10] 'Violet.Evergarden.S01E01.1080p.NF.WEB-DL.AAC2.0.H.264.DUAL-BiOMA.mkv'"))
        assertEquals("ION265", ReleaseGroupName.of("Game.of.Thrones.S01E01.BDRip.x265-ION265_mp4"))
        assertEquals("REWARD", ReleaseGroupName.of("Game.of.Thrones.S01E01.720p.BluRay.X264-REWARD-1"))
        assertEquals("xsp", ReleaseGroupName.of("Game.of.Thrones.S01.EXTRAS.1080p.BluRay.H264-RMXTRAS-xsp-"))
        assertNull(ReleaseGroupName.of("Game.of.Thrones.S01E01.Winter.Is.Coming.1080p.MAX.WEB-DL.DDP5.1.Atmos.H.264-"))
        assertNull(ReleaseGroupName.of("Game.of.Thrones.S01.EXTRAS.720p.BluRay.x264"))
        assertNull(ReleaseGroupName.of("Game.of.Thrones.Season.01.480p.HDTV.H264-20-40"))
        assertNull(ReleaseGroupName.of("Game of thrones S01 S05 mp4 Game Of Thrones-S01 D01 MP4 2011-Winter Is Coming-1"))
    }
}
