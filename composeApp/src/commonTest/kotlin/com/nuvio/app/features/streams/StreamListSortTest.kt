package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StreamListSortTest {

    private fun stream(
        addon: String,
        name: String,
        size: Long? = null,
        description: String? = null,
        url: String? = "https://example.test/$name",
    ) = StreamItem(
        name = name,
        description = description,
        url = url,
        addonName = addon,
        addonId = "addon:$addon",
        behaviorHints = StreamBehaviorHints(videoSize = size),
    )

    private fun group(addon: String, vararg streams: StreamItem) =
        AddonStreamGroup(addonName = addon, addonId = "addon:$addon", streams = streams.toList())

    private fun names(groups: List<AddonStreamGroup>) = groups.flatMap { it.streams }.map { it.name }

    private val gb = 1_073_741_824L

    @Test
    fun `default order returns the input untouched`() {
        val groups = listOf(group("A", stream("A", "a1", size = gb)))
        assertSame(groups, StreamListSort.apply(groups, StreamListSortOrder.DEFAULT))
    }

    @Test
    fun `largest first flattens sections and puts unknown sizes last`() {
        val sorted = StreamListSort.apply(
            listOf(
                group("A", stream("A", "a-small", size = 2 * gb), stream("A", "a-unknown")),
                group("B", stream("B", "b-big", size = 20 * gb)),
            ),
            StreamListSortOrder.SIZE_DESC,
        )
        assertEquals(1, sorted.size)
        assertEquals(StreamListSort.SORTED_ADDON_ID, sorted.single().addonId)
        assertEquals(listOf("b-big", "a-small", "a-unknown"), names(sorted))
    }

    @Test
    fun `flat sort reorders wrapped entries and keeps their payload`() {
        // The player's sources panel sorts rows that carry their own index; the index must travel
        // with its stream, since selecting a row dispatches by it.
        val entries = listOf(
            7 to stream("A", "small", size = gb),
            3 to stream("B", "unknown"),
            5 to stream("B", "big", size = 9 * gb),
        )

        val sorted = StreamListSort.sortFlat(
            entries = entries,
            order = StreamListSortOrder.SIZE_DESC,
            cachedFirst = false,
            streamOf = { it.second },
        )

        assertEquals(listOf(5, 7, 3), sorted.map { it.first })
    }

    @Test
    fun `flat sort under the default order is the same list`() {
        val entries = listOf(1 to stream("A", "a"), 2 to stream("A", "b"))
        assertSame(
            entries,
            StreamListSort.sortFlat(entries, StreamListSortOrder.DEFAULT, cachedFirst = false, streamOf = { it.second }),
        )
    }

    @Test
    fun `smallest first still keeps unknown sizes last`() {
        val sorted = StreamListSort.apply(
            listOf(group("A", stream("A", "unknown"), stream("A", "big", size = 9 * gb), stream("A", "small", size = gb))),
            StreamListSortOrder.SIZE_ASC,
        )
        assertEquals(listOf("small", "big", "unknown"), names(sorted))
    }

    @Test
    fun `size falls back to the size printed in the description`() {
        val sorted = StreamListSort.apply(
            listOf(
                group(
                    "A",
                    stream("A", "text-small", description = "💾 1.4 GB ⚙️ Torrentio"),
                    stream("A", "text-big", description = "👤 12 💾 14.2 GB 📦 80 GB"),
                    stream("A", "speed-only", description = "⚡ 5.0 MB/s"),
                ),
            ),
            StreamListSortOrder.SIZE_DESC,
        )
        assertEquals(listOf("text-big", "text-small", "speed-only"), names(sorted))
    }

    @Test
    fun `quality orders by resolution then source then size`() {
        val sorted = StreamListSort.apply(
            listOf(
                group(
                    "A",
                    stream("A", "Movie.2024.720p.BluRay.x264-GRP", size = 5 * gb),
                    stream("A", "Movie.2024.2160p.WEB-DL.x265-GRP", size = 15 * gb),
                    stream("A", "Movie.2024.2160p.BluRay.REMUX.HEVC-GRP", size = 60 * gb),
                    stream("A", "Movie.2024.1080p.WEB-DL.x264-GRP", size = 4 * gb),
                ),
            ),
            StreamListSortOrder.QUALITY,
        )
        assertEquals(
            listOf(
                "Movie.2024.2160p.BluRay.REMUX.HEVC-GRP",
                "Movie.2024.2160p.WEB-DL.x265-GRP",
                "Movie.2024.1080p.WEB-DL.x264-GRP",
                "Movie.2024.720p.BluRay.x264-GRP",
            ),
            names(sorted),
        )
    }

    @Test
    fun `non-stream rows stay pinned after real sources`() {
        val notice = stream("A", "notice", url = null)
        val sorted = StreamListSort.apply(
            listOf(group("A", notice, stream("A", "real", size = gb))),
            StreamListSortOrder.SIZE_ASC,
        )
        assertEquals(listOf("real", "notice"), names(sorted))
    }

    @Test
    fun `video puts dolby vision and hdr above sdr at the same resolution`() {
        val sorted = StreamListSort.apply(
            listOf(
                group(
                    "A",
                    stream("A", "Movie.2024.2160p.BluRay.x265-GRP", size = 30 * gb),
                    stream("A", "Movie.2024.2160p.BluRay.HDR10.x265-GRP", size = 20 * gb),
                    stream("A", "Movie.2024.1080p.BluRay.DV.HDR.x265-GRP", size = 10 * gb),
                    stream("A", "Movie.2024.2160p.BluRay.DV.HDR.x265-GRP", size = 15 * gb),
                ),
            ),
            StreamListSortOrder.VIDEO,
        )
        assertEquals(
            listOf(
                "Movie.2024.2160p.BluRay.DV.HDR.x265-GRP",
                "Movie.2024.2160p.BluRay.HDR10.x265-GRP",
                "Movie.2024.2160p.BluRay.x265-GRP",
                "Movie.2024.1080p.BluRay.DV.HDR.x265-GRP",
            ),
            names(sorted),
        )
    }

    @Test
    fun `audio ranks lossless object audio over lossy and more channels over fewer`() {
        val sorted = StreamListSort.apply(
            listOf(
                group(
                    "A",
                    stream("A", "Movie.2024.1080p.WEB-DL.AAC.2.0-GRP", size = 4 * gb),
                    stream("A", "Movie.2024.1080p.WEB-DL.DDP.5.1-GRP", size = 5 * gb),
                    stream("A", "Movie.2024.1080p.BluRay.DTS-HD MA.7.1-GRP", size = 12 * gb),
                    stream("A", "Movie.2024.1080p.WEB-DL.DDP.5.1.Atmos-GRP", size = 6 * gb),
                    stream("A", "Movie.2024.1080p.BluRay.TrueHD.7.1.Atmos-GRP", size = 14 * gb),
                    stream("A", "Movie.2024.1080p.WEB-DL.DDP.2.0-GRP", size = 5 * gb),
                ),
            ),
            StreamListSortOrder.AUDIO,
        )
        assertEquals(
            listOf(
                "Movie.2024.1080p.BluRay.TrueHD.7.1.Atmos-GRP",
                "Movie.2024.1080p.BluRay.DTS-HD MA.7.1-GRP",
                "Movie.2024.1080p.WEB-DL.DDP.5.1.Atmos-GRP",
                "Movie.2024.1080p.WEB-DL.DDP.5.1-GRP",
                "Movie.2024.1080p.WEB-DL.DDP.2.0-GRP",
                "Movie.2024.1080p.WEB-DL.AAC.2.0-GRP",
            ),
            names(sorted),
        )
    }

    private fun cachedTraits(cachedNames: Set<String>): (StreamItem) -> StreamTraits = { stream ->
        StreamTraitDetector.detect(stream).copy(isDebridCached = stream.name in cachedNames)
    }

    @Test
    fun `cached first leads a flat sort and keeps the order among each half`() {
        val sorted = StreamListSort.apply(
            listOf(
                group("A", stream("A", "big", size = 20 * gb), stream("A", "small-cached", size = gb)),
                group("B", stream("B", "mid-cached", size = 5 * gb), stream("B", "mid", size = 6 * gb)),
            ),
            StreamListSortOrder.SIZE_DESC,
            cachedFirst = true,
            traitsOf = cachedTraits(setOf("small-cached", "mid-cached")),
        )
        assertEquals(listOf("mid-cached", "small-cached", "big", "mid"), names(sorted))
    }

    @Test
    fun `cached first under the default order re-orders within sections only`() {
        val groups = listOf(
            group("A", stream("A", "a1"), stream("A", "a2-cached"), stream("A", "notice", url = null)),
            group("B", stream("B", "b1-cached"), stream("B", "b2")),
        )
        val sorted = StreamListSort.apply(
            groups,
            StreamListSortOrder.DEFAULT,
            cachedFirst = true,
            traitsOf = cachedTraits(setOf("a2-cached", "b1-cached")),
        )
        assertEquals(listOf("addon:A", "addon:B"), sorted.map { it.addonId })
        assertEquals(listOf("a2-cached", "a1", "notice", "b1-cached", "b2"), names(sorted))
        assertSame(groups[1], sorted[1])
    }

    @Test
    fun `preferred language first puts the first preferred language on top, then the next, then Multi`() {
        val sorted = StreamListSort.apply(
            listOf(
                group(
                    "A",
                    stream("A", "Movie.2024.2160p.WEB-DL-GRP", size = 20 * gb),
                    stream("A", "Movie.2024.1080p.FRENCH.WEB-DL-GRP", size = 4 * gb),
                    stream("A", "Movie.2024.1080p.MULTI.WEB-DL-GRP", size = 6 * gb),
                ),
                group(
                    "B",
                    stream("B", "Movie.2024.720p.ITA.WEB-DL-GRP", size = 2 * gb),
                    stream("B", "Movie.2024.2160p.ITA.WEB-DL-GRP", size = 18 * gb),
                ),
            ),
            StreamListSortOrder.LANGUAGE,
            preferredLanguages = listOf("it", "fr"),
        )
        assertEquals(
            listOf(
                "Movie.2024.2160p.ITA.WEB-DL-GRP",
                "Movie.2024.720p.ITA.WEB-DL-GRP",
                "Movie.2024.1080p.FRENCH.WEB-DL-GRP",
                "Movie.2024.1080p.MULTI.WEB-DL-GRP",
                "Movie.2024.2160p.WEB-DL-GRP",
            ),
            names(sorted),
        )
    }

    @Test
    fun `preferred language matches a regional setting by its language`() {
        val brazilian = StreamTraitDetector.detect(stream("A", "Movie.2024.1080p.PORTUGUESE.WEB-DL-GRP"))
        val english = StreamTraitDetector.detect(stream("A", "Movie.2024.1080p.WEB-DL-GRP"))
        assertEquals(0, StreamListSort.languageRank(brazilian, listOf("pt-br")))
        assertEquals(2, StreamListSort.languageRank(english, listOf("pt-br")))
    }

    @Test
    fun `preferred language sort keeps the player panel ordinals of the older orders`() {
        // The HTML sources panel sends the sort by ordinal; a new order must not shift the old ones.
        assertEquals(5, StreamListSortOrder.AUDIO.ordinal)
        assertEquals(StreamListSortOrder.entries.last(), StreamListSortOrder.LANGUAGE)
    }
}
