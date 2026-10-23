package com.nuvio.app.features.iptv

import com.nuvio.app.features.player.iptvSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IptvRegionsTest {

    @Test
    fun `reads the usual provider prefixes`() {
        assertEquals("GB", IptvRegions.regionOf("UK | Sports"))
        assertEquals("GB", IptvRegions.regionOf("|UK| ENTERTAINMENT"))
        assertEquals("DE", IptvRegions.regionOf("[DE] Kino"))
        assertEquals("FR", IptvRegions.regionOf("FR: Info"))
        assertEquals("US", IptvRegions.regionOf("USA Movies"))
        assertEquals("US", IptvRegions.regionOf("US - News"))
        assertEquals("ES", IptvRegions.regionOf("ES News"))
    }

    @Test
    fun `ignores words that only look like a code`() {
        assertNull(IptvRegions.regionOf("Sports"))
        assertNull(IptvRegions.regionOf("TV Shows"))
        assertNull(IptvRegions.regionOf("HD Movies"))
        assertNull(IptvRegions.regionOf("Uk news")) // a sentence, not a prefix
        assertNull(IptvRegions.regionOf("XX | Unknown"))
    }

    @Test
    fun `a region needs two categories and sorts by size`() {
        val playlist = IptvPlaylist(id = "p", type = IptvPlaylistType.M3u, name = "P", url = "http://h/l.m3u")
        val categories = listOf(
            IptvCategory("1", "UK | Sports"),
            IptvCategory("2", "UK | News"),
            IptvCategory("3", "UK | Kids"),
            IptvCategory("4", "DE | Sport"),
            IptvCategory("5", "DE | Nachrichten"),
            IptvCategory("6", "FR | Info"),
        )
        val channels = categories.mapIndexed { i, c -> IptvChannel(uid = i, key = "k$i", num = i + 1, name = "C$i", categoryId = c.id) }
        val content = IptvPlaylistContent.build(playlist, categories, channels)
        val byCategory = IptvRegions.categoryRegions(content)
        assertEquals(listOf("GB", "DE"), IptvRegions.regions(byCategory))
        assertNull(byCategory["6"]) // a lone French category grows no flag
        assertEquals("UK", IptvRegions.label("GB"))
    }

    @Test
    fun `channel search matches every word or the number, name starts first`() {
        fun ch(num: Int, name: String) = IptvChannel(uid = num, key = "k$num", num = num, name = name, categoryId = "c")
        val channels = listOf(ch(1, "UK: BBC One HD"), ch(2, "BBC Two"), ch(3, "Sky Sports News"), ch(101, "BBC News"))
        assertEquals(listOf(2, 101, 1), iptvSearch(channels, "bbc").map { it.num })
        assertEquals(listOf(101), iptvSearch(channels, "bbc news").map { it.num })
        assertEquals(listOf(3), iptvSearch(channels, "3").map { it.num })
        assertEquals(emptyList(), iptvSearch(channels, "  "))
    }
}
