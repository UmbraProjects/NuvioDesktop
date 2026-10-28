package com.nuvio.app.features.streams

import com.nuvio.app.features.debrid.DebridStreamAudioChannel
import com.nuvio.app.features.debrid.DebridStreamAudioTag
import com.nuvio.app.features.debrid.DebridStreamQuality
import com.nuvio.app.features.debrid.DebridStreamVisualTag
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

/** User-chosen order for the source list. [DEFAULT] leaves the addon/score order alone. */
enum class StreamListSortOrder(val label: String) {
    DEFAULT(localizedString(Res.string.streams_sort_default_order)),
    SIZE_DESC(localizedString(Res.string.streams_sort_largest_first)),
    SIZE_ASC(localizedString(Res.string.streams_sort_smallest_first)),
    QUALITY(localizedString(Res.string.streams_sort_highest_quality)),
    VIDEO(localizedString(Res.string.streams_sort_best_video_hdr_dv)),
    AUDIO(localizedString(Res.string.streams_sort_best_audio)),
}

/**
 * Re-orders the source list by a plain criterion (size, quality, video, audio) chosen from the sort
 * chip, optionally lifting instantly playable (debrid-cached) sources above the rest.
 *
 * A non-default sort collapses every visible section into one flat run: "largest first" means the
 * largest source *on screen*, and sorting within each addon section would leave the biggest file of
 * the second addon below the smallest of the first. Nothing is deduplicated — unlike the score merge,
 * this is a view over exactly what the providers returned.
 *
 * [cachedFirst] is an independent toggle rather than an order, because "cached + largest" is the
 * combination people actually want. Under [StreamListSortOrder.DEFAULT] it re-orders within each
 * section and leaves the sections alone: there is no criterion to justify flattening them.
 *
 * Kept pure and free of Compose so the ordering can be tested directly.
 */
internal object StreamListSort {

    /** Synthetic addon id for the flattened, sorted section. See [StreamSourceMerge.isFlatSection]. */
    const val SORTED_ADDON_ID = "nuvio:list-sorted"

    fun apply(
        groups: List<AddonStreamGroup>,
        order: StreamListSortOrder,
        cachedFirst: Boolean = false,
        traitsOf: (StreamItem) -> StreamTraits = StreamTraitDetector::detect,
        sizeOf: (StreamItem) -> Long? = StreamTraitDetector::listSortSizeBytes,
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups
        if (order == StreamListSortOrder.DEFAULT) {
            return if (cachedFirst) groups.map { liftCached(it, traitsOf) } else groups
        }

        val sorted = sortFlat(
            entries = groups.flatMap { it.streams },
            order = order,
            cachedFirst = cachedFirst,
            streamOf = { it },
            traitsOf = traitsOf,
            sizeOf = sizeOf,
        )

        return listOf(
            AddonStreamGroup(
                addonName = groups.singleOrNull()?.addonName ?: order.label,
                addonId = SORTED_ADDON_ID,
                streams = sorted,
                isLoading = groups.any { it.isLoading },
                error = null,
            ),
        )
    }

    /**
     * [entries] re-ordered by [order], for a list that is already one flat run — the desktop
     * player's HTML sources panel, whose rows carry their own index and filter id and so cannot be
     * regrouped into [AddonStreamGroup]s. Under [StreamListSortOrder.DEFAULT], [cachedFirst] lifts
     * cached sources over the whole list, since there are no sections to keep.
     */
    fun <T> sortFlat(
        entries: List<T>,
        order: StreamListSortOrder,
        cachedFirst: Boolean,
        streamOf: (T) -> StreamItem,
        traitsOf: (StreamItem) -> StreamTraits = StreamTraitDetector::detect,
        sizeOf: (StreamItem) -> Long? = StreamTraitDetector::listSortSizeBytes,
    ): List<T> {
        val comparator = comparatorFor(order, cachedFirst) ?: return entries
        // Rows with nothing playable behind them (diagnostics, notices) have no size or quality;
        // pinned after the real sources in the order the addons sent them, as the score sort does.
        val (rankable, extras) = entries.partition { streamOf(it).isScorableStream }
        val keyed = rankable.map { entry ->
            val stream = streamOf(entry)
            entry to SortKey(stream, sizeOf(stream), lazy { traitsOf(stream) })
        }
        // sortedWith is stable, so ties keep the incoming (addon/score) order.
        return keyed.sortedWith(compareBy(comparator) { it.second }).map { it.first } + extras
    }

    /** Null when nothing would move: the default order with cached-first off. */
    private fun comparatorFor(order: StreamListSortOrder, cachedFirst: Boolean): Comparator<SortKey>? {
        // Unknown sizes always go last, in either direction — "smallest first" putting every row
        // without a size at the top would bury the answer.
        val bySizeKnown = compareBy<SortKey> { it.size == null }
        val largestFirst = bySizeKnown.thenByDescending { it.size ?: 0L }
        val byResolution = compareByDescending<SortKey> { it.traits.value.resolution.value }
        val bySource = compareBy<SortKey> { QUALITY_RANK.getOrElse(it.traits.value.quality) { Int.MAX_VALUE } }
        val comparator: Comparator<SortKey>? = when (order) {
            StreamListSortOrder.DEFAULT -> null
            StreamListSortOrder.SIZE_DESC -> largestFirst
            StreamListSortOrder.SIZE_ASC -> bySizeKnown.thenBy { it.size ?: 0L }
            StreamListSortOrder.QUALITY -> byResolution.then(bySource).then(largestFirst)
            // Resolution still leads: a 720p DV rip above every 4K SDR remux is not "best video".
            // Dynamic range splits what QUALITY treats as ties at the same resolution.
            StreamListSortOrder.VIDEO -> byResolution
                .thenBy { dynamicRangeRank(it.traits.value) }
                .then(bySource)
                .then(largestFirst)
            StreamListSortOrder.AUDIO -> compareBy<SortKey> { audioFormatRank(it.traits.value) }
                .thenBy { channelRank(it.traits.value) }
                .then(byResolution)
                .then(largestFirst)
        }
        if (!cachedFirst) return comparator
        val cached = compareBy<SortKey> { !it.traits.value.isDebridCached }
        return if (comparator == null) cached else cached.then(comparator)
    }

    /** Cached sources to the top of one section, everything else in its incoming order. */
    private fun liftCached(group: AddonStreamGroup, traitsOf: (StreamItem) -> StreamTraits): AddonStreamGroup {
        val (rankable, extras) = group.streams.partition { it.isScorableStream }
        val (cached, uncached) = rankable.partition { traitsOf(it).isDebridCached }
        if (cached.isEmpty() || uncached.isEmpty()) return group
        val lifted = cached + uncached + extras
        // Same instance when nothing moved, so remember/LazyColumn see no change for this section.
        return if (lifted == group.streams) group else group.copy(streams = lifted)
    }

    /**
     * Lower is better. Dolby Vision leads; DV with an HDR10 base layer beats DV-only, which renders
     * with wrong colours on a chain that cannot do DV. AI-generated HDR/DV is a fake grade and
     * ranks as SDR.
     */
    internal fun dynamicRangeRank(traits: StreamTraits): Int {
        if (traits.isAiEnhanced) return DYNAMIC_RANGE_SDR
        val tags = traits.visualTags
        return when {
            DebridStreamVisualTag.HDR_DV in tags -> 0
            DebridStreamVisualTag.DV_ONLY in tags -> 1
            DebridStreamVisualTag.HDR10_PLUS in tags -> 2
            DebridStreamVisualTag.HDR10 in tags || DebridStreamVisualTag.HDR in tags -> 3
            DebridStreamVisualTag.HLG in tags -> 4
            else -> DYNAMIC_RANGE_SDR
        }
    }

    /**
     * Lower is better: lossless object audio, lossless, lossy object (DD+ Atmos), then lossy by
     * quality. The detector also tags "DTS-HD MA" as DTS-HD and DTS, so checks run best-first.
     */
    internal fun audioFormatRank(traits: StreamTraits): Int {
        val tags = traits.audioTags
        val atmos = DebridStreamAudioTag.ATMOS in tags
        val trueHd = DebridStreamAudioTag.TRUEHD in tags
        return when {
            (trueHd && atmos) || DebridStreamAudioTag.DTS_X in tags -> 0
            trueHd || DebridStreamAudioTag.DTS_HD_MA in tags || DebridStreamAudioTag.FLAC in tags -> 1
            atmos -> 2
            DebridStreamAudioTag.DTS_HD in tags || DebridStreamAudioTag.DD_PLUS in tags -> 3
            DebridStreamAudioTag.DTS_ES in tags || DebridStreamAudioTag.DTS in tags -> 4
            DebridStreamAudioTag.DD in tags -> 5
            DebridStreamAudioTag.OPUS in tags || DebridStreamAudioTag.AAC in tags -> 6
            else -> 7
        }
    }

    private fun channelRank(traits: StreamTraits): Int =
        traits.audioChannels.minOfOrNull { CHANNEL_RANK.getOrElse(it) { Int.MAX_VALUE } } ?: Int.MAX_VALUE

    private class SortKey(
        val stream: StreamItem,
        val size: Long?,
        // Size sorts skip trait detection entirely unless cached-first needs it.
        val traits: Lazy<StreamTraits>,
    )

    private val QUALITY_RANK: Map<DebridStreamQuality, Int> =
        DebridStreamQuality.defaultOrder.withIndex().associate { (index, quality) -> quality to index }

    private val CHANNEL_RANK: Map<DebridStreamAudioChannel, Int> =
        DebridStreamAudioChannel.defaultOrder.withIndex().associate { (index, channel) -> channel to index }

    private const val DYNAMIC_RANGE_SDR = 5
}
