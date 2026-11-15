package com.nuvio.app.features.iptv

/**
 * fork:live-tv-taste - counts the minutes of the programme playing (one call a minute from the player) and notes when
 * it has been seen and when it was watched to the end.
 */
object IptvTasteEngine {
    private var watching: IptvWatching? = null

    fun tick(playlist: IptvPlaylist, channel: IptvChannel, now: Long = System.currentTimeMillis()) {
        val entry = IptvEpgRepository.guideEntries(playlist, channel).firstOrNull { now >= it.startMs && now < it.endMs } ?: return
        val (next, event) = IptvTasteRules.step(watching, "${playlist.id}:${channel.key}", entry)
        watching = next
        IptvRepository.noteWatch(entry, event, now)
    }
}
