package com.nuvio.app.features.streams

/**
 * Collapses every addon's section into one score-ordered list.
 *
 * Duplicates are the whole problem here: the same release is routinely offered by Torrentio, Comet
 * and Meteor at once, and AIOStreams aggregates those very providers, so a naive concatenation
 * sorted by score puts three or four identical rows back to back — identical releases score
 * identically, so they land adjacent and the list reads as broken.
 *
 * Kept pure and free of Compose so the dedup can be tested directly: this is the one piece of the
 * feature that can *remove* a row the user might have wanted, so it needs to be provable rather
 * than eyeballed.
 */
internal object StreamSourceMerge {

    /** Synthetic addon id marking the merged section, so the renderer can special-case it. */
    const val MERGED_ADDON_ID = "nuvio:merged-sources"

    /**
     * Synthetic addon id for the optional extra source tab that shows the same collapsed,
     * score-ordered list *alongside* the real addon sections instead of replacing them.
     */
    const val HTPC_ADDON_ID = "nuvio:htpc-sorted"

    /** Filter-chip label for [HTPC_ADDON_ID] — the app's own ranking, next to each addon's. */
    const val HTPC_TAB_LABEL = "HTPC"

    /**
     * True for the synthetic groups that are a single score-ordered run. They must not be
     * re-grouped by source or sorted alphabetically the way a real addon section is — that would
     * throw away the score order which is the entire point of collapsing them.
     */
    fun isFlatSection(addonId: String): Boolean =
        addonId == MERGED_ADDON_ID || addonId == HTPC_ADDON_ID

    /**
     * @param groups already score-sorted groups.
     * @param scoreOf total score for a stream, injected so this stays independent of the profile
     *   and context plumbing.
     */
    fun merge(
        groups: List<AddonStreamGroup>,
        scoreOf: (StreamItem) -> Int,
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups
        return listOf(
            collapse(
                groups = groups,
                scoreOf = scoreOf,
                addonId = MERGED_ADDON_ID,
                addonName = groups.first().addonName,
            ),
        )
    }

    /**
     * The same collapse as [merge], but presented as one extra tab rather than as a replacement for
     * the addon sections. Returns null when there is nothing to show, so the caller can leave the
     * chip out rather than offering an empty tab.
     */
    fun htpcTab(
        groups: List<AddonStreamGroup>,
        scoreOf: (StreamItem) -> Int,
    ): AddonStreamGroup? {
        if (groups.none { it.streams.isNotEmpty() || it.isLoading }) return null
        return collapse(
            groups = groups,
            scoreOf = scoreOf,
            addonId = HTPC_ADDON_ID,
            addonName = HTPC_TAB_LABEL,
        )
    }

    private fun collapse(
        groups: List<AddonStreamGroup>,
        scoreOf: (StreamItem) -> Int,
        addonId: String,
        addonName: String,
    ): AddonStreamGroup {
        val (rankable, extras) = groups.flatMap { it.streams }.partition { it.isScorableStream }

        // LinkedHashMap so streams that tie on score keep the order their providers were consulted
        // in, rather than reshuffling between recompositions.
        val best = LinkedHashMap<String, Pair<StreamItem, Int>>()
        val unkeyed = mutableListOf<Pair<StreamItem, Int>>()
        rankable.forEach { stream ->
            val scored = stream to scoreOf(stream)
            val key = dedupeKey(stream)
            if (key == null) {
                // No reliable identity means we cannot *prove* it is a duplicate, and silently
                // losing a playable source is far worse than showing one twice.
                unkeyed += scored
                return@forEach
            }
            val existing = best[key]
            if (existing == null || scored.second > existing.second) best[key] = scored
        }

        val ordered = (best.values + unkeyed)
            .sortedByDescending { it.second }
            .map { it.first }

        return AddonStreamGroup(
            addonName = addonName,
            addonId = addonId,
            streams = ordered + extras,
            isLoading = groups.any { it.isLoading },
            error = null,
        )
    }

    /**
     * Identity of a release across addons. Infohash is authoritative for torrents; direct links fall
     * back to filename plus exact byte size, which holds up in practice because two different
     * releases essentially never share both. Null means "no reliable identity".
     */
    fun dedupeKey(stream: StreamItem): String? {
        stream.p2pInfoHash?.let { return "hash:$it" }
        val name = stream.behaviorHints.filename?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val size = stream.behaviorHints.videoSize
        if (name != null && size != null) return "file:$name:$size"
        return null
    }
}
