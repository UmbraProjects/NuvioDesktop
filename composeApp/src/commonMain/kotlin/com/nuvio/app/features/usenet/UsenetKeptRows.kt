package com.nuvio.app.features.usenet

import com.nuvio.app.features.player.PlayerStreamsRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamsRepository

/**
 * A row's ⚡ or 📖 is decided when the search runs, so rows already listed when a release first
 * opens kept their ⚡ after backing out to them. Once the engine keeps an NZB, its rows in the
 * loaded source lists (the picker's and the player's) switch to 📖 in place.
 */
internal object UsenetKeptRows {
    fun mark(nzbUrl: String) {
        StreamsRepository.mapGroups { markKept(it, nzbUrl) }
        PlayerStreamsRepository.mapGroups { markKept(it, nzbUrl) }
    }

    /** [groups] with every row of [nzbUrl] reading 📖; the same list when nothing changes. */
    fun markKept(groups: List<AddonStreamGroup>, nzbUrl: String): List<AddonStreamGroup> {
        var changed = false
        val next = groups.map { group ->
            if (group.streams.none { it.nzbUrl == nzbUrl }) return@map group
            group.copy(
                streams = group.streams.map { stream ->
                    val description = stream.description
                    if (stream.nzbUrl == nzbUrl && description != null && description.startsWith(UsenetStreamFormat.FRESH)) {
                        changed = true
                        stream.copy(description = UsenetStreamFormat.KEPT + description.removePrefix(UsenetStreamFormat.FRESH))
                    } else {
                        stream
                    }
                },
            )
        }
        return if (changed) next else groups
    }
}
