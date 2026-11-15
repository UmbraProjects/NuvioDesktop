package com.nuvio.app.features.iptv

/**
 * fork:live-tv-polish - holding OK on any programme on the stages opens its menu, so following something is one
 * press away wherever it shows. Pure rules here (IptvFrontDoorTest); the menu is drawn in IptvStageScreen.kt.
 */
enum class IptvProgrammeAction { Watch, Follow, Unfollow, Remind, Unstar, Close }

object IptvProgrammeMenu {
    /**
     * What the menu offers for [p]: the one OK action, follow or stop following, a reminder when it has not begun,
     * Unstar when its channel is a favourite, Close.
     */
    fun actions(p: IptvWallProgramme, now: Long, followed: Boolean, starred: Boolean = false): List<IptvProgrammeAction> = buildList {
        add(IptvProgrammeAction.Watch)
        add(if (followed) IptvProgrammeAction.Unfollow else IptvProgrammeAction.Follow)
        if (p.entry.startMs > now + 60_000L) add(IptvProgrammeAction.Remind)
        if (starred) add(IptvProgrammeAction.Unstar)
        add(IptvProgrammeAction.Close)
    }

    /** The button's words. */
    fun label(action: IptvProgrammeAction, p: IptvWallProgramme, now: Long, clock: (Long) -> String): String = when (action) {
        IptvProgrammeAction.Watch -> when {
            p.entry.endMs <= now -> "Play from the start"
            p.entry.startMs > now + 60_000L -> "Tune in at ${clock(p.entry.startMs)}"
            else -> "Watch now"
        }
        IptvProgrammeAction.Follow -> "Follow ${followName(p.entry.title)}"
        IptvProgrammeAction.Unfollow -> "Stop following ${followName(p.entry.title)}"
        IptvProgrammeAction.Remind -> "Remind me at ${clock(p.entry.startMs)}"
        IptvProgrammeAction.Unstar -> "Unstar ${p.channel.primary.name}"
        IptvProgrammeAction.Close -> "Close"
    }

    /** What gets followed: the title without a live mark or an episode tail ("Friends", not "Friends - S07E09"). */
    fun followName(title: String): String =
        title.replace(LIVE_MARK, "").replace(EPISODE_TAIL, "").trim().trimEnd('-', ':', '|').trim().ifBlank { title.trim() }

    private val LIVE_MARK = Regex("""[ᴸᴵⱽᴱᵛᵉⁱ]{3,}|\(live\)|\blive\s*$""", RegexOption.IGNORE_CASE)
    private val EPISODE_TAIL = Regex("""\s*[-:|]?\s*S\d{1,2}\s*E\d{1,3}\s*$""", RegexOption.IGNORE_CASE)
}
