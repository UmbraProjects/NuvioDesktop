package com.spydermahn.nuvioremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBadgesTest {
    @Test
    fun readsResolutionHdrAndCodecFromTheReleaseName() {
        val facts = SourceBadges.read("Add-on\n4K HDR", "Some.Show.S03E04.2160p.WEB-DL.DV.HEVC.mkv\n💾 18.4 GB")
        assertEquals("4K", facts.resolution?.label)
        assertEquals("Dolby Vision", facts.hdr?.label)
        assertEquals("HEVC", facts.codec?.label)
    }

    @Test
    fun plainReleasesGetOnlyWhatTheyState() {
        val facts = SourceBadges.read("Add-on 1080p", "Show.1080p.BluRay.x264.mkv")
        assertEquals("1080p", facts.resolution?.label)
        assertNull(facts.hdr)
        assertEquals("H.264", facts.codec?.label)
    }

    @Test
    fun anUnreadableSourceHasNoBadges() {
        val facts = SourceBadges.read("Mystery", null)
        assertTrue(facts.all.isEmpty())
    }

    @Test
    fun hdrFilterAlsoCatchesDolbyVision() {
        val dv = SourceBadges.read("x", "Show.2160p.DoVi.mkv")
        assertTrue(SourceBadges.matches(dv, SourceBadges.HDR))
        assertTrue(SourceBadges.matches(dv, SourceBadges.DOLBY_VISION))
        val plain = SourceBadges.read("x", "Show.1080p.mkv")
        assertTrue(!SourceBadges.matches(plain, SourceBadges.HDR))
    }

    @Test
    fun releaseNameDropsTheEmojiLines() {
        assertEquals("Show.1080p.mkv", SourceBadges.releaseName("Show.1080p.mkv\n💾 9.1 GB  👤 88"))
        assertNull(SourceBadges.releaseName("💾 9.1 GB"))
    }
}
