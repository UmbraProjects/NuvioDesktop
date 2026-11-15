package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/*
 * fork:live-tv-departure - the departure board: only what has not started, leaving within the hour, with a
 * countdown, so nothing is joined halfway; and "Tune in at": a reminder that tells you, or switches by itself,
 * when a programme starts. Pure Kotlin, tested by IptvDepartureTest.
 */

/** A group of the board: what starts [label]. */
data class IptvDepartureBucket(val label: String, val programmes: List<IptvWallProgramme>)

object IptvDeparture {
    /** The board's three windows, in minutes from now. */
    val WINDOWS = listOf(15, 30, 60)
    const val PER_BUCKET = 6

    /**
     * What has not started and starts within the last window, one programme per channel (its next one), in three
     * buckets: within 15 minutes, 15 to 30, 30 to 60. A programme that began a moment ago is no longer a departure.
     * [only] limits the board to some channels (the lineup); null = every channel with a guide.
     */
    fun buckets(channels: List<IptvChannelNow>, now: Long, only: ((IptvMergedChannel) -> Boolean)? = null, perBucket: Int = PER_BUCKET): List<IptvDepartureBucket> {
        val upcoming = channels.asSequence()
            .filter { only == null || only(it.channel) }
            .mapNotNull { on -> on.next(now)?.takeIf { it.startMs - now <= WINDOWS.last() * 60_000L }?.let { IptvWallProgramme(on.channel, it) } }
            .sortedBy { it.entry.startMs }
            .toList()
        var from = 0
        return WINDOWS.mapNotNull { minutes ->
            val limit = now + minutes * 60_000L
            val inside = upcoming.filter { it.entry.startMs > bucketStart(now, from) && it.entry.startMs <= limit }
            from = minutes
            val label = when (minutes) {
                WINDOWS.first() -> "Within $minutes minutes"
                else -> "${WINDOWS[WINDOWS.indexOf(minutes) - 1]} to $minutes minutes"
            }
            inside.take(perBucket).takeIf { it.isNotEmpty() }?.let { IptvDepartureBucket(label, it) }
        }
    }

    private fun bucketStart(now: Long, fromMinutes: Int): Long = if (fromMinutes == 0) now else now + fromMinutes * 60_000L

    /** "in 12 min", "in 1 h 05 min", or "now" once it is a minute away or less. */
    fun countdown(ms: Long): String {
        val minutes = (ms + 59_999L).coerceAtLeast(0L) / 60_000L
        return when {
            minutes <= 0L -> "now"
            minutes < 60L -> "in $minutes min"
            else -> "in ${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
        }
    }
}

/** fork:live-tv-reminder-card - what was picked on the reminder card. */
enum class IptvReminderAnswer { Now, AtStart, Dismiss }

/** A programme to tell the viewer about, or to switch to, when it starts. */
@Serializable
data class IptvReminder(
    val playlistId: String,
    /** The key of the channel's best copy. */
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    /** True: switch to the channel at the start; false: show a card two minutes before. */
    val autoSwitch: Boolean,
) {
    val id: String get() = "$playlistId:$channelKey:$startMs"
}

object IptvReminderRules {
    /** A plain reminder shows this long before the start. */
    const val LEAD_MS = 2 * 60_000L

    /** A reminder that fired more than this late (Nuvio was closed) is dropped without a word. */
    const val GRACE_MS = 10 * 60_000L

    fun fireAt(reminder: IptvReminder): Long = if (reminder.autoSwitch) reminder.startMs else reminder.startMs - LEAD_MS

    fun due(reminders: List<IptvReminder>, now: Long): List<IptvReminder> =
        reminders.filter { fireAt(it) <= now && now < it.startMs + GRACE_MS }

    fun stale(reminders: List<IptvReminder>, now: Long): List<IptvReminder> = reminders.filter { now >= it.startMs + GRACE_MS }

    fun add(reminders: List<IptvReminder>, reminder: IptvReminder): List<IptvReminder> =
        (reminders.filterNot { it.id == reminder.id } + reminder).sortedBy { it.startMs }

    /** The reminder set for [channel]'s programme starting at [startMs], if any. */
    fun find(reminders: List<IptvReminder>, playlistId: String, channel: IptvMergedChannel, startMs: Long): IptvReminder? {
        val keys = channel.sources.mapTo(HashSet()) { it.key }
        return reminders.firstOrNull { it.playlistId == playlistId && it.startMs == startMs && it.channelKey in keys }
    }

    /** The line of the card: "BBC One · starts in 2 min" or "BBC One · now". */
    const val SWITCH_NOW = "Switch now"
    const val AT_START = "At the start"
    const val NOT_NOW = "Not now"

    /** fork:live-tv-reminder-card - the reminder card's answers; [CARD_FOCUS] is highlighted first. */
    val CARD_OPTIONS = listOf(SWITCH_NOW, AT_START, NOT_NOW)
    const val CARD_FOCUS = 1

    /** What an answer on the reminder card means; -1 (Back, or the card timing out) is Not now. */
    fun answerOf(options: List<String>, index: Int): IptvReminderAnswer = when (options.getOrNull(index)) {
        SWITCH_NOW -> IptvReminderAnswer.Now
        AT_START -> IptvReminderAnswer.AtStart
        else -> IptvReminderAnswer.Dismiss
    }

    /** "At the start": the same reminder again, now switching by itself (it fires at the start, not before). */
    fun atStart(reminder: IptvReminder): IptvReminder = reminder.copy(autoSwitch = true)

    fun cardLine(reminder: IptvReminder, now: Long): String = "${reminder.channelName} · starts ${IptvDeparture.countdown(reminder.startMs - now)}".replace("starts now", "starting now")
}
