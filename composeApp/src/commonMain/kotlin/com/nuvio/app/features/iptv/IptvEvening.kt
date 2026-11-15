package com.nuvio.app.features.iptv

import java.time.Instant
import java.time.ZoneId

/**
 * fork:live-tv-evening - Tonight's running order and Just ask: two ways to hand the whole decision over.
 * Rules only (no AI key needed); pure Kotlin, tested by IptvEveningTest. IptvOnNowScreen feeds them the guide.
 */
object IptvRunningOrder {
    const val SECTION = "__order__"
    const val MAX_SLOTS = 6

    /** Shorter than this is not worth a slot. */
    private const val MIN_LENGTH_MIN = 20

    /** The first slot is a programme already on only when this much of it is left. */
    private const val MIN_LEFT_MIN = 25
    private const val HOP_MS = 15 * 60_000L
    private const val MINUTE = 60_000L

    /** When bedtime is, as a time: today's, or tomorrow's when it is more than six hours behind the clock (as Tonight reads it). */
    fun bedtimeMs(now: Long, bedMinuteOfDay: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        var gap = bedMinuteOfDay - (local.hour * 60 + local.minute)
        if (gap < -6 * 60) gap += 24 * 60
        return now + gap * MINUTE
    }

    private fun score(p: IptvWallProgramme, follows: List<String>, usualKeys: Set<String>, taste: Map<String, IptvTitleStat> = emptyMap()): Int {
        // fork:live-tv-taste - finished before, new rather than a repeat, with a picture and words to go on.
        var s = if (taste.isEmpty()) 0 else IptvTasteRules.score(p.entry, IptvTasteRules.statOf(taste, p.entry.title), followed = false)
        if (IptvDecide.isFollowed(follows, p.entry.title)) s += 100
        if (p.entry.kind == IptvProgrammeKind.FILM) s += 30
        if (p.channel.primary.key in usualKeys) s += 20
        if (p.entry.kind == IptvProgrammeKind.SPORT) s += 15
        s += ((p.entry.endMs - p.entry.startMs) / MINUTE / 10).toInt().coerceIn(0, 6)
        return s
    }

    /**
     * One evening, in order: from now to [bedMs], each slot the best programme starting within a quarter of an hour of
     * the last one's end. Whatever would run past bedtime is left out (it is for tomorrow, not trimmed).
     *
     * It never runs dry while there is anything left in the guide: when the viewer's own channels have nothing for a
     * slot it looks at [wider] (every channel the triage kept); when swaps have used everything up, the title swapped
     * out longest ago comes back; and when nothing starts within the quarter hour it jumps to the next thing that does.
     */
    fun build(
        guide: List<IptvWallProgramme>,
        follows: List<String>,
        usualKeys: Set<String>,
        now: Long,
        bedMs: Long,
        /** fork:live-tv-stage - titles swapped out with "Swap one", oldest first. */
        exclude: Set<String> = emptySet(),
        taste: Map<String, IptvTitleStat> = emptyMap(),
        /** Every channel the triage kept, read only when the viewer's own channels run out. */
        wider: () -> List<IptvWallProgramme> = { emptyList() },
    ): List<IptvWallProgramme> {
        fun fits(p: IptvWallProgramme): Boolean {
            val length = (p.entry.endMs - p.entry.startMs) / MINUTE
            return length >= MIN_LENGTH_MIN && p.entry.endMs <= bedMs && p.entry.endMs > now
        }
        val own = guide.filter(::fits)
        val wide by lazy { wider().filter(::fits) }
        val swappedAt = exclude.withIndex().associate { (i, title) -> title to i }
        val byScore = compareByDescending<IptvWallProgramme> { score(it, follows, usualKeys, taste) }.thenBy { it.entry.startMs }

        val slots = ArrayList<IptvWallProgramme>()
        var cursor = now
        val seen = HashSet<String>()
        while (slots.size < MAX_SLOTS) {
            val first = slots.isEmpty()
            fun fresh(p: IptvWallProgramme) = IptvDecide.normalise(p.entry.title) !in seen
            fun allowed(p: IptvWallProgramme) = IptvDecide.normalise(p.entry.title) !in swappedAt
            fun inWindow(p: IptvWallProgramme) = if (first) {
                val left = (p.entry.endMs - maxOf(now, p.entry.startMs)) / MINUTE
                left >= MIN_LEFT_MIN && p.entry.startMs <= now + HOP_MS
            } else {
                p.entry.startMs >= cursor && p.entry.startMs <= cursor + HOP_MS
            }
            fun List<IptvWallProgramme>.best() = filter { fresh(it) && allowed(it) && inWindow(it) }.sortedWith(byScore).firstOrNull()
            // Swapped out longest ago comes back first once nothing new is left for the slot.
            fun List<IptvWallProgramme>.recycled() = filter { fresh(it) && !allowed(it) && inWindow(it) }
                .sortedWith(compareBy<IptvWallProgramme> { swappedAt[IptvDecide.normalise(it.entry.title)] ?: 0 }.then(byScore))
                .firstOrNull()
            // Nothing in the quarter hour: the next thing to start, best of those within a quarter hour of it.
            fun List<IptvWallProgramme>.later(): IptvWallProgramme? {
                val after = filter { fresh(it) && allowed(it) && it.entry.startMs >= cursor }
                val earliest = after.minOfOrNull { it.entry.startMs } ?: return null
                return after.filter { it.entry.startMs <= earliest + HOP_MS }.sortedWith(byScore).firstOrNull()
            }
            val pick = own.best() ?: wide.best() ?: own.recycled() ?: wide.recycled() ?: own.later() ?: wide.later() ?: break
            slots.add(pick)
            seen.add(IptvDecide.normalise(pick.entry.title))
            cursor = pick.entry.endMs
        }
        return slots
    }

    /** fork:live-tv-stage - the best thing the order left out because it runs past bedtime: offered for tomorrow. */
    fun pastBedtime(guide: List<IptvWallProgramme>, follows: List<String>, usualKeys: Set<String>, now: Long, bedMs: Long): IptvWallProgramme? =
        guide.filter { it.entry.startMs in now until bedMs && it.entry.endMs > bedMs && (it.entry.endMs - it.entry.startMs) / MINUTE >= MIN_LENGTH_MIN }
            .maxWithOrNull(compareBy<IptvWallProgramme> { score(it, follows, usualKeys) }.thenByDescending { it.entry.startMs })
            ?.takeIf { score(it, follows, usualKeys) >= 30 }

    /** Why a slot is in the order, in a few words. */
    fun reason(p: IptvWallProgramme, follows: List<String>, usualKeys: Set<String>): String = when {
        IptvDecide.isFollowed(follows, p.entry.title) -> "followed"
        p.entry.kind == IptvProgrammeKind.FILM -> "a film"
        p.entry.kind == IptvProgrammeKind.SPORT -> "sport"
        p.channel.primary.key in usualKeys -> "your usual at this hour"
        else -> "on your channels"
    }

    fun reminderId(playlistId: String, p: IptvWallProgramme) = "$playlistId:${p.channel.primary.key}:${p.entry.startMs}"

    /** Every slot has an auto-switch reminder: the order is approved. */
    fun approved(playlistId: String, slots: List<IptvWallProgramme>, reminders: List<IptvReminder>): Boolean =
        slots.isNotEmpty() && slots.all { s -> reminders.any { it.id == reminderId(playlistId, s) && it.autoSwitch } }
}

/** What a typed line asks for: how long, what kind, and the words a title or channel must contain. */
data class IptvAskQuery(
    val minutes: Int?,
    val kinds: Set<String>,
    val words: List<String>,
    val comedy: Boolean,
    /** Words that rank an answer higher without being required ("football" in "the football"). */
    val prefer: List<String> = emptyList(),
)

class IptvAskAnswer(
    val programme: IptvWallProgramme?,
    val why: String,
    val others: Int,
    /** fork:live-tv-stage - the reasons as heading and detail lines, for the Why this panel. */
    val reasons: List<Pair<String, String>> = emptyList(),
)

object IptvAsk {
    const val SECTION = "__ask__"

    private val NUMBER_WORDS = mapOf("five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "fortyfive" to 45, "sixty" to 60, "ninety" to 90)
    private val STOP = setOf(
        "something", "anything", "to", "watch", "a", "an", "the", "for", "of", "on", "with", "me", "some", "about", "i", "want", "need",
        "have", "has", "got", "half", "hour", "hours", "minute", "minutes", "min", "mins", "and", "or", "that", "is", "short", "quick", "long",
        "tonight", "now", "good", "nice", "any", "please", "just", "show", "program", "programme",
    )
    private val COMEDY = listOf("comedy", "sitcom", "funny", "laugh", "stand-up", "standup", "comedian", "mock the week", "friends", "frasier")

    /** Reads "something funny, half an hour" into a [IptvAskQuery]. */
    fun parse(text: String): IptvAskQuery {
        val lower = text.lowercase().replace(Regex("[^a-z0-9\\s-]"), " ")
        var minutes: Int? = null
        Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b").find(lower)?.let {
            val n = it.groupValues[1].toInt()
            minutes = if (it.groupValues[2].startsWith("h")) n * 60 else n
        }
        if (minutes == null) {
            when {
                Regex("\\bhalf an? hour\\b").containsMatchIn(lower) -> minutes = 30
                Regex("\\ban hour and a half\\b").containsMatchIn(lower) -> minutes = 90
                Regex("\\ban hour\\b|\\bone hour\\b").containsMatchIn(lower) -> minutes = 60
                Regex("\\bquarter of an hour\\b").containsMatchIn(lower) -> minutes = 15
                else -> NUMBER_WORDS.entries.firstOrNull { Regex("\\b${it.key}\\s*(minutes?|mins?)\\b").containsMatchIn(lower) }?.let { minutes = it.value }
            }
        }
        // A sport's name asks for sport; the name itself only ranks (the guide rarely says "football" in the title).
        val sportNames = SPORT_NAMES.filter { Regex("\\b$it\\b").containsMatchIn(lower) }
        val kinds = buildSet {
            if (sportNames.isNotEmpty()) add(IptvProgrammeKind.SPORT)
            if (Regex("\\b(film|films|movie|movies)\\b").containsMatchIn(lower)) add(IptvProgrammeKind.FILM)
            if (Regex("\\b(sport|sports|match|game)\\b").containsMatchIn(lower)) add(IptvProgrammeKind.SPORT)
            if (Regex("\\bnews\\b").containsMatchIn(lower)) add(IptvProgrammeKind.NEWS)
            if (Regex("\\b(kids|children|cartoons?)\\b").containsMatchIn(lower)) add(IptvProgrammeKind.KIDS)
        }
        val comedy = Regex("\\b(funny|comedy|laugh|laughs|sitcom)\\b").containsMatchIn(lower)
        val kindWords = setOf("film", "films", "movie", "movies", "sport", "sports", "match", "game", "news", "kids", "children", "cartoon", "cartoons", "funny", "comedy", "laugh", "laughs", "sitcom")
        val words = lower.split(Regex("\\s+")).filter {
            it.length >= 3 && it !in STOP && it !in kindWords && it !in sportNames && it.toIntOrNull() == null && it !in NUMBER_WORDS && !Regex("\\d+(h|m|hr|hrs|min|mins)").matches(it)
        }
        return IptvAskQuery(minutes, kinds, words, comedy, prefer = sportNames)
    }

    private val SPORT_NAMES = listOf(
        "football", "soccer", "rugby", "cricket", "tennis", "golf", "boxing", "darts", "snooker", "f1", "formula",
        "basketball", "nfl", "nba", "hockey", "cycling", "racing", "wrestling", "ufc", "mma",
    )

    /**
     * Whether [p] is of [kind]: the guide's category, else its title or channel name ("BBC News", "Sky Sports",
     * "Film4"), since many guides carry no categories.
     */
    private fun isKind(p: IptvWallProgramme, kind: String): Boolean =
        p.entry.kind == kind || IptvProgrammeKind.of(p.entry.title) == kind || IptvProgrammeKind.of(p.channel.primary.name) == kind

    /** News and sport on now are live: a viewer can dip in for ten minutes, so their length is no bar. */
    private fun live(p: IptvWallProgramme, now: Long) =
        p.onNow(now) && (isKind(p, IptvProgrammeKind.NEWS) || isKind(p, IptvProgrammeKind.SPORT))

    private fun matches(p: IptvWallProgramme, q: IptvAskQuery, now: Long, useMinutes: Boolean = true, useWords: Boolean = true): Boolean {
        val title = p.entry.title.lowercase()
        val channel = p.channel.primary.name.lowercase()
        if (q.kinds.isNotEmpty() && q.kinds.none { isKind(p, it) }) return false
        if (q.comedy && COMEDY.none { title.contains(it) } && p.entry.kind != "comedy") return false
        if (useWords && q.words.any { !title.contains(it) && !channel.contains(it) }) return false
        if (useMinutes && !live(p, now)) q.minutes?.let { limit ->
            val length = (p.entry.endMs - maxOf(now, p.entry.startMs)) / 60_000L
            if (length > limit + IptvDecide.MAX_OVER_MIN || length < 5) return false
        }
        return true
    }

    /**
     * One answer. [skips] steps to the next best ("Not that"). Candidates: on now, or starting within the next half hour;
     * best are followed things, then films, then your usual channels, then the one that fits the time most closely.
     */
    fun answer(
        text: String,
        guide: List<IptvWallProgramme>,
        follows: List<String>,
        usualKeys: Set<String>,
        now: Long,
        skips: Int,
        /** fork:live-tv-taste - what was watched and finished. */
        taste: Map<String, IptvTitleStat> = emptyMap(),
    ): IptvAskAnswer {
        fun stat(p: IptvWallProgramme) = IptvTasteRules.statOf(taste, p.entry.title)
        val q = parse(text)
        val window = guide.filter { it.onNow(now) || (it.entry.startMs > now && it.entry.startMs <= now + IptvOnNow.SOON_MS) }
        // Strict first; then without the time, then without the words, so a close answer beats "nothing fits".
        var relaxed: String? = null
        var pool = window.filter { matches(it, q, now) }
        if (pool.isEmpty() && q.minutes != null) {
            pool = window.filter { matches(it, q, now, useMinutes = false) }
            if (pool.isNotEmpty()) relaxed = "Nothing fits ${q.minutes} minutes, so this is the closest"
        }
        if (pool.isEmpty() && q.words.isNotEmpty()) {
            pool = window.filter { matches(it, q, now, useMinutes = false, useWords = false) }
            if (pool.isNotEmpty()) relaxed = "Nothing matches \"${q.words.joinToString(" ")}\", so this is the nearest"
        }
        if (pool.isEmpty()) {
            // What was understood, so the viewer can see why nothing came back.
            val understood = buildList {
                if (q.kinds.isNotEmpty()) add(q.kinds.joinToString(" or "))
                if (q.comedy) add("funny")
                q.minutes?.let { add("$it minutes") }
                if (q.words.isNotEmpty()) add("\"${q.words.joinToString(" ")}\" in the title or channel")
            }
            val reasons = if (understood.isEmpty()) emptyList() else listOf("Looked for" to understood.joinToString(", "))
            return IptvAskAnswer(null, "Nothing on now or starting soon fits that.", 0, reasons)
        }
        val ranked = pool.sortedWith(
            compareByDescending<IptvWallProgramme> { IptvDecide.isFollowed(follows, it.entry.title) }
                .thenByDescending { p -> q.prefer.any { p.entry.title.lowercase().contains(it) || p.channel.primary.name.lowercase().contains(it) } }
                .thenByDescending { live(it, now) }
                .thenByDescending { IptvTasteRules.score(it.entry, stat(it), followed = false) / 10 }
                .thenByDescending { it.entry.kind == IptvProgrammeKind.FILM }
                .thenByDescending { it.channel.primary.key in usualKeys }
                .thenBy { kotlin.math.abs(((it.entry.endMs - maxOf(now, it.entry.startMs)) / 60_000L) - (q.minutes ?: 0)) }
                .thenBy { it.entry.startMs },
        )
        val pick = ranked[skips.mod(ranked.size)]
        val left = (pick.entry.endMs - maxOf(now, pick.entry.startMs)) / 60_000L
        val timing = if (pick.onNow(now)) "On now, $left min left" else "Starts in ${(pick.entry.startMs - now) / 60_000L} min, $left min long"
        val because = buildList {
            if (IptvDecide.isFollowed(follows, pick.entry.title)) add("you follow it")
            if (IptvTasteRules.usuallyFinishes(stat(pick))) add("you usually finish it")
            if (pick.entry.isNew) add("a new one")
            if (pick.entry.kind == IptvProgrammeKind.FILM) add("a film")
            if (pick.channel.primary.key in usualKeys) add("a channel you usually watch")
            q.minutes?.let { add("fits $it minutes") }
        }
        val said = if (because.isEmpty()) timing else "$timing; ${because.joinToString(", ")}"
        val why = relaxed?.let { "$it. $said" } ?: said
        val reasons = buildList {
            val kind = when {
                q.comedy -> "Comedy"
                pick.entry.kind == IptvProgrammeKind.FILM -> "A film"
                pick.entry.kind == IptvProgrammeKind.SPORT -> "Sport"
                pick.entry.kind == IptvProgrammeKind.NEWS -> "News"
                pick.entry.kind == IptvProgrammeKind.KIDS -> "For children"
                else -> null
            }
            if (kind != null) add(kind to "From the guide")
            add("$left minutes ${if (pick.onNow(now)) "left" else "long"}" to (q.minutes?.let { "Inside your $it minutes" } ?: timing))
            if (IptvDecide.isFollowed(follows, pick.entry.title)) add("You follow it" to pick.entry.title)
            IptvTasteRules.finishText(stat(pick))?.takeIf { IptvTasteRules.usuallyFinishes(stat(pick)) }?.let { add("You usually finish it" to it) }
            if (pick.entry.isNew) add("A new one" to "Not a repeat")
            else if (IptvTasteRules.isRepeat(pick.entry, stat(pick))) add("Seen this one" to (pick.entry.episode ?: "A repeat"))
            if (pick.channel.primary.key in usualKeys) add("A channel you watch" to pick.channel.primary.name)
            if (q.words.isNotEmpty() && relaxed == null) add("Matches \"${q.words.joinToString(" ")}\"" to "In the title or channel")
            if (live(pick, now)) add("Live" to "Dip in for as long as you like")
            relaxed?.let { add("The closest there is" to it) }
        }
        return IptvAskAnswer(pick, why, ranked.size - 1, reasons)
    }
}

/** fork:live-tv-evening - the wall rows of the two sections. */
object IptvEveningRows {
    private fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

    fun order(slots: List<IptvWallProgramme>, approved: Boolean, bedMs: Long): List<IptvWallRow> {
        if (slots.isEmpty()) {
            return listOf(
                IptvWallRow(
                    "Tonight's running order", "to bedtime ${clock(bedMs)}", 3,
                    listOf(IptvWallItem.Note("order:none", "Nothing to line up", "No programme of your lineup (or usual channels) fits before bedtime.")),
                ),
            )
        }
        val rows = slots.mapIndexed { i, p -> IptvWallItem.Programme(p, film = false, tag = "${i + 1}  ${clock(p.entry.startMs)}") }
            .chunked(3).mapIndexed { index, chunk ->
                IptvWallRow(if (index == 0) "Tonight's running order" else null, if (index == 0) "ends before bedtime ${clock(bedMs)}" else null, 3, chunk)
            }
        val button = IptvWallItem.Action(
            if (approved) "order:cancel" else "order:approve",
            if (approved) "Approved" else "Approve",
            if (approved) "select to cancel" else "Nuvio changes channel at each start; any key takes over",
            selected = approved, enabled = true,
        )
        return rows + IptvWallRow(null, null, 3, listOf(button))
    }

    fun ask(text: String, answer: IptvAskAnswer?): List<IptvWallRow> {
        if (answer == null) {
            return listOf(
                IptvWallRow(
                    "Just ask", "one answer", 3,
                    listOf(IptvWallItem.Note("ask:help", "Type in the search box", "For example: something funny, half an hour. Or: a film, an hour. You get one answer.")),
                ),
            )
        }
        val p = answer.programme
        return if (p == null) {
            listOf(IptvWallRow("Just ask", "\"${text.trim()}\"", 3, listOf(IptvWallItem.Note("ask:none", "Nothing fits", answer.why))))
        } else {
            listOf(
                IptvWallRow("Just ask", "\"${text.trim()}\"", 3, listOf(IptvWallItem.Programme(p, film = false, tag = "THE ANSWER"))),
                IptvWallRow(
                    null, null, 3,
                    listOf(
                        IptvWallItem.Note("ask:why", "Why this", answer.why),
                        IptvWallItem.Action("ask:not", "Not that", if (answer.others > 0) "${answer.others} more" else "that was the only one", selected = false, enabled = answer.others > 0),
                    ),
                ),
            )
        }
    }
}

/**
 * fork:live-tv-stage - the one metadata line every programme tile carries, so tiles read alike whatever the screen:
 * "Channel 4 · on now, 12 min in · ends 20:00", "ITV4 · starts in 2 min · ends 22:25", "BBC Three · from catch-up · 07:00 to 07:10".
 */
object IptvProgrammeLine {
    fun text(channel: String, entry: IptvEpgEntry, now: Long, over: Int, clock: (Long) -> String): String {
        val when_ = when {
            entry.endMs <= now -> "from catch-up · ${clock(entry.startMs)} to ${clock(entry.endMs)}"
            entry.startMs <= now -> "on now, ${((now - entry.startMs) / 60_000L).coerceAtLeast(0)} min in · ends ${clock(entry.endMs)}"
            entry.startMs - now <= 60 * 60_000L -> "starts ${IptvDeparture.countdown(entry.startMs - now)} · ends ${clock(entry.endMs)}"
            else -> "starts ${clock(entry.startMs)} · ends ${clock(entry.endMs)}"
        }
        return "$channel · $when_" + if (over > 0) " · $over min over" else ""
    }

    /** fork:live-tv-sport-art - " · also on Sky Sports Main Event", or " · also on A and 2 more". */
    fun alsoOn(channels: List<String>): String = when (channels.size) {
        0 -> ""
        1 -> " · also on ${channels[0]}"
        else -> " · also on ${channels[0]} and ${channels.size - 1} more"
    }
}
