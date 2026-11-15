package com.nuvio.app.features.iptv

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.Serializable

/** fork:live-tv-stage - one line asked on Just ask. */
@Serializable
data class IptvAsked(val text: String, val at: Long)

/**
 * fork:live-tv-stage (Settings -> Live TV -> Stage): Live TV as full-screen stages, one decision each, laid out as the
 * mockups ("Nuvio Live TV: Less to choose"): a centred heading, a strip of the stages, then two equal side panels round
 * a centre, or three equal columns. Nothing moves or resizes with what is in it. Pure rules here, tested by
 * IptvStageTest; the screen is IptvStageScreen.kt.
 */
enum class IptvStage(val label: String) {
    /** fork:live-tv-front-door - the front door: one pick, favourites and scores. First, and where Live TV opens. */
    Door("Home"),
    Three("Three things"),
    Soon("Starting soon"),
    Minutes("I have time"),
    Order("Tonight"),
    Earlier("Earlier today"),
    Following("Following"),
    Ask("Just ask"),
    Lineup("Your 40"),
    /** fork:live-tv-brakes - the honest tally; on the strip only with Brakes on. */
    Week("Your week"),
    /** fork:live-tv-views - the extra ways to see everything, each on the strip only when switched on in Settings. */
    Map("Map"),
    Mosaic("For you now"),
    River("River"),
    Wall("Picture wall"),
    Grid("Tonight grid"),
    Dial("Dial"),
}

/** What the departure board can be narrowed to. */
enum class IptvSoonKind(val label: String) { Films("Films"), Sport("Sport"), Other("Everything else") }

object IptvStageRules {
    val SOON_WINDOWS = listOf(15, 30, 60)
    private const val MINUTE = 60_000L

    fun soonKind(entry: IptvEpgEntry): IptvSoonKind = when (entry.kind) {
        IptvProgrammeKind.FILM -> IptvSoonKind.Films
        IptvProgrammeKind.SPORT -> IptvSoonKind.Sport
        else -> IptvSoonKind.Other
    }

    /**
     * The departure board: programmes that have not begun, starting inside [windowMin], of the [kinds] asked, on the
     * lineup when [lineupOnly]. Second: up to [later] more from past the window, shown dimmed so the board never looks
     * empty when something good is just outside it.
     */
    fun departures(
        upcoming: List<IptvWallProgramme>,
        now: Long,
        windowMin: Int,
        kinds: Set<IptvSoonKind>,
        lineupOnly: Boolean,
        lineupUids: Set<Int>,
        later: Int = 4,
    ): Pair<List<IptvWallProgramme>, List<IptvWallProgramme>> {
        val wanted = upcoming.asSequence()
            .filter { it.entry.startMs > now }
            .filter { soonKind(it.entry) in kinds }
            .filter { !lineupOnly || lineupUids.isEmpty() || it.channel.primary.uid in lineupUids }
            .sortedWith(compareBy({ it.entry.startMs }, { it.channel.primary.name }))
            .toList()
        val limit = now + windowMin * MINUTE
        return wanted.filter { it.entry.startMs <= limit } to wanted.filter { it.entry.startMs > limit }.take(later)
    }

    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    /** "55 min", "1h 55m". */
    fun length(ms: Long): String {
        val minutes = (ms / MINUTE).toInt().coerceAtLeast(0)
        return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
    }

    /** "Today", "Yesterday", then the weekday. */
    fun dayLabel(ms: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            today.plusDays(1) -> "Tomorrow"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)
        }
    }

    /** "Sat 22:20". */
    fun weekLabel(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        return at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK) + " " + clock(ms, zone)
    }

    /** Programmes grouped by the day they were shown, today first, best first inside a day (the order given). */
    fun byDay(list: List<IptvWallProgramme>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<IptvWallProgramme>>> =
        list.groupBy { Instant.ofEpochMilli(it.entry.startMs).atZone(zone).toLocalDate() }
            .entries.sortedByDescending { it.key }
            .map { (_, items) -> dayLabel(items.first().entry.startMs, now, zone) to items }

    /** Where a followed thing was found: "on now, Sky Sports F1", "tonight 20:00 on BBC One", "Saturday 22:20 on BBC One". */
    fun foundText(p: IptvWallProgramme, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val channel = p.channel.primary.name
        if (p.onNow(now)) return "on now, $channel"
        val day = dayLabel(p.entry.startMs, now, zone)
        val whenText = if (day == "Today") "tonight ${clock(p.entry.startMs, zone)}" else "$day ${clock(p.entry.startMs, zone)}"
        return "$whenText on $channel"
    }

    /** "On now", "in 2 min", "in 3 h", "in 2 days". */
    fun untilText(entry: IptvEpgEntry, now: Long): String {
        if (entry.startMs <= now) return "On now"
        val minutes = (entry.startMs - now + MINUTE - 1) / MINUTE
        return when {
            minutes < 60 -> "in $minutes min"
            minutes < 24 * 60 -> "in ${minutes / 60} h"
            else -> "in ${minutes / (24 * 60)} days"
        }
    }
}

/** fork:live-tv-stage - one block of things that can be highlighted: a list ([columns] = 1), a row of chips, a grid. */
data class IptvStageGroup(val id: String, val count: Int, val columns: Int = 1)

/** The highlight: which column of the stage, which group in it, which item in the group. */
data class IptvStageFocus(val column: Int, val group: Int, val item: Int)

enum class IptvStageMove { Up, Down, Left, Right }

/**
 * Moves the highlight across a stage laid out as columns (left panel, centre, right panel) of groups stacked top to
 * bottom. Empty groups are stepped over. Null: Up from the top, which goes to the strip of stages.
 */
object IptvStageFocusRules {
    private fun groupsOf(layout: List<List<IptvStageGroup>>, column: Int) = layout.getOrNull(column).orEmpty()

    /** The first place that can be highlighted, preferring [column]. */
    fun first(layout: List<List<IptvStageGroup>>, column: Int = 0): IptvStageFocus? {
        val order = listOf(column) + layout.indices.filter { it != column }
        for (c in order) {
            val g = groupsOf(layout, c).indexOfFirst { it.count > 0 }
            if (g >= 0) return IptvStageFocus(c, g, 0)
        }
        return null
    }

    /** [focus] kept inside the layout as it is now (a list can shrink under it). */
    fun clamp(layout: List<List<IptvStageGroup>>, focus: IptvStageFocus): IptvStageFocus? {
        val group = groupsOf(layout, focus.column).getOrNull(focus.group)
        if (group == null || group.count == 0) return first(layout, focus.column)
        return focus.copy(item = focus.item.coerceIn(0, group.count - 1))
    }

    fun move(layout: List<List<IptvStageGroup>>, focus: IptvStageFocus, move: IptvStageMove): IptvStageFocus? {
        val at = clamp(layout, focus) ?: return null
        val groups = groupsOf(layout, at.column)
        val group = groups[at.group]
        val cols = group.columns.coerceAtLeast(1)
        val row = at.item / cols
        val lastRow = (group.count - 1) / cols
        return when (move) {
            IptvStageMove.Up -> when {
                row > 0 -> at.copy(item = at.item - cols)
                else -> {
                    val above = (at.group - 1 downTo 0).firstOrNull { groups[it].count > 0 }
                    if (above == null) null else {
                        val g = groups[above]
                        val c = g.columns.coerceAtLeast(1)
                        IptvStageFocus(at.column, above, (((g.count - 1) / c) * c + at.item % cols).coerceAtMost(g.count - 1))
                    }
                }
            }
            IptvStageMove.Down -> when {
                row < lastRow -> at.copy(item = (at.item + cols).coerceAtMost(group.count - 1))
                else -> {
                    val below = (at.group + 1 until groups.size).firstOrNull { groups[it].count > 0 }
                    if (below == null) at else IptvStageFocus(at.column, below, (at.item % cols).coerceAtMost(groups[below].count - 1))
                }
            }
            IptvStageMove.Left -> when {
                cols > 1 && at.item % cols > 0 -> at.copy(item = at.item - 1)
                else -> side(layout, at, row, -1) ?: at
            }
            IptvStageMove.Right -> when {
                cols > 1 && at.item % cols < cols - 1 && at.item + 1 < group.count -> at.copy(item = at.item + 1)
                else -> side(layout, at, row, 1) ?: at
            }
        }
    }

    /** Into the next column with something in it: the group at the same height, the row at the same height. */
    private fun side(layout: List<List<IptvStageGroup>>, at: IptvStageFocus, row: Int, step: Int): IptvStageFocus? {
        var column = at.column + step
        while (column in layout.indices) {
            val groups = layout[column]
            if (groups.any { it.count > 0 }) {
                val g = (at.group.coerceAtMost(groups.size - 1) downTo 0).firstOrNull { groups[it].count > 0 }
                    ?: groups.indexOfFirst { it.count > 0 }
                val target = groups[g]
                val c = target.columns.coerceAtLeast(1)
                val r = row.coerceAtMost((target.count - 1) / c)
                val item = if (step < 0) (r * c + c - 1) else r * c
                return IptvStageFocus(column, g, item.coerceAtMost(target.count - 1))
            }
            column += step
        }
        return null
    }
}
