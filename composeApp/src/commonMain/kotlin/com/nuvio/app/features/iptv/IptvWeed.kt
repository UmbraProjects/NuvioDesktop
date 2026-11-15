package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/** fork:live-tv-weed - what the night checks have found about one channel. [bad] counts bad nights in a row. */
@Serializable
data class IptvWeedRecord(val bad: Int = 0, val checked: Long = -1L, val badNight: Long = -1L)

/**
 * fork:live-tv-weed (Settings -> Live TV -> Overnight weeding): between 02:00 and 05:00, while nothing plays, the
 * lineup's and the recently watched channels are opened one at a time. A channel that fails on three nights in a row
 * leaves Your 40 and the wall as "dead"; one good night clears it. Never more than [MAX_PER_NIGHT] channels a night.
 */
object IptvWeedRules {
    const val MAX_PER_NIGHT = 60
    const val BAD_NIGHTS = 3
    const val FIRST_HOUR = 2
    const val LAST_HOUR = 5 // exclusive

    /** True inside the quiet hours. [hour] is the local hour of day. */
    fun quietHour(hour: Int): Boolean = hour in FIRST_HOUR until LAST_HOUR

    /** Which channel keys to open tonight: the lineup, then the recents, not yet checked tonight, capped. */
    fun targets(
        lineup: List<String>,
        recents: List<String>,
        records: Map<String, IptvWeedRecord>,
        night: Long,
        limit: Int = MAX_PER_NIGHT,
    ): List<String> =
        (lineup + recents).distinct().filter { records[it]?.checked != night }.take(limit)

    /** The result of one open. A second bad result in the same night does not count again. */
    fun record(records: Map<String, IptvWeedRecord>, key: String, ok: Boolean, night: Long): Map<String, IptvWeedRecord> {
        val old = records[key] ?: IptvWeedRecord()
        val next = when {
            ok -> IptvWeedRecord(bad = 0, checked = night, badNight = -1L)
            old.badNight == night -> old.copy(checked = night)
            else -> IptvWeedRecord(bad = old.bad + 1, checked = night, badNight = night)
        }
        return records + (key to next)
    }

    fun isDead(record: IptvWeedRecord?): Boolean = record != null && record.bad >= BAD_NIGHTS

    fun dead(records: Map<String, IptvWeedRecord>): Set<String> = records.filterValues { isDead(it) }.keys

    /** Forgets what is known about channels no longer in the playlist. */
    fun prune(records: Map<String, IptvWeedRecord>, present: Set<String>): Map<String, IptvWeedRecord> =
        records.filterKeys { it in present }
}
