package com.nuvio.app.features.iptv

/** fork:live-tv-ask-ai - one programme the AI picked: which airing (channel and start) and its reason. */
data class IptvAiChoice(val uid: Int, val startMs: Long, val why: String)

/** The AI's answer to one question, best first. */
data class IptvAiPick(val question: String, val choices: List<IptvAiChoice>)

/**
 * fork:live-tv-ask-ai (Settings > Live TV > "Let the AI answer Just ask"): the question and what is on now or starting
 * soon go to the viewer's own AI key; it answers with up to three numbered picks and a reason each. The rules' answer
 * stays as the fallback, and after the AI's picks "Not that" carries on with the rules.
 */
object IptvAskAiRules {
    const val MAX_CANDIDATES = 60
    const val MAX_PICKS = 3
    private const val DESC_CHARS = 110

    const val SYSTEM =
        "You help someone choose one thing to watch on live TV right now. You get their request and a numbered list of " +
            "programmes that are on now or start soon. Pick up to three that best fit the request, best first. Only pick " +
            "from the list. Prefer what is on now unless the request says otherwise; respect any time they give. Give each " +
            "pick one short, plain reason (under 20 words) that speaks to the request, not a summary of the programme. " +
            "Answer with JSON only: {\"picks\":[{\"i\":3,\"why\":\"...\"}]}"

    private fun clock(ms: Long) = IptvStageRules.clock(ms)

    /** One line per candidate, numbered from 1. */
    fun prompt(question: String, candidates: List<IptvWallProgramme>, now: Long): String = buildString {
        append("Request: ").appendLine(question.trim())
        append("It is ").append(clock(now)).appendLine(".")
        appendLine("Programmes:")
        candidates.forEachIndexed { i, p ->
            val e = p.entry
            append(i + 1).append(". ").append(e.title)
            e.episode?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            append(" | ").append(p.channel.primary.name)
            e.kind?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it.lowercase()) }
            val left = (e.endMs - maxOf(now, e.startMs)) / 60_000L
            if (e.startMs <= now) append(" | on now, $left min left") else append(" | starts ${clock(e.startMs)}, $left min long")
            if (e.isNew) append(" | new")
            e.desc?.takeIf { it.isNotBlank() }?.let { d ->
                append(" | ").append(if (d.length > DESC_CHARS) d.take(DESC_CHARS).substringBeforeLast(' ') + "..." else d)
            }
            appendLine()
        }
    }

    private val PICK = Regex("\\{[^{}]*?\"i\"\\s*:\\s*(\\d+)[^{}]*?\"why\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\\}")
    private val PICK_REVERSED = Regex("\\{[^{}]*?\"why\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"i\"\\s*:\\s*(\\d+)[^{}]*?\\}")

    /** The picks as 0-based indexes with their reasons; anything off the list or repeated is dropped. */
    fun parse(reply: String, count: Int): List<Pair<Int, String>> {
        val out = LinkedHashMap<Int, String>()
        fun take(index: Int?, why: String) {
            if (index == null || index !in 1..count || out.size >= MAX_PICKS) return
            out.putIfAbsent(index - 1, why.replace("\\\"", "\"").replace("\\n", " ").trim())
        }
        val found = (PICK.findAll(reply).map { it.range.first to (it.groupValues[1].toIntOrNull() to it.groupValues[2]) } +
            PICK_REVERSED.findAll(reply).map { it.range.first to (it.groupValues[2].toIntOrNull() to it.groupValues[1]) })
            .sortedBy { it.first }
        found.forEach { (_, pick) -> take(pick.first, pick.second) }
        return out.map { (i, why) -> i to why }
    }

    /** The picks as airings, kept by channel and start so they survive the guide being read again. */
    fun choices(parsed: List<Pair<Int, String>>, candidates: List<IptvWallProgramme>): List<IptvAiChoice> =
        parsed.mapNotNull { (i, why) -> candidates.getOrNull(i)?.let { IptvAiChoice(it.channel.primary.uid, it.entry.startMs, why) } }

    /**
     * What to send: what is on now (with ten minutes or more left) or starting within the hour, best-liked first, at
     * most [MAX_CANDIDATES], one airing per title.
     */
    fun candidates(guide: List<IptvWallProgramme>, now: Long, rank: (IptvWallProgramme) -> Int): List<IptvWallProgramme> =
        guide.filter { p ->
            (p.onNow(now) && p.entry.endMs - now >= 10 * 60_000L) || (p.entry.startMs > now && p.entry.startMs <= now + 60 * 60_000L)
        }
            .sortedWith(compareByDescending<IptvWallProgramme> { rank(it) }.thenBy { it.entry.startMs })
            .distinctBy { IptvDecide.normalise(it.entry.title) }
            .take(MAX_CANDIDATES)

    /**
     * The answer to show after [skips] presses of "Not that": the AI's picks in turn (those still in [guide]), then
     * null so the rules carry on with the skips left over (see [rulesSkips]).
     */
    fun answer(pick: IptvAiPick, guide: List<IptvWallProgramme>, skips: Int, now: Long): IptvAskAnswer? {
        val live = live(pick, guide, now)
        val (p, why) = live.getOrNull(skips) ?: return null
        val left = (p.entry.endMs - maxOf(now, p.entry.startMs)) / 60_000L
        val timing = if (p.onNow(now)) "On now, $left min left" else "Starts in ${(p.entry.startMs - now) / 60_000L} min, $left min long"
        val reasons = buildList {
            if (why.isNotBlank()) add("Why the AI picked it" to why)
            add("$left minutes ${if (p.onNow(now)) "left" else "long"}" to timing)
            add("On" to p.channel.primary.name)
        }
        // The rules carry on after the last of them, so there is always one more.
        return IptvAskAnswer(p, if (why.isBlank()) timing else "$timing; $why", live.size - skips, reasons)
    }

    /** How many "Not that" presses are left for the rules once the AI's picks are used up. */
    fun rulesSkips(pick: IptvAiPick?, guide: List<IptvWallProgramme>, skips: Int, now: Long): Int =
        if (pick == null) skips else (skips - live(pick, guide, now).size).coerceAtLeast(0)

    private fun live(pick: IptvAiPick, guide: List<IptvWallProgramme>, now: Long): List<Pair<IptvWallProgramme, String>> =
        pick.choices.mapNotNull { c ->
            guide.firstOrNull { it.channel.primary.uid == c.uid && it.entry.startMs == c.startMs && it.entry.endMs > now }?.let { it to c.why }
        }
}
