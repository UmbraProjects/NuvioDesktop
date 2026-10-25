package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BingeReleaseMatchTest {

    @Test
    fun `next filename keeps the release and its number widths`() {
        assertEquals(
            "Show.S01E04.1080p.WEB-DL.DDP5.1.H.264-GRP.mkv",
            FilenameEpisodeMatcher.buildNextFilename("Show.S01E03.1080p.WEB-DL.DDP5.1.H.264-GRP.mkv", 1, 3, 1, 4),
        )
        assertEquals("Show 1x10 [720p].mkv", FilenameEpisodeMatcher.buildNextFilename("Show 1x09 [720p].mkv", 1, 9, 1, 10))
        assertEquals("Anime - E013 [1080p].mkv", FilenameEpisodeMatcher.buildNextFilename("Anime - E012 [1080p].mkv", null, 12, null, 13))
    }

    @Test
    fun `resolution is not read as season by episode`() {
        assertEquals(null to 5, FilenameEpisodeMatcher.extractSeasonEpisode("Show 1920x1080 EP05.mkv"))
    }

    @Test
    fun `a filename for a different episode is not rewritten`() {
        assertNull(FilenameEpisodeMatcher.buildNextFilename("Show.S01E03.mkv", 1, 5, 1, 6))
    }

    @Test
    fun `hint aims the last filename at the requested episode`() {
        val hint = BingeReleaseHint.forEpisode("Show.S02E07.2160p.WEB.H265-GRP.mkv", null, season = 2, episode = 8)
        assertEquals("Show.S02E08.2160p.WEB.H265-GRP.mkv", hint?.expectedFilename)
        assertNull(BingeReleaseHint.forEpisode(null, " ", 1, 1))
    }

    private fun row(name: String, filename: String?, hash: String? = null) = StreamItem(
        name = name,
        url = "https://host.example/$name.mkv",
        infoHash = hash,
        addonName = "Addon",
        addonId = "addon:a:u",
        behaviorHints = StreamBehaviorHints(bingeGroup = "addon|1080p", filename = filename),
    )

    private fun select(streams: List<StreamItem>, hint: BingeReleaseHint?, bingeGroupOnly: Boolean) =
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = streams,
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "addon|1080p",
            preferBingeGroupInSelection = true,
            bingeGroupOnly = bingeGroupOnly,
            preferredRelease = hint,
        )

    @Test
    fun `the same release wins within the binge group`() {
        val other = row("other", "Show.S01E04.1080p.WEB-DL-OTHER.mkv")
        val same = row("same", "Show.S01E04.1080p.WEB-DL-GRP.mkv")
        val hint = BingeReleaseHint.forEpisode("Show.S01E03.1080p.WEB-DL-GRP.mkv", null, 1, 4)
        assertEquals(same, select(listOf(other, same), hint, bingeGroupOnly = true))
        assertEquals(same, select(listOf(other, same), hint, bingeGroupOnly = false))
        // Without a hint the group's first row is picked, as before.
        assertEquals(other, select(listOf(other, same), null, bingeGroupOnly = true))
    }

    @Test
    fun `the same torrent is the fallback when no filename matches`() {
        val hash = "a".repeat(40)
        val other = row("other", "x.mkv", hash = "b".repeat(40))
        val pack = row("pack", "y.mkv", hash = hash)
        val hint = BingeReleaseHint.forEpisode("Show.S01E03.mkv", hash, 1, 4)
        assertEquals(pack, select(listOf(other, pack), hint, bingeGroupOnly = true))
    }
}
