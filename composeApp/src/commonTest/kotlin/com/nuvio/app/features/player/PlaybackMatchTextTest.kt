package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamBadgeFilter
import com.nuvio.app.features.streams.StreamBadgeImport
import com.nuvio.app.features.streams.StreamBadgeRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackMatchTextTest {

    @Test
    fun `4k dolby vision atmos reads like a release name`() {
        val text = playbackMatchText(
            PlayingMediaFacts(
                width = 3840,
                height = 1600,
                isHdr = true,
                videoCodec = "hevc",
                dolbyVisionProfile = 8,
                audioCodec = "truehd",
                audioProfile = "Dolby TrueHD + Dolby Atmos",
                audioChannels = 8,
                audioLanguage = "eng",
            ),
        )
        listOf("2160p", "4K", "HEVC", "x265", "DV", "HDR10", "10bit", "TrueHD", "Atmos", "7.1", "eng").forEach {
            assertTrue(it in text.split(' ') || text.contains(it), "missing $it in $text")
        }
    }

    @Test
    fun `dolby vision profile 5 has no hdr10 layer`() {
        val text = playbackMatchText(PlayingMediaFacts(width = 1920, height = 1080, isHdr = true, dolbyVisionProfile = 5))
        assertTrue("DV" in text.split(' '))
        assertFalse("HDR10" in text.split(' '))
    }

    @Test
    fun `dts profiles add their own tokens`() {
        val text = playbackMatchText(PlayingMediaFacts(audioCodec = "dts", audioProfile = "DTS-HD MA", audioChannels = 6))
        assertTrue(text.contains("DTS-HD MA"))
        assertTrue(text.contains("5.1"))
    }

    private fun pack(url: String, vararg filters: Pair<String, String>) = StreamBadgeImport(
        sourceUrl = url,
        filters = filters.map { (name, pattern) ->
            StreamBadgeFilter(id = name, name = name, pattern = pattern, imageURL = "https://img.example/$name.png")
        },
    )

    @Test
    fun `player badges match case-insensitively and keep only image badges`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                pack("https://a.example/pack.json", "4K" to "\\b2160p\\b", "Atmos" to "atmos", "Remux" to "remux"),
            ),
        )
        val badges = playerBadges(
            PlayingMediaFacts(width = 3840, height = 2160, audioCodec = "eac3", audioProfile = "Dolby Digital Plus + Dolby Atmos"),
            rules,
        )
        assertEquals(listOf("4K", "Atmos"), badges.map { it.name })
    }

    @Test
    fun `the player can use another imported pack`() {
        val rules = StreamBadgeRules(
            imports = listOf(
                pack("https://a.example/streams.json", "Stream" to "2160p").copy(isActive = true),
                pack("https://b.example/player.json", "Player" to "2160p").copy(isActive = false),
            ),
        )
        val facts = PlayingMediaFacts(width = 3840, height = 2160)
        assertEquals(listOf("Stream"), playerBadges(facts, rules.forPlayer("")).map { it.name })
        assertEquals(listOf("Player"), playerBadges(facts, rules.forPlayer("https://b.example/player.json")).map { it.name })
    }
}
