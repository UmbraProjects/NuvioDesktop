package com.nuvio.app.features.iptv

import com.nuvio.app.features.livescores.LiveMatch
import com.nuvio.app.features.livescores.MatchPhase
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.exp

/*
 * fork:live-tv-on-now - the Live TV tab led by what is on rather than by a channel list (Settings > Live TV >
 * On Now, off by default): live sport with its score, films with how far in they are, and the channels usually
 * watched at this hour. This file is the rules, pure Kotlin (tested by IptvOnNowTest); IptvOnNowScreen.kt draws them.
 */

/** What a guide's `<category>` says a programme is. Stored on [IptvEpgEntry.kind]. */
object IptvProgrammeKind {
    const val FILM = "film"
    const val SPORT = "sport"
    const val NEWS = "news"
    const val KIDS = "kids"

    private val film = listOf("movie", "film", "cinema")
    private val sport = listOf(
        "sport", "football", "soccer", "rugby", "cricket", "tennis", "golf", "boxing", "motorsport", "formula",
        "basketball", "darts", "snooker", "cycling", "athletics", "hockey", "racing", "wrestling", "martial",
    )
    private val news = listOf("news", "current affairs")
    private val kids = listOf("children", "kids")

    /** The kind [category] names, or null. "Sports documentary" style categories count as what they are about. */
    fun of(category: String): String? {
        val text = category.lowercase()
        return when {
            sport.any { it in text } -> SPORT
            film.any { it in text } -> FILM
            news.any { it in text } -> NEWS
            kids.any { it in text } -> KIDS
            else -> null
        }
    }
}

/** A merged channel with the guide's programmes for it from about now onwards, in start order. */
class IptvChannelNow(
    val channel: IptvMergedChannel,
    val entries: List<IptvEpgEntry>,
    /** The provider category it sits in ("UK | Movies"); what a programme is can hang on it. */
    val categoryName: String = "",
) {
    fun current(now: Long): IptvEpgEntry? = entries.firstOrNull { it.startMs <= now && it.endMs > now }
    fun next(now: Long): IptvEpgEntry? = entries.firstOrNull { it.startMs > now }
}

/** A programme on a channel: on now, or about to start. */
data class IptvWallProgramme(val channel: IptvMergedChannel, val entry: IptvEpgEntry) {
    fun onNow(now: Long): Boolean = entry.startMs <= now && entry.endMs > now
}

object IptvOnNow {
    /** A film or match starting within this long is listed with what is already on. */
    const val SOON_MS = 30 * 60_000L

    private const val MINUTE = 60_000L
    private val filmPlace = Regex("""\b(movies?|films?|cinema|cine|kino)\b""")
    private val sportPlace = Regex("""\b(sports?|espn|dazn|eurosport|bein|racing|golf|cricket|nba|nfl|nhl|mlb|ufc|wwe|f1|tennis|football|futbol|rugby)\b""")
    private val filmLead = Regex("""^\s*(film|movie)\s*[:\-]\s*""", RegexOption.IGNORE_CASE)
    private val titleLead = Regex("""^\s*(new|live|film|movie|premiere)\s*[:\-]\s*""", RegexOption.IGNORE_CASE)
    private val titleYear = Regex("""\s*[(\[]\s*((?:19|20)\d{2})\s*[)\]]\s*$""")
    private val versus = Regex("""\b(v|vs|versus)\b""")

    private fun place(programme: IptvChannelNow): String = (programme.channel.primary.name + " " + programme.categoryName).lowercase()

    /**
     * Whether [entry] is a film: the guide says so, its title says so ("Film: Heat"), or it is feature length on a
     * film channel or in a film category (a guide with no categories still has "UK | Movies").
     */
    fun isFilm(entry: IptvEpgEntry, on: IptvChannelNow): Boolean {
        val minutes = (entry.endMs - entry.startMs) / MINUTE
        if (entry.kind == IptvProgrammeKind.FILM) return minutes >= 45
        if (entry.kind != null) return false
        if (filmLead.containsMatchIn(entry.title)) return true
        return minutes in 70..240 && filmPlace.containsMatchIn(place(on))
    }

    /** Whether [entry] is sport: the guide says so, or it is on a sports channel or in a sports category. */
    fun isSport(entry: IptvEpgEntry, on: IptvChannelNow): Boolean {
        if (entry.kind == IptvProgrammeKind.SPORT) return true
        if (entry.kind != null) return false
        return sportPlace.containsMatchIn(place(on))
    }

    /** Sport that is an event rather than a studio show: "Live: ...", or one side against another. */
    fun looksLikeEvent(title: String): Boolean {
        val text = title.lowercase()
        return text.startsWith("live") || versus.containsMatchIn(text)
    }

    /** A guide title as something to look up: "New: Heat (1995)" is "Heat", 1995. */
    fun filmQuery(title: String): Pair<String, Int?> {
        var text = title.trim()
        while (true) {
            val stripped = titleLead.replace(text, "")
            if (stripped == text) break
            text = stripped
        }
        val year = titleYear.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return titleYear.replace(text, "").trim() to year
    }

    /**
     * Every copy of each programme, the best first: on now beats about to start, then the copy marked live
     * (fork:live-tv-sport-art: "ᴸⁱᵛᵉ" and "(Live)" do not make another programme), then the better copy of the channel.
     */
    private fun copies(found: List<IptvWallProgramme>, now: Long): List<List<IptvWallProgramme>> =
        found.groupBy { IptvSportTitle.sameKey(filmQuery(it.entry.title).first) }.values.map { same ->
            same.sortedWith(
                compareByDescending<IptvWallProgramme> { it.onNow(now) }
                    .thenByDescending { IptvSportTitle.markedLive(it.entry.title) }
                    .thenByDescending { IptvChannelMerge.rank(it.channel.primary.name) },
            )
        }

    /** One programme per title (see [copies]). */
    private fun dedupe(found: List<IptvWallProgramme>, now: Long): List<IptvWallProgramme> = copies(found, now).map { it.first() }

    /** fork:live-tv-sport-art - for each sport programme [sport] lists, the other channels showing it, by name. */
    fun sportCopies(channels: List<IptvChannelNow>, now: Long): Map<IptvWallProgramme, List<String>> =
        copies(gather(channels, now, ::isSport), now).associate { same ->
            val best = same.first()
            best to same.drop(1).map { it.channel.primary.name }.filter { it.isNotBlank() && it != best.channel.primary.name }.distinct()
        }

    private fun gather(channels: List<IptvChannelNow>, now: Long, wanted: (IptvEpgEntry, IptvChannelNow) -> Boolean): List<IptvWallProgramme> =
        channels.flatMap { on ->
            on.entries.asSequence()
                .filter { it.endMs > now && it.startMs <= now + SOON_MS && wanted(it, on) }
                .map { IptvWallProgramme(on.channel, it) }
                .toList()
        }

    /** Films on now, the most recently started first (the easiest to join), then the ones about to start. */
    fun films(channels: List<IptvChannelNow>, now: Long): List<IptvWallProgramme> =
        dedupe(gather(channels, now, ::isFilm), now).sortedWith(
            compareByDescending<IptvWallProgramme> { it.onNow(now) }
                .thenBy { if (it.onNow(now)) now - it.entry.startMs else it.entry.startMs - now },
        )

    /** Sport on now or about to start: events before studio shows, then by channel order. */
    fun sport(channels: List<IptvChannelNow>, now: Long): List<IptvWallProgramme> =
        dedupe(gather(channels, now, ::isSport), now).sortedWith(
            compareByDescending<IptvWallProgramme> { looksLikeEvent(it.entry.title) }
                .thenByDescending { it.onNow(now) }
                .thenBy { it.channel.primary.num },
        )
}

/** One time a channel was put on, for [IptvUsuals]. [ref] is `playlistId:channelKey`. */
@kotlinx.serialization.Serializable
data class IptvPlay(val ref: String, val at: Long)

/** The channels usually watched around this time: what "Your channels" leads with. */
object IptvUsuals {
    const val MAX_PLAYS = 600
    private const val DAY_MS = 24 * 3_600_000L

    /**
     * Channel refs, the likeliest first. A play counts fully within an hour of this time of day, half within two,
     * a little otherwise; half as much again on the same weekday; and less the older it is (a month halves it,
     * roughly), so last winter's habits fade.
     */
    fun rank(plays: List<IptvPlay>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        if (plays.isEmpty()) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone)
        val minuteNow = today.hour * 60 + today.minute
        val scores = HashMap<String, Double>()
        for (play in plays) {
            val then = Instant.ofEpochMilli(play.at).atZone(zone)
            val apart = abs(then.hour * 60 + then.minute - minuteNow).let { minOf(it, 24 * 60 - it) }
            val hour = when {
                apart <= 60 -> 1.0
                apart <= 120 -> 0.5
                else -> 0.15
            }
            val weekday = if (then.dayOfWeek == today.dayOfWeek) 1.5 else 1.0
            val age = exp(-((now - play.at).coerceAtLeast(0L).toDouble() / DAY_MS) / 43.0)
            scores[play.ref] = (scores[play.ref] ?: 0.0) + hour * weekday * age
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }
}

/** Finding the channel a match is on, from the guide's titles ("Live: Man Utd v Real Sociedad"). */
object IptvSportMatch {
    private val ACCENTS = Regex("""\p{Mn}+""")
    private val SPLIT = Regex("""[^a-z0-9]+""")

    /** Words in a club's name that a guide title leaves out. */
    private val filler = setOf("fc", "afc", "cf", "sc", "ac", "as", "fk", "bk", "sk", "cd", "ud", "rc", "club", "the", "de", "and")

    /** Short forms a guide uses, to the word the feed uses. */
    private val alias = mapOf(
        "utd" to "united", "man" to "manchester", "spurs" to "tottenham", "hotspur" to "tottenham",
        "wolves" to "wolverhampton", "st" to "saint", "nottm" to "nottingham", "sheff" to "sheffield",
        "psg" to "paris", "inter" to "internazionale", "boro" to "middlesbrough",
    )

    fun words(text: String): List<String> {
        val plain = ACCENTS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
        return plain.split(SPLIT).filter { it.isNotEmpty() }.map { alias[it] ?: it }
    }

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        // "man" for "manchester", "wed" for "wednesday": a guide's cut-down word.
        return short.length >= 3 && long.startsWith(short)
    }

    private fun namedBy(title: List<String>, name: String): Boolean {
        val wanted = words(name).filter { it !in filler }.distinct()
        return wanted.isNotEmpty() && wanted.all { word -> title.any { same(it, word) } }
    }

    /** Whether [title] names the team, by its full name or its short one. */
    fun mentions(title: String, name: String, shortName: String = name): Boolean {
        val titleWords = words(title)
        return namedBy(titleWords, name) || (shortName != name && namedBy(titleWords, shortName))
    }

    /**
     * The programme showing [match], if the guide has one: both sides in the title, or one side and "live" (guides
     * shorten "Live Football: Man Utd"). Only programmes that overlap the match are looked at; on now beats later.
     */
    fun programmeFor(match: LiveMatch, programmes: List<IptvWallProgramme>, now: Long): IptvWallProgramme? {
        val start = match.startEpochMs ?: now
        val live = match.phase == MatchPhase.Live || match.phase == MatchPhase.HalfTime
        val from = if (live) now - 5 * 60_000L else start - 30 * 60_000L
        val to = if (live) now + 5 * 60_000L else start + 150 * 60_000L
        return programmes.asSequence()
            .filter { it.entry.endMs > from && it.entry.startMs < to }
            .mapNotNull { programme ->
                val title = programme.entry.title
                val home = mentions(title, match.home.name, match.home.shortName)
                val away = mentions(title, match.away.name, match.away.shortName)
                val score = when {
                    home && away -> 3
                    (home || away) && "live" in words(title) -> 1
                    else -> 0
                }
                if (score == 0) null else programme to score
            }
            .sortedWith(
                compareByDescending<Pair<IptvWallProgramme, Int>> { it.second }
                    .thenByDescending { it.first.onNow(now) }
                    .thenByDescending { IptvChannelMerge.rank(it.first.channel.primary.name) },
            )
            .firstOrNull()?.first
    }
}

/** Start over: playing a programme from its beginning out of the provider's recordings ("catch-up"). */
object IptvCatchup {
    private val START: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    /** The copy of [channel] the provider records, if any; only Xtream accounts say. */
    fun source(playlist: IptvPlaylist, channel: IptvMergedChannel): IptvChannel? =
        if (playlist.type != IptvPlaylistType.Xtream) null
        else channel.sources.firstOrNull { it.archiveDays > 0 && it.streamId != null }

    /** Whether [entry] has begun and is still inside the days the provider keeps. */
    fun available(playlist: IptvPlaylist, channel: IptvMergedChannel, entry: IptvEpgEntry, now: Long): Boolean {
        val source = source(playlist, channel) ?: return false
        return entry.startMs < now - 60_000L && now - entry.startMs < source.archiveDays * 24 * 3_600_000L
    }

    /**
     * The link that plays [entry] from its start. [serverZone] is the account's time zone (the panel reads the
     * start in its own local time); null or unknown means UTC.
     */
    fun url(playlist: IptvPlaylist, source: IptvChannel, entry: IptvEpgEntry, serverZone: String?): String? {
        val id = source.streamId ?: return null
        val zone = serverZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
        val minutes = ((entry.endMs - entry.startMs) / 60_000L).toInt().coerceIn(1, 12 * 60)
        return XtreamUrls.timeshiftUrl(playlist, id, START.format(Instant.ofEpochMilli(entry.startMs).atZone(zone)), minutes)
    }
}

/** One tile of the wall. [key] is stable while the tile stands for the same thing. */
sealed interface IptvWallItem {
    val key: String

    /** A followed match with its score, and the programme showing it when the guide has one. */
    data class Match(val match: LiveMatch, val programme: IptvWallProgramme?) : IptvWallItem {
        override val key: String get() = "match:${match.id}"
    }

    /** A film or a sport programme from the guide. */
    data class Programme(
        val programme: IptvWallProgramme,
        val film: Boolean,
        /** fork:live-tv-decide - "SAFE", "STARTS SOON", "WILDCARD" on the Three things cards. */
        val tag: String? = null,
        /** fork:live-tv-decide - minutes the programme runs over what was dialled (shown dimmed); 0 = fits. */
        val over: Int = 0,
        /** fork:live-tv-sport-art - other channels showing the same programme, by name. */
        val alsoOn: List<String> = emptyList(),
    ) : IptvWallItem {
        override val key: String get() = "programme:${programme.channel.primary.uid}:${programme.entry.startMs}:${tag.orEmpty()}"
    }

    /** fork:live-tv-decide - a button tile: the minutes dial, Reshuffle. [id] says which. */
    data class Action(val id: String, val label: String, val note: String?, val selected: Boolean, val enabled: Boolean) : IptvWallItem {
        override val key: String get() = "action:$id"
    }

    /** fork:live-tv-decide - a tile with only words: an empty card, a follow the guide has nothing for. */
    data class Note(val id: String, val title: String, val text: String) : IptvWallItem {
        override val key: String get() = "note:$id"
    }

    /** A channel with what it is showing, for "Your channels". */
    data class Channel(val channel: IptvMergedChannel, val entry: IptvEpgEntry?) : IptvWallItem {
        override val key: String get() = "channel:${channel.primary.uid}"
    }
}

/** A titled row of the wall, [columns] tiles wide; a long list is several rows, only the first titled. */
data class IptvWallRow(val title: String?, val note: String?, val columns: Int, val items: List<IptvWallItem>)

class IptvWallModel(
    val sport: List<IptvWallItem>,
    val films: List<IptvWallItem.Programme>,
    val usuals: List<IptvWallItem.Channel>,
    /** How many of [sport] are matches with a score. */
    val matchCount: Int,
    /** fork:live-tv-departure - the departure board's buckets (empty with the switch off). */
    val soon: List<IptvDepartureBucket> = emptyList(),
    /** fork:live-tv-decide - the rows of the Decide for me sections, ready made (see [IptvDecide.sections]). */
    val sections: Map<String, List<IptvWallRow>> = emptyMap(),
) {
    private fun matches(item: IptvWallItem, needle: String): Boolean = when (item) {
        is IptvWallItem.Match -> item.match.home.name.contains(needle, true) || item.match.away.name.contains(needle, true) ||
            item.programme?.channel?.primary?.name?.contains(needle, true) == true
        is IptvWallItem.Programme -> item.programme.entry.title.contains(needle, true) || item.programme.channel.primary.name.contains(needle, true)
        is IptvWallItem.Channel -> item.channel.primary.name.contains(needle, true) || item.entry?.title?.contains(needle, true) == true
        is IptvWallItem.Action -> false
        is IptvWallItem.Note -> item.title.contains(needle, true)
    }

    private fun rowsOf(title: String, note: String?, columns: Int, items: List<IptvWallItem>): List<IptvWallRow> =
        items.chunked(columns).mapIndexed { index, chunk -> IptvWallRow(title.takeIf { index == 0 }, note.takeIf { index == 0 }, columns, chunk) }

    /** The rows of [section], narrowed to [query] (a title, a team or a channel name) when it is not blank. */
    fun rows(section: String, query: String = ""): List<IptvWallRow> {
        sections[section]?.let { return it }
        val needle = query.trim()
        fun <T : IptvWallItem> List<T>.narrowed(): List<T> = if (needle.isEmpty()) this else filter { matches(it, needle) }
        val sportNote = when {
            sport.isEmpty() -> null
            matchCount > 0 -> if (matchCount == 1) "1 match" else "$matchCount matches"
            else -> "from the guide"
        }
        val filmNote = if (films.isEmpty()) null else "${films.size} on now or starting soon"
        return when (section) {
            IptvWall.SOON -> soon.flatMap { bucket ->
                rowsOf(bucket.label, null, 3, bucket.programmes.map { IptvWallItem.Programme(it, film = false) }.narrowed())
            }
            IptvWall.SPORT -> rowsOf("Sport", sportNote, 2, sport.narrowed())
            IptvWall.FILMS -> rowsOf("Films", filmNote, 3, films.narrowed())
            else -> buildList {
                sport.narrowed().take(4).takeIf { it.isNotEmpty() }?.let { add(IptvWallRow("Live sport", sportNote, 4, it)) }
                films.narrowed().take(3).takeIf { it.isNotEmpty() }?.let { add(IptvWallRow("Films", filmNote, 3, it)) }
                usuals.narrowed().take(4).takeIf { it.isNotEmpty() }?.let { add(IptvWallRow("Your channels", "what you usually watch around now", 4, it)) }
            }
        }
    }
}

object IptvWall {
    /** Sidebar sections that show the wall instead of a channel list. Not in [IptvGroups]: the player's switcher has no use for them. */
    const val ON_NOW = "__onnow__"
    const val SPORT = "__sport__"
    const val FILMS = "__films__"
    const val SOON = "__soon__"
    val SECTIONS = setOf(ON_NOW, SPORT, FILMS, SOON) + IptvDecide.SECTIONS

    private const val MAX_FILMS = 30
    private const val MAX_SPORT = 24

    /**
     * [channels]: every merged channel the guide knows something about. [matches]: the live scores' followed matches
     * (empty with live scores off). [usuals]: the channels for "Your channels", likeliest first.
     */
    fun build(
        channels: List<IptvChannelNow>,
        matches: List<LiveMatch>,
        usuals: List<IptvMergedChannel>,
        now: Long,
        /** fork:live-tv-departure */
        soon: List<IptvDepartureBucket> = emptyList(),
        sections: Map<String, List<IptvWallRow>> = emptyMap(),
    ): IptvWallModel {
        val byUid = channels.associateBy { it.channel.primary.uid }
        val sportProgrammes = IptvOnNow.sport(channels, now)
        // Matches are looked for in everything about to be on, not only what was recognised as sport.
        val candidates = channels.flatMap { on ->
            on.entries.filter { it.endMs > now - 5 * 60_000L && it.startMs < now + 12 * 3_600_000L }.map { IptvWallProgramme(on.channel, it) }
        }
        val current = matches
            .filter { it.phase == MatchPhase.Live || it.phase == MatchPhase.HalfTime || (it.phase == MatchPhase.Before && (it.startEpochMs ?: 0L) in (now - 3_600_000L)..(now + 12 * 3_600_000L)) }
            .sortedWith(compareBy<LiveMatch> { if (it.phase == MatchPhase.Before) 1 else 0 }.thenBy { it.startEpochMs ?: Long.MAX_VALUE })
            .map { IptvWallItem.Match(it, IptvSportMatch.programmeFor(it, candidates, now)) }
        val taken = current.mapNotNullTo(HashSet()) { it.programme }
        // fork:live-tv-sport-art - a match's other copies are not listed again under it.
        val takenKeys = taken.mapTo(HashSet()) { IptvSportTitle.sameKey(it.entry.title) }
        val alsoOn = IptvOnNow.sportCopies(channels, now)
        val sport = current + sportProgrammes.filter { it !in taken && IptvSportTitle.sameKey(it.entry.title) !in takenKeys }
            .take(MAX_SPORT).map { IptvWallItem.Programme(it, film = false, alsoOn = alsoOn[it].orEmpty()) }
        val films = IptvOnNow.films(channels, now).take(MAX_FILMS).map { IptvWallItem.Programme(it, film = true) }
        val usualItems = usuals.map { IptvWallItem.Channel(it, byUid[it.primary.uid]?.current(now)) }
        return IptvWallModel(sport, films, usualItems, matchCount = current.size, soon = soon, sections = sections)
    }
}
