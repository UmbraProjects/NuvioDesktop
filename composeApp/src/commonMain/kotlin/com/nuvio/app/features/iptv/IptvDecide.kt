package com.nuvio.app.features.iptv

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/*
 * fork:live-tv-decide - "Decide for me": Live TV views that offer very little and end. Three things (one safe, one
 * starting soon, one wildcard, one reshuffle a night), I have N minutes (only what fits), Earlier today (what the
 * provider kept, best first) and Following (programmes and teams followed once, found on whichever channel has them).
 * Pure Kotlin, tested by IptvDecideTest; IptvOnNowScreen feeds it the guide.
 */

/** The night's reshuffle: [day] is the night's number ([IptvDecide.nightOf]), [count] how many were used (one is allowed). */
@Serializable
data class IptvShuffle(val day: Long = -1L, val count: Int = 0)

/** A programme that fits the minutes dialled, and how far it runs over (0 = fits). */
data class IptvFit(val programme: IptvWallProgramme, val overMin: Int)

class IptvThree(val safe: IptvWallProgramme?, val soon: IptvWallProgramme?, val wildcard: IptvWallProgramme?)

class IptvMinutesBoard(val alreadyOn: List<IptvFit>, val starting: List<IptvFit>, val catchUp: List<IptvFit>)

/** One followed thing and where it is on. */
class IptvFollowResult(val text: String, val matches: List<IptvWallProgramme>)

object IptvDecide {
    val MINUTE_CHOICES = listOf(15, 25, 45, 60, 90)
    const val DEFAULT_MINUTES = 25

    /** A programme this much over the minutes is still shown, dimmed with how far over. */
    const val MAX_OVER_MIN = 10

    /** Shorter than this is not worth offering. */
    private const val MIN_LENGTH_MIN = 10
    private const val SOON_WINDOW_MIN = 30
    private const val PER_COLUMN = 3

    /** The Live TV sidebar sections these views use. */
    const val THREE = "__three__"
    const val MINUTES = "__minutes__"
    const val EARLIER = "__earlier__"
    const val FOLLOWING = "__following__"
    val SECTIONS = setOf(THREE, MINUTES, EARLIER, FOLLOWING, IptvRunningOrder.SECTION, IptvAsk.SECTION)

    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    fun normalise(title: String): String = title.lowercase().replace(NON_WORD, " ").trim()

    /** The night [now] belongs to: the day counted from 05:00, so 01:00 is still last night. */
    fun nightOf(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).minusHours(5).toLocalDate().toEpochDay()

    /** Which of the candidates to show: the first, or after the night's reshuffle the next one. */
    fun rotationFor(shuffle: IptvShuffle, night: Long): Int = if (shuffle.day == night) shuffle.count else 0

    fun canReshuffle(shuffle: IptvShuffle, night: Long): Boolean = !(shuffle.day == night && shuffle.count >= 1)

    private fun <T> List<T>.rotated(by: Int): T? = if (isEmpty()) null else this[by.mod(size)]

    /**
     * SAFE: what is on now among the usual channels. STARTS SOON: the next thing to begin within half an hour (it can
     * be over before [endsBy]). WILDCARD: a film on or about to start that is not on the usual channels and, with a
     * [lineup], not on it. No two cards share a channel. [rotation] steps each card to its next candidate.
     */
    fun three(
        channels: List<IptvChannelNow>,
        usuals: List<IptvMergedChannel>,
        lineup: ((IptvMergedChannel) -> Boolean)?,
        now: Long,
        rotation: Int,
        endsBy: Long? = null,
        /** fork:live-tv-taste - higher first within each card's candidates; stable, so equal ranks keep their order. */
        rank: ((IptvWallProgramme) -> Int)? = null,
    ): IptvThree {
        fun List<IptvWallProgramme>.ranked() = if (rank == null) this else sortedByDescending(rank)
        fun okEnd(programme: IptvWallProgramme) = endsBy == null || programme.entry.endMs <= endsBy
        val byUid = channels.associateBy { it.channel.primary.uid }
        val usualUids = usuals.mapTo(HashSet()) { it.primary.uid }

        val safeList = usuals.mapNotNull { usual -> byUid[usual.primary.uid]?.current(now)?.let { IptvWallProgramme(usual, it) } }.filter(::okEnd).ranked()
        val safe = safeList.rotated(rotation)
        val taken = HashSet<Int>().apply { safe?.let { add(it.channel.primary.uid) } }

        val soonList = IptvDeparture.buckets(channels, now, only = lineup, perBucket = 99)
            .flatMap { it.programmes }
            .filter { it.entry.startMs - now <= SOON_WINDOW_MIN * 60_000L && it.channel.primary.uid !in taken && okEnd(it) }
            .ranked()
        val soon = soonList.rotated(rotation)
        soon?.let { taken.add(it.channel.primary.uid) }

        val films = IptvOnNow.films(channels, now).filter { it.channel.primary.uid !in taken && okEnd(it) }
        val outside = films.filter { it.channel.primary.uid !in usualUids && (lineup == null || !lineup(it.channel)) }
        val wildcard = (outside.ifEmpty { films }).ranked().rotated(rotation)
        return IptvThree(safe, soon, wildcard)
    }

    /**
     * What fits [budgetMin] minutes starting now: [channels] gives what is on and what follows; [past] the finished
     * programmes the provider still has. Nothing runs over, except a programme up to [MAX_OVER_MIN] over, marked.
     */
    fun minutes(channels: List<IptvChannelNow>, past: List<IptvWallProgramme>, now: Long, budgetMin: Int, perColumn: Int = PER_COLUMN): IptvMinutesBoard {
        val budget = budgetMin * 60_000L
        fun fit(programme: IptvWallProgramme, spanMs: Long): IptvFit? {
            val over = ((spanMs - budget + 59_999L) / 60_000L).toInt()
            return if (over <= MAX_OVER_MIN) IptvFit(programme, over.coerceAtLeast(0)) else null
        }
        val on = channels.mapNotNull { c ->
            c.current(now)?.takeIf { it.endMs - now >= 5 * 60_000L }?.let { IptvWallProgramme(c.channel, it) }?.let { fit(it, it.entry.endMs - now) }
        }.sortedWith(compareBy({ it.overMin > 0 }, { it.programme.entry.endMs })).distinctBy(::sameShowing).take(perColumn)
        val starting = channels.mapNotNull { c ->
            c.next(now)?.takeIf { it.startMs - now <= 60 * 60_000L && it.endMs - it.startMs >= MIN_LENGTH_MIN * 60_000L }
                ?.let { IptvWallProgramme(c.channel, it) }?.let { fit(it, it.entry.endMs - now) }
        }.sortedWith(compareBy({ it.overMin > 0 }, { it.programme.entry.startMs })).distinctBy(::sameShowing).take(perColumn)
        val earlier = past.filter { it.entry.endMs <= now && it.entry.endMs - it.entry.startMs >= MIN_LENGTH_MIN * 60_000L }
            .mapNotNull { fit(it, it.entry.endMs - it.entry.startMs) }
            .sortedWith(compareBy({ it.overMin > 0 }, { -it.programme.entry.endMs })).distinctBy { normalise(it.programme.entry.title) }.take(perColumn)
        return IptvMinutesBoard(on, starting, earlier)
    }

    /** fork:live-tv-polish - one showing on two channels (Euronews twice, TVO and TV Ontario) is one row. */
    private fun sameShowing(fit: IptvFit): String = normalise(fit.programme.entry.title) + "@" + fit.programme.entry.startMs / 60_000L

    fun matchesFollow(follow: String, title: String): Boolean {
        val wanted = normalise(follow)
        val seen = normalise(title)
        if (wanted.isEmpty() || seen.isEmpty()) return false
        if (seen == wanted || (wanted.length >= 4 && seen.contains(wanted))) return true
        // fork:live-tv-learn - a name of two words or more by any spelling: "Manchester United" finds "Man Utd v
        // Chelsea" and "Man United" finds "Manchester United v Spurs", word by word, each a short form of the other.
        val followWords = teamWords(wanted)
        if (followWords.size < 2) return false
        val titleWords = teamWords(seen)
        return (0..titleWords.size - followWords.size).any { at ->
            followWords.indices.all { j -> sameWord(followWords[j], titleWords[at + j]) }
        }
    }

    private fun teamWords(text: String) = text.split(' ').filter { it.isNotBlank() }.map { if (it == "utd") "united" else it }

    private fun sameWord(a: String, b: String) = a == b || (b.length >= 3 && a.startsWith(b)) || (a.length >= 3 && b.startsWith(a))

    /** fork:live-tv-polish - a follow typed in lower case ("man utd") shown with capitals ("Man Utd"); typed capitals kept. */
    fun displayFollow(text: String): String =
        if (text != text.lowercase()) text else text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

    fun isFollowed(follows: List<String>, title: String): Boolean = follows.any { matchesFollow(it, title) }

    /** The follows that [title] counts as (for unfollowing). */
    fun followsOf(follows: List<String>, title: String): List<String> = follows.filter { matchesFollow(it, title) }

    /**
     * Where each followed thing is on, in [ahead] (programmes that have not ended): a lineup channel first, then the
     * soonest start, one slot per channel, at most [limit] each. A follow with no match comes back with none.
     */
    fun follows(follows: List<String>, ahead: List<IptvWallProgramme>, lineup: ((IptvMergedChannel) -> Boolean)?, limit: Int = 2): List<IptvFollowResult> =
        follows.map { text ->
            val found = ahead.filter { matchesFollow(text, it.entry.title) }
                .sortedWith(compareBy({ lineup != null && !lineup(it.channel) }, { it.entry.startMs }, { IptvChannelMerge.rank(it.channel.primary.name) * -1 }))
                .distinctBy { it.channel.primary.uid }
                .take(limit)
                .sortedBy { it.entry.startMs }
            IptvFollowResult(text, found)
        }

    /** Earlier today, best first: followed, then films, then the latest; dull short ones dropped; at most [limit]. */
    fun earlier(
        past: List<IptvWallProgramme>,
        follows: List<String>,
        now: Long,
        limit: Int = 9,
        /** fork:live-tv-taste - after follows and films, the better-liked first. */
        rank: ((IptvWallProgramme) -> Int)? = null,
    ): List<IptvWallProgramme> =
        past.filter { it.entry.endMs <= now && it.entry.endMs - it.entry.startMs >= MIN_LENGTH_MIN * 60_000L }
            .distinctBy { it.channel.primary.uid to it.entry.startMs }
            .sortedWith(
                compareByDescending<IptvWallProgramme> { isFollowed(follows, it.entry.title) }
                    .thenByDescending { it.entry.kind == IptvProgrammeKind.FILM }
                    .thenByDescending { rank?.invoke(it) ?: 0 }
                    .thenByDescending { it.entry.endMs },
            )
            // fork:live-tv-polish - one show must not fill the list (a channel showing Friends all day): at most
            // [EARLIER_PER_TITLE] of each title, the best of them.
            .let { sorted ->
                val seen = HashMap<String, Int>()
                sorted.filter { p -> seen.merge(normalise(p.entry.title), 1, Int::plus)!! <= EARLIER_PER_TITLE }
            }
            .take(limit)

    /** fork:live-tv-polish - how many of one title Earlier today keeps. */
    const val EARLIER_PER_TITLE = 2

    private fun programme(p: IptvWallProgramme, tag: String? = null, over: Int = 0) = IptvWallItem.Programme(p, film = false, tag = tag, over = over)

    /** The rows of the four sections, ready for the wall; every row's tiles are the same size. */
    fun sections(
        three: IptvThree,
        board: IptvMinutesBoard,
        minutes: Int,
        canReshuffle: Boolean,
        earlier: List<IptvWallProgramme>,
        followResults: List<IptvFollowResult>,
    ): Map<String, List<IptvWallRow>> {
        fun slot(p: IptvWallProgramme?, tag: String, empty: String): IptvWallItem =
            if (p != null) programme(p, tag) else IptvWallItem.Note("three:$tag", tag, empty)
        val threeRows = listOf(
            IptvWallRow(
                "Three things", "pick one; the screen has no more", 3,
                listOf(
                    slot(three.safe, "SAFE", "None of your usual channels has a guide"),
                    slot(three.soon, "STARTS SOON", "Nothing starts in the next half hour"),
                    slot(three.wildcard, "WILDCARD", "No film to suggest right now"),
                ),
            ),
            IptvWallRow(
                null, null, 3,
                listOf(
                    IptvWallItem.Action(
                        "reshuffle", "Reshuffle", if (canReshuffle) "one per night, 1 left" else "used tonight", selected = false, enabled = canReshuffle,
                    ),
                ),
            ),
        )
        val dial = IptvWallRow(
            "I have $minutes minutes", "nothing here runs over", 5,
            MINUTE_CHOICES.map { IptvWallItem.Action("minutes:$it", "$it min", null, selected = it == minutes, enabled = true) },
        )
        fun column(title: String, empty: String, fits: List<IptvFit>) = IptvWallRow(
            title, null, 3,
            if (fits.isEmpty()) listOf(IptvWallItem.Note("minutes:$title", title, empty)) else fits.map { programme(it.programme, over = it.overMin) },
        )
        val minuteRows = listOf(
            dial,
            column("Already on, ends in time", "Nothing on now ends within $minutes minutes", board.alreadyOn),
            column("Starts soon, ends in time", "Nothing starting soon fits", board.starting),
            column("From catch-up", "No catch-up programme is short enough, or the provider keeps none", board.catchUp),
        )
        val earlierRows = earlier.map { programme(it) }.chunked(3).mapIndexed { index, chunk ->
            IptvWallRow(if (index == 0) "Earlier today" else null, if (index == 0) "ready to play from the start" else null, 3, chunk)
        }
        val followRows = followResults.flatMap { result ->
            val items = if (result.matches.isEmpty()) {
                listOf(IptvWallItem.Note("follow:${result.text}", result.text, "Not in the guide yet"))
            } else {
                result.matches.map { programme(it) }
            }
            listOf(IptvWallRow(result.text, if (result.matches.isEmpty()) null else "followed", 3, items))
        }
        return mapOf(THREE to threeRows, MINUTES to minuteRows, EARLIER to earlierRows, FOLLOWING to followRows)
    }
}
