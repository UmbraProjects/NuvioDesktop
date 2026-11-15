package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvLanguagesTest {
    private val playlist = IptvPlaylist(id = "p", type = IptvPlaylistType.M3u, name = "x", url = "http://x/list.m3u")

    @Test
    fun `a channel's own name decides, then its category, then its country`() {
        assertEquals("es", IptvLanguages.ofChannel("US: Univision HD", "US | Entertainment", "US"))
        assertEquals("es", IptvLanguages.ofChannel("US: ESPN Deportes", "US | Sports", "US"))
        assertEquals("es", IptvLanguages.ofChannel("US: Discovery", "US | Latino", "US"))
        assertEquals("es", IptvLanguages.ofChannel("CNN en Español", "US | News", "US"))
        assertEquals("en", IptvLanguages.ofChannel("US: ESPN", "US | Sports", "US"))
        assertEquals("en", IptvLanguages.ofChannel("Al Jazeera English", "Arabic | News", "QA"))
        assertEquals("sa", IptvLanguages.ofChannel("UK: Zee TV", "UK | Asian", "GB"))
        assertEquals("en", IptvLanguages.ofChannel("UK: BBC One", "UK | Asian", "GB"))
        assertEquals("fr", IptvLanguages.ofChannel("CA: TVA Montreal", "CA | General", "CA"))
        assertEquals("es", IptvLanguages.ofChannel("ES: La 1", "ES | General", "ES"), "the ES prefix is Spain, and Spain is Spanish")
        assertEquals(IptvLanguages.OTHER, IptvLanguages.ofChannel("Channel 9", "VIP", null))
    }

    @Test
    fun `English-only US - Spanish channels go, even from an English category, and an all-Spanish category goes too`() {
        val categories = listOf(IptvCategory("us-ent", "US | Entertainment"), IptvCategory("us-lat", "US | Latino"), IptvCategory("us-news", "US | News"))
        val channels = listOf(
            IptvChannel(uid = 1, key = "a", num = 1, name = "US: ABC", categoryId = "us-ent"),
            IptvChannel(uid = 2, key = "b", num = 2, name = "US: Univision", categoryId = "us-ent"),
            IptvChannel(uid = 3, key = "c", num = 3, name = "US: Discovery", categoryId = "us-lat"),
            IptvChannel(uid = 4, key = "d", num = 4, name = "US: CNN", categoryId = "us-news"),
            IptvChannel(uid = 5, key = "e", num = 5, name = "US: Telemundo", categoryId = "us-news"),
        )
        val content = IptvPlaylistContent.build(playlist, categories, channels)
        val regions = mapOf("us-ent" to "US", "us-lat" to "US", "us-news" to "US")

        val options = IptvTriageRules.languageOptions(content, regions, setOf("US"))
        assertEquals(listOf("es" to 3, "en" to 2), options.map { it.id to it.channels })
        assertEquals(setOf("en"), IptvTriageRules.defaultLanguages(setOf("US", "GB")))

        val kept = IptvTriageRules.keptCategories(content, IptvTriage(setOf("US"), setOf("Entertainment", "News"), setOf("en")), regions)
        assertEquals(setOf("us-ent", "us-news"), kept, "US | Latino has nothing English left in it")
        assertTrue(kept.keeps(channels[0]))
        assertFalse(kept.keeps(channels[1]), "Univision is left out of US | Entertainment")
        assertFalse(kept.keeps(channels[4]))
        assertEquals(2, IptvTriageRules.channelCount(content, kept))

        // A triage saved before the languages question keeps every language.
        val old = IptvTriageRules.keptCategories(content, IptvTriage(setOf("US"), setOf("Entertainment", "News")), regions)
        assertTrue(old.keeps(channels[1]))
    }
}
