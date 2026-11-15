package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class IptvChannelMergeTest {
    private fun channel(uid: Int, name: String, category: String = "c") =
        IptvChannel(uid = uid, key = "k$uid", num = uid, name = name, categoryId = category)

    @Test
    fun `copies of one channel become one row, best quality first`() {
        val merged = IptvChannelMerge.merge(
            listOf(
                channel(1, "BBC One SD"),
                channel(2, "BBC One HD"),
                channel(3, "BBC One FHD"),
                channel(4, "BBC One (backup)"),
                channel(5, "BBC Two HD"),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(listOf("BBC One FHD", "BBC One HD", "BBC One SD", "BBC One (backup)"), merged[0].sources.map { it.name })
        assertEquals("FHD · 4 sources", merged[0].sourcesLabel())
        assertEquals("HD", merged[1].sourcesLabel())
    }

    @Test
    fun `a region prefix and raised letters do not split a channel`() {
        assertEquals(IptvChannelMerge.mergeKey("UK: BBC One HD", null), IptvChannelMerge.mergeKey("UK | BBC One ᶠᴴᴰ", null))
        assertEquals(IptvChannelMerge.mergeKey("[UK] Sky Sports F1 HEVC", null), IptvChannelMerge.mergeKey("UK - Sky Sports F1 1080p", null))
        // The category's region stands in for a missing prefix.
        assertEquals(IptvChannelMerge.mergeKey("UK: ITV1 HD", "GB"), IptvChannelMerge.mergeKey("ITV 1", "GB"))
    }

    @Test
    fun `different channels stay apart`() {
        assertNotEquals(IptvChannelMerge.mergeKey("BBC One HD", null), IptvChannelMerge.mergeKey("BBC One +1 HD", null))
        assertNotEquals(IptvChannelMerge.mergeKey("UK: Eurosport 1", null), IptvChannelMerge.mergeKey("DE: Eurosport 1", null))
        assertNotEquals(IptvChannelMerge.mergeKey("Sky Sports Main Event HD", null), IptvChannelMerge.mergeKey("Sky Sports Main Event UHD", null))
        assertNotEquals(IptvChannelMerge.mergeKey("BBC One", "GB"), IptvChannelMerge.mergeKey("BBC One", "US"))
    }

    @Test
    fun `a channel with no real name is never merged`() {
        assertNull(IptvChannelMerge.mergeKey("", null))
        assertNull(IptvChannelMerge.mergeKey("HD", null))
        assertNull(IptvChannelMerge.mergeKey("#####", null))
        assertEquals(2, IptvChannelMerge.merge(listOf(channel(1, ""), channel(2, ""))).size)
    }

    @Test
    fun `order follows where each channel first appears`() {
        val merged = IptvChannelMerge.merge(listOf(channel(1, "ITV1 SD"), channel(2, "BBC One"), channel(3, "ITV1 FHD")))
        assertEquals(listOf("ITV1 FHD", "BBC One"), merged.map { it.primary.name })
        assertEquals(listOf(channel(1, "ITV1 SD")), merged[0].alternates)
    }

    @Test
    fun `quality labels`() {
        assertEquals("FHD", IptvChannelMerge.qualityLabel("Film4 1080p"))
        assertEquals("4K", IptvChannelMerge.qualityLabel("Sky Sports UHD"))
        assertNull(IptvChannelMerge.qualityLabel("Film4"))
        // "HD" inside another word is not a quality.
        assertNull(IptvChannelMerge.qualityLabel("Shdow TV"))
    }
}
