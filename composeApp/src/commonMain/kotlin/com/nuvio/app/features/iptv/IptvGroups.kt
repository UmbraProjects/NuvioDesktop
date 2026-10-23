package com.nuvio.app.features.iptv

/**
 * The lists a playlist's channels can be browsed by: Favourites, Recently watched, All channels,
 * then the provider's categories (by id). Shared by the Live TV tab and the player's channel
 * switcher so both offer the same lists in the same order.
 */
object IptvGroups {
    const val FAVOURITES = "__fav__"
    const val RECENT = "__recent__"
    const val ALL = "__all__"

    /** Every group id of [content], in display order. */
    fun ids(content: IptvPlaylistContent): List<String> =
        listOf(FAVOURITES, RECENT, ALL) + content.categories.map { it.id }

    fun channels(content: IptvPlaylistContent, groupId: String, settings: IptvSettings): List<IptvChannel> {
        val playlistPrefix = "${content.playlist.id}:"
        fun refs(list: List<String>) = list.asSequence()
            .filter { it.startsWith(playlistPrefix) }
            .mapNotNull { content.byKey[it.removePrefix(playlistPrefix)] }
            .toList()
        return when (groupId) {
            FAVOURITES -> refs(settings.favourites)
            RECENT -> refs(settings.recents)
            ALL -> content.channels
            else -> content.byCategory[groupId].orEmpty()
        }
    }
}
