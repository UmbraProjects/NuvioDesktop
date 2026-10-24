package com.nuvio.app.features.streams

import kotlin.concurrent.Volatile

object StreamBadgePresentation {
    private class FilterCache(val rules: StreamBadgeRules, val filters: List<CompiledStreamBadgeFilter>)

    @Volatile
    private var filterCache: FilterCache? = null

    fun apply(groups: List<AddonStreamGroup>, rules: StreamBadgeRules): List<AddonStreamGroup> {
        val filters = compiledFilters(rules)
        if (filters.isEmpty()) return groups
        return groups.map { group ->
            group.copy(
                streams = group.streams.map { stream ->
                    val matchedBadges = StreamBadgeMatcher.matchedBadges(stream, filters)
                    if (matchedBadges.isEmpty()) {
                        stream
                    } else {
                        stream.copy(badges = mergeBadges(stream.badges, matchedBadges))
                    }
                },
            )
        }
    }

    // Each addon group is presented on its own with the same rules, so their regexes are
    // compiled once and reused until the rules change. Rules and filters are swapped together, so a
    // racing caller at worst compiles the same rules twice.
    private fun compiledFilters(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        filterCache?.takeIf { it.rules == rules }?.let { return it.filters }
        return StreamBadgeMatcher.compile(rules).also { filterCache = FilterCache(rules, it) }
    }

    private fun mergeBadges(existing: List<StreamBadge>, matched: List<StreamBadge>): List<StreamBadge> {
        if (existing.isEmpty()) return matched
        val seenKeys = existing.mapTo(mutableSetOf()) { it.dedupeKey() }
        return existing + matched.filter { badge -> seenKeys.add(badge.dedupeKey()) }
    }

    private fun StreamBadge.dedupeKey(): String =
        (imageURL.takeIf { it.isNotBlank() } ?: name).lowercase()
}
