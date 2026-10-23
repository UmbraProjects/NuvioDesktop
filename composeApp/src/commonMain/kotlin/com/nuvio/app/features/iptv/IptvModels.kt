package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

// Ported from Slokker's GoatTV (GOAThings/GoatTV), used with permission. See Docs/plans/iptv-plan.md.

@Serializable
enum class IptvPlaylistType { Xtream, M3u }

/**
 * A provider the user added. Xtream uses [server]/[username]/[password]; M3U uses [url]. [epgUrl]
 * overrides the guide either type would otherwise use (Xtream's `xmltv.php`, the M3U's `x-tvg-url`).
 */
@Serializable
data class IptvPlaylist(
    val id: String,
    val type: IptvPlaylistType,
    val name: String,
    val server: String? = null,
    val username: String? = null,
    val password: String? = null,
    val url: String? = null,
    val epgUrl: String? = null,
)

@Serializable
data class IptvCategory(val id: String, val name: String)

/**
 * One channel of a playlist.
 *
 * [uid] is its position in the list: unique, so it is the lazy-list key. [key] is the stable
 * identity used for favourites and recents (Xtream `stream_id`, M3U `tvg-id` or a URL hash); it
 * survives a refresh that reorders the list.
 */
@Serializable
data class IptvChannel(
    val uid: Int,
    val key: String,
    val num: Int,
    /** Can be empty when the provider sends no name; the UI shows "Channel [num]". */
    val name: String,
    val logo: String? = null,
    val categoryId: String,
    val streamId: String? = null,
    val url: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Channel id in the XMLTV guide (`tvg-id` / `epg_channel_id`). */
    val epgId: String? = null,
    /** Days of catch-up the server keeps for this channel (0 = none). Not used by v1. */
    val archiveDays: Int = 0,
)

@Serializable
data class XtreamAccount(
    val maxConnections: Int? = null,
    val status: String? = null,
    /** Expiry, epoch seconds; null = no expiry given. */
    val expiresAtSeconds: Long? = null,
    /** `allowed_output_formats` (e.g. only "ts"); empty = not stated. */
    val allowedFormats: Set<String> = emptySet(),
    /** Server time zone (e.g. "Europe/Lisbon"); catch-up requests use its local time. */
    val timezone: String? = null,
)

class IptvPlaylistContent(
    val playlist: IptvPlaylist,
    val categories: List<IptvCategory>,
    val channels: List<IptvChannel>,
    val account: XtreamAccount? = null,
    /** The XMLTV guide: the user's override, the Xtream account's, or the M3U header's `x-tvg-url`. */
    val guideUrl: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val byCategory: Map<String, List<IptvChannel>> by lazy { channels.groupBy { it.categoryId } }

    /** First channel wins when a key repeats. */
    val byKey: Map<String, IptvChannel> by lazy { channels.asReversed().associateBy { it.key } }

    val categoryNames: Map<String, String> by lazy { categories.associate { it.id to it.name } }

    companion object {
        /** Category for channels whose category the provider did not list. The UI localises it. */
        const val OTHER_CATEGORY = "__other__"

        /** Puts channels in unknown categories into [OTHER_CATEGORY] and drops empty categories. */
        fun build(
            playlist: IptvPlaylist,
            categories: List<IptvCategory>,
            channels: List<IptvChannel>,
            account: XtreamAccount? = null,
            guideUrl: String? = playlist.epgUrl,
            fetchedAt: Long = System.currentTimeMillis(),
        ): IptvPlaylistContent {
            val known = categories.mapTo(HashSet()) { it.id }
            val fixed = channels.map { if (it.categoryId in known) it else it.copy(categoryId = OTHER_CATEGORY) }
            val used = fixed.mapTo(HashSet()) { it.categoryId }
            val cats = categories.filter { it.id in used }.toMutableList()
            if (OTHER_CATEGORY in used) cats += IptvCategory(OTHER_CATEGORY, OTHER_CATEGORY)
            return IptvPlaylistContent(
                playlist = playlist,
                categories = cats,
                channels = fixed,
                account = account,
                guideUrl = guideUrl?.trim()?.takeIf { it.isNotEmpty() },
                fetchedAt = fetchedAt,
            )
        }
    }
}

@Serializable
data class IptvEpgEntry(val title: String, val startMs: Long, val endMs: Long) {
    fun progress(now: Long): Float =
        if (endMs <= startMs) 0f else ((now - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/**
 * One programme per moment. Provider XMLTV files are often several sources merged, so the same slot
 * appears two or three times with slightly different times or titles, and drawn as is they stack
 * into unreadable text. Keeps, in start order, each programme that begins once the last kept one
 * has ended (a minute of slack for sources that disagree by seconds).
 */
fun List<IptvEpgEntry>.withoutOverlaps(): List<IptvEpgEntry> {
    if (size < 2) return this
    val kept = ArrayList<IptvEpgEntry>(size)
    for (entry in sortedBy { it.startMs }) {
        val last = kept.lastOrNull()
        if (last == null || entry.startMs >= last.endMs - 60_000L) kept += entry
    }
    return kept
}

/** Why a playlist or guide failed to load, so the UI can say what to fix. */
class IptvSourceException(
    val kind: Kind,
    message: String,
    val httpCode: Int? = null,
    val status: String? = null,
    cause: Throwable? = null,
) : java.io.IOException(message, cause) {
    enum class Kind { NoUrl, BadUrl, BadLogin, Inactive, Http, BadResponse, Network }
}
