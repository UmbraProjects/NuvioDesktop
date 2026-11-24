package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What plays across every combination of source order, pick mode and where the best stream sits, with the streams laid
 * out the way a load lays them out (groups put in Source priority order, then flattened).
 */
class PlaybackPickMatrixTest {

    private val scoring = StreamScoreProfile(enabled = true)
    private val addons = setOf("Torrentio")
    private val sources = listOf(PlaybackSources.USENET, PlaybackSources.PLUGINS, PlaybackSources.LOCAL, PlaybackSources.addon("Torrentio"))

    private val high = "Show.2023.2160p.BluRay.REMUX.HDR.DV.TrueHD.Atmos-GRP"
    private val low = "Show.2023.480p.WEB.x264-GRP"

    private fun streamFor(source: String, release: String): StreamItem = when (source) {
        PlaybackSources.USENET -> StreamItem(
            name = release, addonName = "Built-in Usenet", addonId = "usenet:built-in",
            nzbUrl = "https://indexer.example/$release.nzb", behaviorHints = StreamBehaviorHints(filename = "$release.mkv"),
        )
        PlaybackSources.PLUGINS -> StreamItem(
            name = release, url = "https://plugin.example/$release.mkv", addonName = "Some scraper", addonId = "plugin:x",
            behaviorHints = StreamBehaviorHints(filename = "$release.mkv"),
        )
        PlaybackSources.LOCAL -> StreamItem(
            name = release, url = "file:///D:/Media/$release.mkv", addonName = "Local library", addonId = "local-library",
            streamType = "local", behaviorHints = StreamBehaviorHints(filename = "$release.mkv"),
        )
        else -> StreamItem(
            name = release, url = "https://addon.example/$release.mkv", addonName = "Torrentio", addonId = "addon:torrentio",
            behaviorHints = StreamBehaviorHints(filename = "$release.mkv"),
        )
    }

    private fun groupFor(source: String, streams: List<StreamItem>, loading: Boolean = false): AddonStreamGroup {
        val first = streamFor(source, low)
        return AddonStreamGroup(addonName = first.addonName, addonId = first.addonId, streams = streams, isLoading = loading)
    }

    /** One stream per source; [best] holds the high-scoring release, the rest the low one. */
    private fun load(order: List<String>, best: String, settings: PlaybackPickSettings): List<AddonStreamGroup> =
        PlaybackSources.orderGroups(
            groups = sources.map { source -> groupFor(source, listOf(streamFor(source, if (source == best) high else low))) },
            installedAddonNames = addons.toList(),
            settings = settings.copy(order = order),
        )

    private fun rules(mode: StreamAutoPlayMode, settings: PlaybackPickSettings) = PlaybackPickRules(
        mode = mode, installedAddonNames = addons, sources = settings, scoreProfile = scoring,
    )

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }

    @Test
    fun `the release scores higher than the low one`() {
        val score = { s: StreamItem -> StreamScorer.score(s, scoring, StreamScoreContext.MOVIE).total }
        sources.forEach { assertTrue(score(streamFor(it, high)) > score(streamFor(it, low)), it) }
    }

    @Test
    fun `best score plays the best stream whatever the source order`() {
        val settings = PlaybackPickSettings()
        for (order in permutations(sources)) for (best in sources) {
            val streams = load(order, best, settings).flatMap { it.streams }
            val picked = rules(StreamAutoPlayMode.SCORED, settings).select(streams)
            assertEquals(streamFor(best, high), picked, "order=$order best=$best")
        }
    }

    @Test
    fun `priority order plays the top source whatever the scores`() {
        val settings = PlaybackPickSettings()
        for (order in permutations(sources)) for (best in sources) {
            val streams = load(order, best, settings).flatMap { it.streams }
            val picked = rules(StreamAutoPlayMode.FIRST_STREAM, settings).select(streams)
            assertEquals(order.first(), PlaybackSources.keyFor(picked!!, addons), "order=$order best=$best")
        }
    }

    @Test
    fun `a source switched off is never picked in either mode`() {
        for (off in sources) for (mode in listOf(StreamAutoPlayMode.SCORED, StreamAutoPlayMode.FIRST_STREAM)) {
            val settings = PlaybackPickSettings(autoPlay = mapOf(off to false))
            for (order in permutations(sources)) {
                // The switched-off source holds the best stream and sits at the top: it still must not play.
                val top = listOf(off) + (order - off)
                val picked = rules(mode, settings).select(load(top, off, settings).flatMap { it.streams })
                assertNotEquals(off, PlaybackSources.keyFor(picked!!, addons), "off=$off mode=$mode")
            }
        }
    }

    @Test
    fun `the example from the question - local 1500 against usenet 200`() {
        val settings = PlaybackPickSettings()
        val order = listOf(PlaybackSources.USENET, PlaybackSources.PLUGINS, PlaybackSources.LOCAL)
        val streams = load(order, PlaybackSources.LOCAL, settings).flatMap { it.streams }
        assertEquals(PlaybackSources.LOCAL, PlaybackSources.keyFor(rules(StreamAutoPlayMode.SCORED, settings).select(streams)!!, addons))
        assertEquals(PlaybackSources.USENET, PlaybackSources.keyFor(rules(StreamAutoPlayMode.FIRST_STREAM, settings).select(streams)!!, addons))
    }

    // --- While sources are still answering ---------------------------------------------------------------------------

    @Test
    fun `only instant ends the wait at once`() {
        assertTrue(PlaybackPick.waitEndsAtOnce(0))
        assertFalse(PlaybackPick.waitEndsAtOnce(3))
        assertFalse(PlaybackPick.waitEndsAtOnce(Int.MAX_VALUE))
    }

    @Test
    fun `best score waits for every source before the wait is over`() {
        val settings = PlaybackPickSettings()
        val r = rules(StreamAutoPlayMode.SCORED, settings)
        // Usenet answers first with a low stream; local is answered; plugins are still searching.
        val groups = listOf(
            groupFor(PlaybackSources.USENET, listOf(streamFor(PlaybackSources.USENET, low))),
            groupFor(PlaybackSources.PLUGINS, emptyList(), loading = true),
            groupFor(PlaybackSources.LOCAL, listOf(streamFor(PlaybackSources.LOCAL, low))),
        )
        assertNull(PlaybackPick.pickOnResponse(r, groups, PlaybackPick.answered(groups), waitOver = false))
        // Once the wait is over, the best of what has answered plays.
        assertEquals(
            streamFor(PlaybackSources.USENET, low),
            PlaybackPick.pickOnResponse(r, groups, PlaybackPick.answered(groups), waitOver = true),
        )
        // Plugins answer with the best release: it wins the final pick.
        val done = groups.map { if (it.isLoading) it.copy(streams = listOf(streamFor(PlaybackSources.PLUGINS, high)), isLoading = false) else it }
        assertEquals(streamFor(PlaybackSources.PLUGINS, high), r.select(PlaybackPick.answered(done)))
    }

    @Test
    fun `once the wait is over a slow indexer no longer keeps usenet out of the pick`() {
        val settings = PlaybackPickSettings()
        val r = rules(StreamAutoPlayMode.SCORED, settings)
        // Three indexers have answered with the best release; a fourth is still searching.
        val groups = listOf(
            groupFor(PlaybackSources.USENET, listOf(streamFor(PlaybackSources.USENET, high)), loading = true),
            groupFor(PlaybackSources.PLUGINS, listOf(streamFor(PlaybackSources.PLUGINS, low))),
        )
        // Before the wait: Usenet's partial list stays out, and best score picks nothing yet.
        assertEquals(listOf(streamFor(PlaybackSources.PLUGINS, low)), PlaybackPick.answered(groups))
        assertNull(PlaybackPick.pickOnResponse(r, groups, PlaybackPick.answered(groups), waitOver = false))
        // After it: what the answered indexers found competes, and wins on score.
        assertEquals(
            streamFor(PlaybackSources.USENET, high),
            PlaybackPick.pickOnResponse(r, groups, PlaybackPick.answered(groups, waitOver = true), waitOver = true),
        )
    }

    @Test
    fun `priority order plays early only when every source above has answered`() {
        val settings = PlaybackPickSettings()
        val r = rules(StreamAutoPlayMode.FIRST_STREAM, settings)
        val usenetSearching = listOf(
            groupFor(PlaybackSources.USENET, emptyList(), loading = true),
            groupFor(PlaybackSources.PLUGINS, listOf(streamFor(PlaybackSources.PLUGINS, high))),
        )
        assertNull(PlaybackPick.pickOnResponse(r, usenetSearching, PlaybackPick.answered(usenetSearching), waitOver = false))

        val usenetEmpty = usenetSearching.map { if (it.isLoading) it.copy(isLoading = false) else it }
        assertEquals(
            streamFor(PlaybackSources.PLUGINS, high),
            PlaybackPick.pickOnResponse(r, usenetEmpty, PlaybackPick.answered(usenetEmpty), waitOver = false),
        )

        val topAnswered = listOf(
            groupFor(PlaybackSources.USENET, listOf(streamFor(PlaybackSources.USENET, low))),
            groupFor(PlaybackSources.PLUGINS, emptyList(), loading = true),
        )
        assertEquals(
            streamFor(PlaybackSources.USENET, low),
            PlaybackPick.pickOnResponse(r, topAnswered, PlaybackPick.answered(topAnswered), waitOver = false),
        )
    }

    @Test
    fun `the remembered release plays early in either mode`() {
        val settings = PlaybackPickSettings()
        for (mode in listOf(StreamAutoPlayMode.SCORED, StreamAutoPlayMode.FIRST_STREAM)) {
            val same = streamFor(PlaybackSources.PLUGINS, low).let { it.copy(behaviorHints = it.behaviorHints.copy(bingeGroup = "grp")) }
            val r = rules(mode, settings).copy(bingeGroup = "grp")
            val groups = listOf(
                groupFor(PlaybackSources.USENET, emptyList(), loading = true),
                groupFor(PlaybackSources.PLUGINS, listOf(same)),
            )
            assertEquals(same, PlaybackPick.pickOnResponse(r, groups, PlaybackPick.answered(groups), waitOver = false), "mode=$mode")
        }
    }
}
