package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals

class IptvTriageTest {
    private val playlist = IptvPlaylist(id = "p", type = IptvPlaylistType.M3u, name = "x", url = "http://x/list.m3u")

    /** Categories as `id to name`, each with [perCategory] channels. */
    private fun content(vararg categories: Pair<String, String>, perCategory: Int = 2): IptvPlaylistContent {
        var uid = 0
        val channels = categories.flatMap { (id, name) ->
            (1..perCategory).map { n -> IptvChannel(uid = uid++, key = "$id-$n", num = uid, name = "$name $n", categoryId = id) }
        }
        return IptvPlaylistContent.build(playlist, categories.map { IptvCategory(it.first, it.second) }, channels)
    }

    private val sample = content(
        "uk-ent" to "UK | Entertainment", "uk-sport" to "UK | Sports", "uk-film" to "UK | Movies", "uk-kids" to "UK | Kids",
        "fr-info" to "FR | Info", "fr-cine" to "FR | Cinema", "us-news" to "US | News", "us-shop" to "US | Shopping",
        "vip" to "VIP Sports", "loops" to "24/7 Movies",
    )
    private val regions = IptvRegions.categoryRegions(sample)

    @Test
    fun `a category name says what kind it is`() {
        fun kind(name: String) = IptvTriageRules.kindOf(name)
        assertEquals(IptvChannelKind.Sport, kind("UK | Sports"))
        assertEquals(IptvChannelKind.Sport, kind("PPV Events"))
        assertEquals(IptvChannelKind.Films, kind("DE | Kino"))
        assertEquals(IptvChannelKind.News, kind("FR | Info"))
        assertEquals(IptvChannelKind.Kids, kind("Kids Movies"))
        assertEquals(IptvChannelKind.Loops, kind("24/7 Movies"))
        assertEquals(IptvChannelKind.Adult, kind("XXX | Adults 18+"))
        assertEquals(IptvChannelKind.Documentaries, kind("UK | Documentaries"))
        assertEquals(IptvChannelKind.Music, kind("Music Channels"))
        assertEquals(IptvChannelKind.Radio, kind("UK Radio"))
        assertEquals(IptvChannelKind.Shopping, kind("US | Shopping"))
        assertEquals(IptvChannelKind.Religious, kind("Religious"))
        assertEquals(IptvChannelKind.Entertainment, kind("UK | General"))
        assertEquals(IptvChannelKind.Entertainment, kind(IptvPlaylistContent.OTHER_CATEGORY))
        // "info" inside another word is not news.
        assertEquals(IptvChannelKind.Entertainment, kind("Infotainment"))
    }

    @Test
    fun `countries are offered biggest first, with no-country last`() {
        val options = IptvTriageRules.regionOptions(sample, regions)
        assertEquals(listOf("GB", "FR", "US", IptvTriageRules.NO_REGION), options.map { it.id })
        assertEquals(listOf(8, 4, 4, 4), options.map { it.channels })
    }

    @Test
    fun `kinds are counted inside the chosen countries`() {
        val uk = IptvTriageRules.kindOptions(sample, regions, setOf("GB"))
        assertEquals(listOf("Entertainment" to 2, "Sport" to 2, "Films" to 2, "Kids" to 2), uk.map { it.id to it.channels })
        val rest = IptvTriageRules.kindOptions(sample, regions, setOf("US", IptvTriageRules.NO_REGION))
        assertEquals(listOf("Sport", "News", "Shopping", "Loops"), rest.map { it.id })
    }

    @Test
    fun `a category is kept when its country and its kind are both in`() {
        val triage = IptvTriage(regions = setOf("GB", "FR"), kinds = setOf("Sport", "Films", "News"))
        val kept = IptvTriageRules.keptCategories(sample, triage, regions)
        assertEquals(setOf("uk-sport", "uk-film", "fr-info", "fr-cine"), kept)
        assertEquals(8, IptvTriageRules.channelCount(sample, kept))
        assertEquals(emptySet(), IptvTriageRules.keptCategories(sample, IptvTriage(), regions))
    }

    @Test
    fun `first ten leads with what is watched, then the starred, then playlist order`() {
        val merged = IptvChannelMerge.merge(sample.channels.take(6))
        val ref = { channel: IptvChannel -> "p:${channel.key}" }
        val picks = IptvTriageRules.firstTenCandidates(
            channels = merged,
            refOf = ref,
            plays = listOf("p:uk-sport-1", "p:uk-sport-1", "p:uk-film-2"),
            favourites = listOf("p:uk-ent-2"),
            limit = 4,
        )
        assertEquals(listOf("uk-sport-1", "uk-film-2", "uk-ent-2", "uk-ent-1"), picks.map { it.primary.key })
    }
}
