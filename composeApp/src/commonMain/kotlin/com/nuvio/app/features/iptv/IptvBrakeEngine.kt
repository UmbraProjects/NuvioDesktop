package com.nuvio.app.features.iptv

/**
 * fork:live-tv-brakes - the live side of [IptvBrakeRules]. The player calls [noteChange] on every
 * channel change and [tick] once a minute; a card is shown through [showChoice] (the desktop HUD)
 * and the answer comes back through [answer]. Nothing here runs unless the Brakes switch is on.
 */
object IptvBrakeEngine {
    /** Set by the desktop app: draws the card in the player (title, line, options, focused index). */
    var showChoice: ((String, String, List<String>, Int) -> Unit)? = null

    private enum class Kind { ZapBudget, EndCard }

    private var zaps = emptyList<Long>()
    private var pending: Kind? = null
    private var options: List<String> = emptyList()
    /** fork:live-tv-brakes - the programme last picked (normalised title, end), and the approved running order's end. */
    private var picked: Pair<String, Long>? = null
    private var orderEndMs: Long? = null

    /** A programme picked on a stage or reached through a reminder: its end gets the card. */
    fun chose(title: String, endMs: Long) {
        picked = IptvDecide.normalise(title) to endMs
    }

    /** The running order was approved (its last slot's end) or cancelled (null). */
    fun orderApproved(lastEndMs: Long?) {
        orderEndMs = lastEndMs
    }

    private fun card(kind: Kind, title: String, line: String, choices: List<String>, focused: Int) {
        IptvReminderEngine.dropCard()
        pending = kind
        options = choices
        showChoice?.invoke(title, line, choices, focused)
    }
    private var endShownFor: Long? = null
    private var stayKey: String? = null
    private var stayStartMs = 0L

    private fun on() = IptvRepository.settings.value.brakes
    private fun night(now: Long) = IptvDecide.nightOf(now)

    /** A channel change: counted in the tally, and the zap budget offered the brake once a night. */
    fun noteChange(now: Long = System.currentTimeMillis()) {
        if (!on()) return
        IptvRepository.tally(night(now)) { it.copy(changes = it.changes + 1) }
        zaps = IptvBrakeRules.zapped(zaps, now)
        stayKey = null
        val settings = IptvRepository.settings.value
        if (IptvBrakeRules.overBudget(zaps, settings.brakeOffered, night(now)) && pending == null) {
            IptvRepository.brakeOffered(night(now))
            zaps = emptyList()
            card(Kind.ZapBudget, "That's a lot of flicking", "Nothing has held you for a while.", IptvBrakeRules.zapOptions(settings.stage), 0)
        }
    }

    /** Once a minute while a channel plays: the minute is counted, and the end card shown when a programme you stayed on ends. */
    fun tick(playlist: IptvPlaylist, channel: IptvChannel, now: Long = System.currentTimeMillis()) {
        if (!on()) return
        IptvRepository.tally(night(now)) { it.copy(minutes = it.minutes + 1) }
        val key = "${playlist.id}:${channel.uid}"
        if (stayKey != key) {
            stayKey = key
            stayStartMs = now
        }
        val entry = IptvEpgRepository.guideEntries(playlist, channel).firstOrNull { now >= it.startMs && now < it.endMs } ?: return
        if (pending != null) return
        // Stay counts from the later of tuning in and the programme's start.
        val stayed = now - maxOf(stayStartMs, entry.startMs)
        val lastOfOrder = orderEndMs == entry.endMs
        val wasPicked = picked?.let { it.first == IptvDecide.normalise(entry.title) && it.second == entry.endMs } == true
        val chosen = lastOfOrder || IptvBrakeRules.chosen(stayStartMs, entry.startMs, wasPicked)
        if (chosen && IptvBrakeRules.endCardDue(entry.endMs, now, stayed, endShownFor)) {
            endShownFor = entry.endMs
            IptvRepository.tally(night(now)) { it.copy(finished = it.finished + 1) }
            val stops = listOf(IptvBrakeRules.STOP_HERE, IptvBrakeRules.ONE_MORE)
            if (lastOfOrder) {
                orderEndMs = null
                card(Kind.EndCard, "That's tonight", "${entry.title} was the last of the running order.", stops, 0)
            } else {
                card(Kind.EndCard, entry.title, "That's the end of it.", stops, 0)
            }
        }
    }

    /** fork:live-tv-reminder-card - the reminder card took the HUD's place. */
    fun dropCard() {
        pending = null
        options = emptyList()
    }

    /** The viewer's answer to the card on screen: -1 is dismissed. */
    fun answer(index: Int, now: Long = System.currentTimeMillis()): IptvBrakeAnswer {
        pending ?: return IptvBrakeAnswer.Dismissed
        pending = null
        val answer = IptvBrakeRules.answerOf(options, index)
        options = emptyList()
        if (answer == IptvBrakeAnswer.TurnOff) IptvRepository.tally(night(now)) { it.copy(stopped = it.stopped + 1) }
        return answer
    }
}
