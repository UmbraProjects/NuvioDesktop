package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.nuvio.app.features.livescores.LiveMatch
import com.nuvio.app.features.livescores.LiveSport

/**
 * fork:live-tv-sport-art - what a sports listing is about, read out of its title, and the pictures for it: the two
 * teams' badges, or the two fighters' photos. One reader and one picture source for every Live TV page, so a page only
 * asks [rememberSportArt] and draws [IptvMatchup] (IptvSportViews.kt).
 *
 * "European Rugby Champions Cup : Semi-Final: Saracens v Wasps ᴸⁱᵛᵉ" reads as competition "European Rugby Champions
 * Cup", round "Semi-Final", headline "Saracens v Wasps", live. Titles with no fixture in them read as nothing.
 */
data class IptvSportInfo(
    val competition: String?,
    val round: String?,
    /** "Saracens v Wasps", without the competition, the round or the live mark. */
    val headline: String,
    val sides: List<String>,
    val live: Boolean,
    /** TheSportsDB's sport name ("Soccer", "Rugby", "Cricket"...), when the title says which. */
    val sport: String?,
    val women: Boolean,
) {
    /** Fighters rather than teams: their photos, not badges. */
    val people: Boolean get() = sport == SPORT_BOXING || sport == SPORT_FIGHTING

    /** "EUROPEAN RUGBY CHAMPIONS CUP · SEMI-FINAL" for a tile's top line. */
    val label: String? get() = listOfNotNull(competition, round).joinToString(" · ").takeIf { it.isNotBlank() }

    companion object {
        const val SPORT_BOXING = "Boxing"
        const val SPORT_FIGHTING = "Fighting"
        const val SPORT_RUGBY_LEAGUE = "Rugby League"
    }
}

/** One side's name and picture (a badge, or a fighter's photo when [person]); [picture] null when none was found. */
class IptvSportSide(val name: String, val picture: String?, val person: Boolean)

class IptvSportArt(val info: IptvSportInfo, val sides: List<IptvSportSide>) {
    val anyPicture: Boolean get() = sides.any { it.picture != null }
}

object IptvSportTitle {
    /** "ᴸⁱᵛᵉ" and the like that providers glue to a title: modifier letters at the end. */
    private val LIVE_MARK = Regex("""\s*[A-Za-z]?\p{Lm}{2,}\s*$""")
    private val LIVE_WORD = Regex("""(?:^live[:\s-]+|\s*[(\[]live[)\]]|\s+-\s+live$|\s+live$)""", RegexOption.IGNORE_CASE)
    private val PARTS = Regex("""\s*:\s+|\s+:\s*""")
    private val VERSUS = Regex("""\s(?:v|vs|vs\.|versus)\s""", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("""\s+[-–]\s+(.+)$""")

    private val SPORTS = listOf(
        listOf("rugby league", "rlwc", "nrl", "betfred super league", "rugby league world cup") to IptvSportInfo.SPORT_RUGBY_LEAGUE,
        listOf("rugby") to "Rugby",
        listOf("cricket", "t10", "t20", "wbbl", "bbl", "ipl", "test match", "odi", "the hundred") to "Cricket",
        listOf("boxing", "fight night") to IptvSportInfo.SPORT_BOXING,
        listOf("ufc", "mma", "pfl", "bellator", "cage") to IptvSportInfo.SPORT_FIGHTING,
        listOf("nfl", "cfl", "grey cup", "college football", "american football", "super bowl") to "American Football",
        listOf("nba", "wnba", "basketball", "euroleague") to "Basketball",
        listOf("nhl", "hockey") to "Ice Hockey",
        listOf("afl", "aussie rules") to "Australian Football",
        listOf("mlb", "baseball") to "Baseball",
        listOf("darts") to "Darts",
        listOf(
            "football", "soccer", "premier league", "laliga", "la liga", "bundesliga", "serie a", "ligue 1", "champions league",
            "europa", "fa cup", "efl", "championship", "spfl", "mls", "eredivisie", "wsl", "women's super league", "copa", "uefa", "fifa",
        ) to "Soccer",
    )

    fun sportOf(text: String): String? {
        val lower = " ${text.lowercase()} "
        return SPORTS.firstOrNull { (words, _) -> words.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(lower) } }?.second
    }

    /**
     * One key for every copy of a programme: "Final: Australia v New Zealand ᴸⁱᵛᵉ" on one channel and "Final:
     * Australia v New Zealand" on another are the same match.
     */
    fun sameKey(title: String): String {
        var clean = title.trim()
        LIVE_MARK.find(clean)?.let { clean = clean.substring(0, it.range.first) }
        LIVE_WORD.find(clean.trim())?.let { clean = clean.trim().removeRange(it.range) }
        return IptvDecide.normalise(clean).replace(Regex("\\s+"), " ")
    }

    /** Whether a provider marked [title] as the live broadcast. */
    fun markedLive(title: String): Boolean = LIVE_MARK.containsMatchIn(title.trim()) || LIVE_WORD.containsMatchIn(title.trim())

    /** [title] without the "ᴸⁱᵛᵉ" mark some providers glue on. */
    fun clean(title: String): String = LIVE_MARK.find(title)?.let { title.substring(0, it.range.first).trim() } ?: title

    /** What [title] is about, or null when it is not a fixture (no "v" in it) or is a film. */
    fun parse(title: String, kind: String?): IptvSportInfo? {
        if (kind == IptvProgrammeKind.FILM || title.isBlank()) return null
        var live = false
        var clean = title.trim()
        LIVE_MARK.find(clean)?.let { live = true; clean = clean.substring(0, it.range.first).trim() }
        LIVE_WORD.find(clean)?.let { live = true; clean = clean.removeRange(it.range).trim() }
        val parts = clean.split(PARTS).map { it.trim() }.filter { it.isNotEmpty() }
        val at = parts.indexOfLast { VERSUS.containsMatchIn(" $it ") }
        if (at < 0) return null
        var headline = parts[at]
        val rounds = parts.subList(minOf(1, at), at).toMutableList()
        TAIL.find(headline)?.let { tail ->
            rounds += tail.groupValues[1].trim()
            headline = headline.substring(0, tail.range.first).trim()
        }
        val sides = IptvTeamRules.teamsIn(headline, kind)
        if (sides.size != 2) return null
        val competition = parts.firstOrNull()?.takeIf { at > 0 }
        return IptvSportInfo(
            competition = competition,
            round = rounds.filter { it.isNotBlank() && it != competition }.joinToString(" · ").takeIf { it.isNotBlank() },
            headline = "${sides[0]} v ${sides[1]}",
            sides = sides,
            live = live,
            sport = sportOf(title),
            women = Regex("""\bwom[ae]n'?s?\b""", RegexOption.IGNORE_CASE).containsMatchIn(title),
        )
    }

    /** "Zuffa Boxing 13 Adames" -> event "Zuffa Boxing 13", fighter "Adames": the fight night's name is not the fighter's. */
    fun splitFighter(name: String): Pair<String?, String> {
        val words = name.trim().split(Regex("\\s+"))
        val lastNumber = words.indexOfLast { w -> w.any { it.isDigit() } }
        if (lastNumber < 0 || lastNumber == words.lastIndex) return null to name.trim()
        return words.subList(0, lastNumber + 1).joinToString(" ") to words.subList(lastNumber + 1, words.size).joinToString(" ")
    }

    /** A live-scores match as the same kind of info, so its tile can show the same badges and photos. */
    fun ofMatch(match: LiveMatch): IptvSportInfo {
        val sport = when (match.sport) {
            LiveSport.Football -> "Soccer"
            LiveSport.Rugby -> "Rugby"
            LiveSport.RugbyLeague -> IptvSportInfo.SPORT_RUGBY_LEAGUE
            LiveSport.Mma -> IptvSportInfo.SPORT_FIGHTING
            LiveSport.Boxing -> IptvSportInfo.SPORT_BOXING
        }
        val fight = sport == IptvSportInfo.SPORT_BOXING || sport == IptvSportInfo.SPORT_FIGHTING
        val (event, home) = if (fight) splitFighter(match.home.name) else null to match.home.name
        val away = if (fight) splitFighter(match.away.name).second else match.away.name
        return IptvSportInfo(
            competition = event ?: match.competitionName,
            round = null,
            headline = "$home v $away",
            sides = listOf(home, away),
            live = false,
            sport = sport,
            women = Regex("""\bwom[ae]n\b""", RegexOption.IGNORE_CASE).containsMatchIn("${match.competitionName} ${match.home.name}"),
        )
    }
}

/** What a programme title is about; null when it is not a fixture. Cheap: read once per title. */
@Composable
fun rememberSportInfo(title: String?, kind: String?): IptvSportInfo? = remember(title, kind) { title?.let { IptvSportTitle.parse(it, kind) } }

/** [info]'s two sides with their pictures, filled in as the sources answer (IptvSportPictures); names alone until then. */
@Composable
fun rememberSportArt(info: IptvSportInfo?): IptvSportArt? {
    fun sides(pictures: List<String?>) = info!!.sides.mapIndexed { i, name -> IptvSportSide(name, pictures.getOrNull(i), info.people) }
    val initial = info?.let { i -> IptvSportArt(i, sides(i.sides.map { IptvSportPictures.peek(it, i.sport, i.people, i.women)?.ifEmpty { null } })) }
    return produceState(initial, info) {
        val i = info ?: return@produceState
        val pictures = i.sides.map { IptvSportPictures.picture(it, i.sport, i.people, i.women) }
        value = IptvSportArt(i, sides(pictures))
    }.value
}
