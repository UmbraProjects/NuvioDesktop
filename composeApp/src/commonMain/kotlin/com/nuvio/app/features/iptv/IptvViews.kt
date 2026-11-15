package com.nuvio.app.features.iptv

import java.time.Instant
import java.time.ZoneId

/*
 * fork:live-tv-views - six more ways to see everything on the kept channels, each a stage switched on in Settings ->
 * Live TV -> More views: a map that zooms from kinds of channel into what is on, a mosaic sized by how likely the
 * viewer is to want each thing, a river of programmes by kind rather than by channel, a wall of real pictures, a
 * grid of tonight by kind and half-hour, and a dial that flips channel by channel. Pure rules here (IptvViewsTest);
 * the screens are IptvStageViews.kt.
 */

/** One block of the map: a kind of programme and what of it is on, the likeliest first. */
class IptvViewBlock(val kind: IptvChannelKind, val programmes: List<IptvWallProgramme>)

/** The mosaic's three sizes of tile. */
class IptvMosaic(val big: List<IptvWallProgramme>, val mid: List<IptvWallProgramme>, val small: List<IptvWallProgramme>)

/** One lane of the river: a kind, and programmes along it that do not overlap, in time order. */
class IptvRiverLane(val kind: IptvChannelKind, val items: List<IptvWallProgramme>)

/** One cell of the tonight grid: what of [kind] starts in the half-hour from [slotMs], the likeliest first. */
class IptvGridCell(val kind: IptvChannelKind, val slotMs: Long, val programmes: List<IptvWallProgramme>)

/** One card of the dial: a channel with its number, and what is on and next. */
class IptvDialCard(val number: Int, val channel: IptvMergedChannel, val now: IptvEpgEntry?, val next: IptvEpgEntry?)

internal object IptvViewRules {
    val VIEWS: List<IptvStage> = listOf(IptvStage.Map, IptvStage.Mosaic, IptvStage.River, IptvStage.Wall, IptvStage.Grid, IptvStage.Dial)

    /** The kinds the river and the grid have lanes for, in their order. */
    val LANES: List<IptvChannelKind> = listOf(
        IptvChannelKind.Sport, IptvChannelKind.Films, IptvChannelKind.Entertainment,
        IptvChannelKind.Documentaries, IptvChannelKind.News, IptvChannelKind.Kids,
    )

    private const val MINUTE = 60_000L
    private const val HALF_HOUR = 30 * MINUTE

    /** What a programme is: what the guide says, else what its channel's category is for. */
    fun kindOf(entry: IptvEpgEntry, categoryName: String): IptvChannelKind = when (entry.kind) {
        IptvProgrammeKind.FILM -> IptvChannelKind.Films
        IptvProgrammeKind.SPORT -> IptvChannelKind.Sport
        IptvProgrammeKind.NEWS -> IptvChannelKind.News
        IptvProgrammeKind.KIDS -> IptvChannelKind.Kids
        else -> IptvTriageRules.kindOf(categoryName).let { if (it in LANES) it else IptvChannelKind.Entertainment }
    }

    /** Every copy of a programme counts once: the same title (live marks aside) at the same start. */
    private fun copyKey(p: IptvWallProgramme) = "${IptvSportTitle.sameKey(p.entry.title)}@${p.entry.startMs}"

    /** Programmes of [picks] that [wanted] keeps, each with its kind, one per copy (the better channel kept). */
    private fun gather(picks: List<IptvChannelNow>, wanted: (IptvEpgEntry) -> Boolean): List<Pair<IptvWallProgramme, IptvChannelKind>> =
        picks.flatMap { on -> on.entries.filter(wanted).map { IptvWallProgramme(on.channel, it) to kindOf(it, on.categoryName) } }
            .groupBy { copyKey(it.first) }.values
            .map { same -> same.maxByOrNull { IptvChannelMerge.rank(it.first.channel.primary.name) }!! }

    private fun onNow(now: Long) = { e: IptvEpgEntry -> e.startMs <= now && e.endMs > now }

    /** The map's blocks: what is on now by kind, the kind with most on first, the likeliest first inside each. */
    fun blocks(picks: List<IptvChannelNow>, now: Long, rank: (IptvWallProgramme) -> Int): List<IptvViewBlock> =
        gather(picks, onNow(now)).groupBy { it.second }
            .map { (kind, items) -> IptvViewBlock(kind, items.map { it.first }.sortedByDescending(rank)) }
            .sortedByDescending { it.programmes.size }

    /** The mosaic: everything on now, the likeliest first, cut into [bigCount] big, [midCount] middle and [smallCount] small. */
    fun mosaic(picks: List<IptvChannelNow>, now: Long, rank: (IptvWallProgramme) -> Int, bigCount: Int = 2, midCount: Int = 6, smallCount: Int = 24): IptvMosaic {
        val ranked = gather(picks, onNow(now)).map { it.first }
            .sortedWith(compareByDescending<IptvWallProgramme>(rank).thenBy { now - it.entry.startMs })
        return IptvMosaic(
            ranked.take(bigCount),
            ranked.drop(bigCount).take(midCount),
            ranked.drop(bigCount + midCount).take(smallCount),
        )
    }

    /**
     * The river from [fromMs] to [toMs]: for each lane, the likeliest programmes that fit without overlapping, then in
     * time order. Up to [perLane] each.
     */
    fun river(picks: List<IptvChannelNow>, fromMs: Long, toMs: Long, rank: (IptvWallProgramme) -> Int, perLane: Int = 10): List<IptvRiverLane> {
        val byKind = gather(picks) { it.endMs > fromMs && it.startMs < toMs && it.endMs - it.startMs >= 15 * MINUTE }.groupBy { it.second }
        return LANES.map { kind ->
            val chosen = ArrayList<IptvWallProgramme>()
            byKind[kind].orEmpty().map { it.first }.sortedByDescending(rank).forEach { p ->
                if (chosen.size < perLane && chosen.none { it.entry.startMs < p.entry.endMs && p.entry.startMs < it.entry.endMs }) chosen += p
            }
            IptvRiverLane(kind, chosen.sortedBy { it.entry.startMs })
        }
    }

    /** The half-hour [now] is in. */
    fun slotOf(now: Long): Long = now - Math.floorMod(now, HALF_HOUR)

    /**
     * The tonight grid: a row per lane, a column per half-hour from the one [now] is in, [columns] of them. A cell holds
     * what starts in its half-hour, the likeliest first. fork:live-tv-polish - the first column no longer takes what
     * began earlier ("Sport at 18:00" listed things from 15:00): the cell says "starting" and means it.
     */
    fun grid(picks: List<IptvChannelNow>, now: Long, rank: (IptvWallProgramme) -> Int, columns: Int = 10): List<IptvGridCell> {
        val first = slotOf(now)
        val end = first + columns * HALF_HOUR
        val found = gather(picks) { it.endMs > now && it.startMs < end }
        return LANES.flatMap { kind ->
            val ofKind = found.filter { it.second == kind }.map { it.first }
            (0 until columns).map { col ->
                val slot = first + col * HALF_HOUR
                val inSlot = ofKind.filter { p -> p.entry.startMs in slot until slot + HALF_HOUR }
                IptvGridCell(kind, slot, inSlot.sortedByDescending(rank))
            }
        }
    }

    /** How busy a cell is, 0 to 6, for its shade: what starts there, against the busiest cell. */
    fun shade(count: Int, busiest: Int): Int = if (count == 0 || busiest == 0) 0 else (1 + 5 * count / busiest).coerceIn(1, 6)

    /** "18:00" for a slot. */
    fun slotLabel(slotMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(slotMs).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    /**
     * The dial's cards: Your 40 in its own numbers, then every other kept channel with a guide, numbered on from there
     * in the playlist's order. A channel is on the dial once.
     */
    fun dial(lineup: List<IptvStageChannel>, picks: List<IptvChannelNow>, now: Long): List<IptvDialCard> {
        val cards = lineup.map { IptvDialCard(it.number, it.channel, it.now, it.next) }.toMutableList()
        val seen = lineup.mapTo(HashSet()) { it.channel.primary.uid }
        var number = (lineup.maxOfOrNull { it.number } ?: 0)
        picks.sortedBy { it.channel.primary.num }.forEach { on ->
            if (seen.add(on.channel.primary.uid)) cards += IptvDialCard(++number, on.channel, on.current(now), on.next(now))
        }
        return cards
    }

    /** The card a typed [number] goes to, if any. */
    fun dialIndexOf(cards: List<IptvDialCard>, number: Int): Int? = cards.indexOfFirst { it.number == number }.takeIf { it >= 0 }

    /** The wall's channels: Your 40 first, then the usual channels, then the busiest kept ones, [limit] at most. */
    fun wall(lineup: List<IptvStageChannel>, picks: List<IptvChannelNow>, usualKeys: Set<String>, limit: Int = 12): List<IptvMergedChannel> {
        val out = LinkedHashMap<Int, IptvMergedChannel>()
        lineup.forEach { out.putIfAbsent(it.channel.primary.uid, it.channel) }
        picks.filter { it.channel.primary.key in usualKeys }.forEach { out.putIfAbsent(it.channel.primary.uid, it.channel) }
        picks.forEach { out.putIfAbsent(it.channel.primary.uid, it.channel) }
        return out.values.take(limit)
    }
}
