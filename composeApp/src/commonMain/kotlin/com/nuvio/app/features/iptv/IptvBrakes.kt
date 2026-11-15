package com.nuvio.app.features.iptv

import kotlinx.serialization.Serializable

/** fork:live-tv-brakes - one night's honest count: minutes watched, channel changes, programmes seen to the end, brakes taken. */
@Serializable
data class IptvNight(
    val minutes: Int = 0,
    val changes: Int = 0,
    val finished: Int = 0,
    val stopped: Int = 0,
)

/** What the viewer picked on a brake card. */
enum class IptvBrakeAnswer { KeepGoing, TurnOff, PickForMe, Dismissed }

/**
 * fork:live-tv-brakes (Settings -> Live TV -> Brakes): three gentle stops that never nag.
 * - Zap budget: five channel changes inside two minutes offers a choice, once a night.
 * - End card: after a programme you stayed with, "Stop here" is the focused answer.
 * - Your week: the tally, kept as plain numbers for [KEEP_NIGHTS] nights.
 */
object IptvBrakeRules {
    const val ZAP_LIMIT = 5
    const val ZAP_WINDOW_MS = 2 * 60_000L
    const val KEEP_NIGHTS = 120
    const val STAY_MINUTES = 15
    /** Tuned in this long after a programme began still counts as choosing it. */
    const val CHOSEN_GRACE_MIN = 10

    const val KEEP_FLICKING = "Keep flicking"
    const val PICK_FOR_ME = "Pick something for me"
    const val TURN_OFF = "Turn Live TV off"
    const val STOP_HERE = "Stop here"
    const val ONE_MORE = "One more"

    /** The zap card's answers: with the Stage on, the middle one leaves for Three things. */
    fun zapOptions(stageOn: Boolean): List<String> =
        if (stageOn) listOf(KEEP_FLICKING, PICK_FOR_ME, TURN_OFF) else listOf(KEEP_FLICKING, TURN_OFF)

    /** What an answer on a card means; -1 (or out of range) is dismissed. */
    fun answerOf(options: List<String>, index: Int): IptvBrakeAnswer = when (options.getOrNull(index)) {
        TURN_OFF, STOP_HERE -> IptvBrakeAnswer.TurnOff
        PICK_FOR_ME -> IptvBrakeAnswer.PickForMe
        null -> IptvBrakeAnswer.Dismissed
        else -> IptvBrakeAnswer.KeepGoing
    }

    /**
     * A programme the viewer chose rather than landed on: picked on a stage, reached through a reminder, or tuned in
     * to within [CHOSEN_GRACE_MIN] minutes of its start.
     */
    fun chosen(stayStartMs: Long, entryStartMs: Long, picked: Boolean): Boolean =
        picked || stayStartMs <= entryStartMs + CHOSEN_GRACE_MIN * 60_000L

    /** Channel changes still inside the window, with [now] added. */
    fun zapped(times: List<Long>, now: Long): List<Long> =
        (times.filter { now - it in 0 until ZAP_WINDOW_MS } + now)

    /** True when the changes in the window hit the budget and tonight has not been offered the brake yet. */
    fun overBudget(times: List<Long>, offeredNight: Long?, night: Long): Boolean =
        times.size >= ZAP_LIMIT && offeredNight != night

    /** The end card shows from half a minute before the programme ends, only after the viewer stayed [STAY_MINUTES] on it. */
    fun endCardDue(endMs: Long, now: Long, stayedMs: Long, shownFor: Long?): Boolean =
        shownFor != endMs && stayedMs >= STAY_MINUTES * 60_000L && now >= endMs - 30_000L && now <= endMs + 2 * 60_000L

    fun nightKey(night: Long): String = night.toString()

    fun add(tally: Map<String, IptvNight>, night: Long, change: (IptvNight) -> IptvNight): Map<String, IptvNight> {
        val key = nightKey(night)
        return prune(tally + (key to change(tally[key] ?: IptvNight())), night)
    }

    /** Drops nights older than [KEEP_NIGHTS]. */
    fun prune(tally: Map<String, IptvNight>, night: Long): Map<String, IptvNight> =
        tally.filterKeys { key -> (key.toLongOrNull()?.let { night - it in 0 until KEEP_NIGHTS } ?: false) }

    /** The last seven nights, tonight included, summed. */
    fun week(tally: Map<String, IptvNight>, night: Long): IptvNight {
        var sum = IptvNight()
        for (day in 0 until 7) {
            val n = tally[nightKey(night - day)] ?: continue
            sum = IptvNight(sum.minutes + n.minutes, sum.changes + n.changes, sum.finished + n.finished, sum.stopped + n.stopped)
        }
        return sum
    }

    fun hoursText(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** The last seven nights, oldest first, tonight last: the bars of Your week. */
    fun nights(tally: Map<String, IptvNight>, night: Long): List<Pair<Long, IptvNight>> =
        (6 downTo 0).map { day -> (night - day) to (tally[nightKey(night - day)] ?: IptvNight()) }

    /** "Tonight", "Last night", then the weekday the night began on ("Mon"). */
    fun nightLabel(night: Long, tonight: Long): String = when (tonight - night) {
        0L -> "Tonight"
        1L -> "Last night"
        else -> java.time.LocalDate.ofEpochDay(night).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)
    }

    /** Channel changes per hour watched, for the week ("12 an hour"); null without an hour to go on. */
    fun changesPerHour(week: IptvNight): Int? = if (week.minutes < 60) null else (week.changes * 60 / week.minutes)

    /** The one honest line for the week. */
    fun weekLine(week: IptvNight): String =
        "${hoursText(week.minutes)} watching, ${week.changes} channel changes, ${week.finished} programmes seen to the end"
}
