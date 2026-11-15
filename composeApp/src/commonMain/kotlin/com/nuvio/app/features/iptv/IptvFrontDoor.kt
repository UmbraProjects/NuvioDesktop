package com.nuvio.app.features.iptv

/**
 * fork:live-tv-front-door - the front door (mockups "Nuvio Live TV: Less to choose", Front door row): Live TV opens
 * on one pick, with the starred channels on the left and the followed scores on the right. Pure rules here, tested
 * by IptvFrontDoorTest; the stage is DoorStage in IptvStageScreen.kt.
 */
enum class IptvDoorReason { Followed, StartsSoon, Usual, Film }

data class IptvDoorPick(val programme: IptvWallProgramme, val reason: IptvDoorReason) {
    /** Stable while the same programme is on the same channel. */
    val key: String get() = IptvDoorRules.keyOf(programme)
}

object IptvDoorRules {
    private const val MINUTE = 60_000L
    /** "Starts soon" on the front door means within this long. */
    const val SOON_MS = 5 * MINUTE
    /** The pick stays put this long, so the page does not keep changing under the viewer. */
    const val HOLD_MS = 15 * MINUTE
    /** A usual channel's programme shorter than this (a bulletin, a filler) is not worth putting up. */
    private const val SHORTEST_MS = 20 * MINUTE
    /** A film is only offered while it is still near its start. */
    private const val FILM_LATE_MS = 30 * MINUTE

    fun keyOf(p: IptvWallProgramme): String = "${p.channel.primary.uid}:${p.entry.startMs}:${IptvDecide.normalise(p.entry.title)}"

    /**
     * Every candidate, best first; the first rule that finds something wins, and later rules only fill in behind it
     * (for "Something else"):
     * 1. something followed that is on now ([followedLive]: followed matches already tied to a channel, then
     *    followed titles on now);
     * 2. something on [lineupUids] (or [usuals] when there is no lineup) starting within [SOON_MS];
     * 3. what is on the usual channels at this hour, in their order;
     * 4. a film under way for less than half an hour, best ranked first.
     */
    fun candidates(
        picks: List<IptvChannelNow>,
        follows: List<String>,
        followedLive: List<IptvWallProgramme>,
        lineupUids: Set<Int>,
        usuals: List<IptvMergedChannel>,
        now: Long,
        rank: (IptvWallProgramme) -> Int = { 0 },
    ): List<IptvDoorPick> {
        val onNow = picks.mapNotNull { on -> on.current(now)?.let { IptvWallProgramme(on.channel, it) } }
        val followed = (followedLive.filter { it.onNow(now) } +
            onNow.filter { IptvDecide.isFollowed(follows, it.entry.title) }.sortedByDescending(rank))
            .map { IptvDoorPick(it, IptvDoorReason.Followed) }
        val own = lineupUids.ifEmpty { usuals.mapTo(HashSet()) { it.primary.uid } }
        val soon = picks.asSequence()
            .filter { it.channel.primary.uid in own }
            .flatMap { on -> on.entries.asSequence().filter { it.startMs > now && it.startMs <= now + SOON_MS }.map { IptvWallProgramme(on.channel, it) } }
            .sortedWith(compareBy<IptvWallProgramme> { it.entry.startMs }.thenByDescending(rank))
            .map { IptvDoorPick(it, IptvDoorReason.StartsSoon) }
            .toList()
        val byUid = picks.associateBy { it.channel.primary.uid }
        val usual = usuals.mapNotNull { channel ->
            byUid[channel.primary.uid]?.current(now)
                ?.takeIf { it.endMs - it.startMs >= SHORTEST_MS }
                ?.let { IptvDoorPick(IptvWallProgramme(byUid.getValue(channel.primary.uid).channel, it), IptvDoorReason.Usual) }
        }
        val films = onNow.filter { it.entry.kind == IptvProgrammeKind.FILM && now - it.entry.startMs <= FILM_LATE_MS }
            .sortedByDescending(rank)
            .map { IptvDoorPick(it, IptvDoorReason.Film) }
        return (followed + soon + usual + films).distinctBy { IptvDecide.normalise(it.programme.entry.title) }
    }

    /**
     * The one shown: [held] while it is younger than [HOLD_MS], still on (or still to come) and not skipped, unless
     * something followed has come on since; else the first candidate not in [skipped].
     */
    fun choose(candidates: List<IptvDoorPick>, skipped: Set<String>, held: IptvDoorPick?, heldAt: Long, now: Long): IptvDoorPick? {
        val fresh = candidates.firstOrNull { it.key !in skipped }
        if (held != null && held.key !in skipped && now - heldAt < HOLD_MS && held.programme.entry.endMs > now) {
            val followedNow = fresh?.reason == IptvDoorReason.Followed && held.reason != IptvDoorReason.Followed
            if (!followedNow) return held
        }
        return fresh
    }

    /** The one line under the pick saying why it is there. */
    fun why(pick: IptvDoorPick, now: Long, lineupNumber: Int?, finished: String?): String {
        val e = pick.programme.entry
        return when (pick.reason) {
            IptvDoorReason.Followed -> "You follow this, and it is on now."
            IptvDoorReason.StartsSoon -> {
                val minutes = ((e.startMs - now + MINUTE - 1) / MINUTE).coerceAtLeast(1)
                "Starts in $minutes min. OK puts the channel on now, so you see it from the first minute."
            }
            IptvDoorReason.Usual -> (finished?.let { "$it · " } ?: "") + "Your usual at this hour" + (lineupNumber?.let { ", $it on Your 40" } ?: "") + "."
            IptvDoorReason.Film -> "A film that began ${((now - e.startMs) / MINUTE).coerceAtLeast(0)} min ago."
        }
    }

    /** The label over the pick. */
    fun label(reason: IptvDoorReason): String = when (reason) {
        IptvDoorReason.Followed -> "THE PICK · YOU FOLLOW THIS"
        IptvDoorReason.StartsSoon -> "THE PICK · STARTS SOON"
        IptvDoorReason.Usual -> "THE PICK · YOUR USUAL"
        IptvDoorReason.Film -> "THE PICK · A FILM"
    }

    /** What is next on the viewer's channels (not the pick), soonest first. */
    fun nextUp(upcoming: List<IptvWallProgramme>, ownUids: Set<Int>, now: Long, exclude: String?, limit: Int = 3): List<IptvWallProgramme> =
        upcoming.asSequence()
            .filter { it.entry.startMs > now && it.channel.primary.uid in ownUids && keyOf(it) != exclude }
            .sortedBy { it.entry.startMs }
            .distinctBy { IptvDecide.normalise(it.entry.title) }
            .take(limit)
            .toList()
}

/** fork:live-tv-front-door - the triage is offered by itself once per playlist per session, not on every visit. */
object IptvFirstRun {
    private val asked = HashSet<String>()

    @Synchronized
    fun shouldAsk(playlistId: String): Boolean = asked.add(playlistId)
}
