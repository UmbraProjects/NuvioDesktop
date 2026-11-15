package com.nuvio.app.features.livescores

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * fork:live-scores - the three feeds turned into LiveMatch. Pure, so they are tested against saved answers.
 * ESPN (keyless, unofficial): `site.api.espn.com/apis/site/v2/sports/<path>/scoreboard` and `.../summary?event=`.
 * TheSportsDB (free key "123"): boxing fight nights. API-Football (the user's own key): football when ESPN fails.
 */

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonElement?.bool(): Boolean = str() == "true"
private fun JsonObject?.at(vararg path: String): JsonElement? {
    var cursor: JsonElement? = this
    for (step in path) cursor = cursor.obj()?.get(step)
    return cursor
}

internal fun parseJsonOrNull(text: String): JsonObject? = runCatching { lenient.parseToJsonElement(text).obj() }.getOrNull()

// ---------------------------------------------------------------------------------------------------------------- ESPN

/** A scoreboard answer (one league, or a sport's "all" feed) as matches. */
fun parseEspnScoreboard(text: String, sport: LiveSport): List<LiveMatch> {
    val root = parseJsonOrNull(text) ?: return emptyList()
    val leagueName = root["leagues"].arr()?.firstOrNull().obj()?.get("name").str()
    val leagueId = root["leagues"].arr()?.firstOrNull().obj()?.get("id").str()
    val events = root["events"].arr() ?: return emptyList()
    return events.mapNotNull { element ->
        val event = element.obj() ?: return@mapNotNull null
        if (sport == LiveSport.Mma) return@mapNotNull parseEspnFightNight(event)
        val competition = event["competitions"].arr()?.firstOrNull().obj() ?: return@mapNotNull null
        val eventLeague = espnLeagueIdFromUid(event["uid"].str()) ?: leagueId
        val known = LiveScoreCatalog.forEspnLeague(sport, eventLeague)
        espnMatch(
            id = event["id"].str() ?: return@mapNotNull null,
            sport = sport,
            competition = competition,
            competitionKey = known?.key ?: eventLeague,
            competitionName = known?.name ?: leagueName.takeIf { known == null && eventLeague == leagueId },
            startIso = event["date"].str() ?: competition["date"].str(),
            incidents = competition["details"].arr().orEmpty().mapNotNull { espnIncident(it.obj(), sport) },
        )
    }
}

/**
 * A match report (`summary?event=`): the header's score and status plus its key events, which also carry missed
 * penalties and VAR, which the scoreboard leaves out.
 */
fun parseEspnSummary(text: String, sport: LiveSport, eventId: String): LiveMatch? {
    val root = parseJsonOrNull(text) ?: return null
    val header = root["header"].obj() ?: return null
    val competition = header["competitions"].arr()?.firstOrNull().obj() ?: return null
    val league = header["league"].obj()
    val leagueId = league?.get("id").str()
    val known = LiveScoreCatalog.forEspnLeague(sport, leagueId)
        ?: league?.get("slug").str()?.let { slug -> LiveScoreCatalog.competitions.firstOrNull { it.espnPath?.substringAfter('/') == slug } }
    val keyEvents = root["keyEvents"].arr().orEmpty().mapNotNull { espnIncident(it.obj(), sport) }
    val details = competition["details"].arr().orEmpty().mapNotNull { espnIncident(it.obj(), sport) }
    return espnMatch(
        id = header["id"].str() ?: eventId,
        sport = sport,
        competition = competition,
        competitionKey = known?.key ?: leagueId,
        competitionName = known?.name ?: league?.get("name").str(),
        startIso = competition["date"].str(),
        incidents = (keyEvents + details).distinctBy { it.key },
    )
}

private fun espnMatch(
    id: String,
    sport: LiveSport,
    competition: JsonObject,
    competitionKey: String?,
    competitionName: String?,
    startIso: String?,
    incidents: List<MatchIncident>,
): LiveMatch? {
    val competitors = competition["competitors"].arr().orEmpty().mapNotNull { it.obj() }
    val home = competitors.firstOrNull { it["homeAway"].str() == "home" } ?: competitors.getOrNull(0) ?: return null
    val away = competitors.firstOrNull { it["homeAway"].str() == "away" } ?: competitors.getOrNull(1) ?: return null
    val status = competition["status"].obj()
    val type = status?.get("type").obj()
    val statusName = type?.get("name").str() ?: "STATUS_UNKNOWN"
    val phase = espnPhase(type?.get("state").str(), statusName)
    return LiveMatch(
        feed = LiveFeed.Espn,
        id = id,
        sport = sport,
        competitionKey = competitionKey,
        competitionName = competitionName,
        home = espnTeam(home, sport) ?: return null,
        away = espnTeam(away, sport) ?: return null,
        homeScore = home["score"].str()?.toIntOrNull(),
        awayScore = away["score"].str()?.toIntOrNull(),
        phase = phase,
        statusName = statusName,
        clock = when (phase) {
            MatchPhase.Live -> status?.get("displayClock").str()?.takeUnless { it == "-" || it == "0'" }
                ?: type?.get("shortDetail").str()
            MatchPhase.HalfTime -> "HT"
            MatchPhase.Finished -> type?.get("shortDetail").str() ?: "FT"
            else -> null
        },
        startEpochMs = startIso?.let(::isoToEpochMs),
        incidents = incidents,
    )
}

private fun espnTeam(competitor: JsonObject, sport: LiveSport): LiveTeam? {
    val team = competitor["team"].obj() ?: competitor["athlete"].obj() ?: return null
    val name = team["displayName"].str() ?: team["name"].str() ?: return null
    return LiveTeam(
        sport = sport,
        id = team["id"].str() ?: competitor["id"].str() ?: name,
        name = name,
        shortName = team["shortDisplayName"].str() ?: team["abbreviation"].str() ?: name,
    )
}

internal fun espnPhase(state: String?, statusName: String): MatchPhase = when {
    statusName.contains("POSTPONED") || statusName.contains("CANCELED") || statusName.contains("CANCELLED") ||
        statusName.contains("ABANDONED") || statusName.contains("SUSPENDED") || statusName.contains("DELAYED") -> MatchPhase.Postponed
    statusName == "STATUS_HALFTIME" || statusName == "STATUS_HALF_TIME" -> MatchPhase.HalfTime
    state == "in" -> MatchPhase.Live
    state == "post" -> MatchPhase.Finished
    else -> MatchPhase.Before
}

/** One scoreboard `details` row or one summary `keyEvents` row. Both give the same key, so switching is quiet. */
private fun espnIncident(row: JsonObject?, sport: LiveSport): MatchIncident? {
    row ?: return null
    val text = row.at("type", "text").str() ?: return null
    val lower = text.lowercase()
    val football = sport == LiveSport.Football
    val kind = when {
        football && (row["ownGoal"].bool() || lower.contains("own goal")) -> IncidentKind.OwnGoal
        football && lower.contains("penalty") && (lower.contains("missed") || lower.contains("saved")) -> IncidentKind.PenaltyMissed
        football && lower.contains("var") -> IncidentKind.Var
        football && (lower.startsWith("penalty - scored") || (row["scoringPlay"].bool() && row["penaltyKick"].bool())) -> IncidentKind.PenaltyGoal
        football && (lower.startsWith("goal") || row["scoringPlay"].bool()) -> IncidentKind.Goal
        lower.contains("red card") || row["redCard"].bool() -> IncidentKind.RedCard
        lower.contains("yellow card") || row["yellowCard"].bool() -> IncidentKind.YellowCard
        !football && lower.contains("try") -> IncidentKind.Try
        !football && lower.contains("conversion") -> IncidentKind.Conversion
        !football && (lower.contains("penalty goal") || lower.contains("drop goal") || lower.contains("field goal")) -> IncidentKind.KickedPoints
        else -> IncidentKind.Other
    }
    if (kind == IncidentKind.Other) return null
    val clock = row.at("clock", "displayValue").str()
    val teamId = row.at("team", "id").str()
    val player = row["athletesInvolved"].arr()?.firstOrNull().obj()?.get("displayName").str()
        ?: row["participants"].arr()?.firstOrNull().obj()?.at("athlete", "displayName").str()
    return MatchIncident(
        key = incidentKey(kind, clock, teamId, player),
        kind = kind,
        teamId = teamId,
        clock = clock,
        player = player,
        detail = text,
    )
}

/** The same incident from either ESPN view (or a later poll) gets the same key. */
internal fun incidentKey(kind: IncidentKind, clock: String?, teamId: String?, player: String?): String {
    val group = when (kind) {
        IncidentKind.Goal, IncidentKind.PenaltyGoal, IncidentKind.OwnGoal -> "G"
        else -> kind.name
    }
    return listOf(group, clock.orEmpty().filter { it.isDigit() || it == '+' }, teamId.orEmpty(), player.orEmpty().lowercase()).joinToString("|")
}

/** A UFC card: one "match" per event, live from the first fight to the last; the result is the main event's. */
private fun parseEspnFightNight(event: JsonObject): LiveMatch? {
    val id = event["id"].str() ?: return null
    val fights = event["competitions"].arr().orEmpty().mapNotNull { it.obj() }
    if (fights.isEmpty()) return null
    val states = fights.map { it.at("status", "type", "state").str() }
    val phase = when {
        states.all { it == "post" } -> MatchPhase.Finished
        states.any { it == "in" || it == "post" } -> MatchPhase.Live
        else -> MatchPhase.Before
    }
    val main = fights.maxByOrNull { it["date"].str().orEmpty() } ?: fights.last()
    val fighters = main["competitors"].arr().orEmpty().mapNotNull { it.obj() }
    val red = fighters.getOrNull(0)?.let { espnTeam(it, LiveSport.Mma) } ?: LiveTeam(LiveSport.Mma, "$id-a", "TBC")
    val blue = fighters.getOrNull(1)?.let { espnTeam(it, LiveSport.Mma) } ?: LiveTeam(LiveSport.Mma, "$id-b", "TBC")
    val mainDone = main.at("status", "type", "state").str() == "post"
    val winner = fighters.firstOrNull { it["winner"].bool() }?.let { espnTeam(it, LiveSport.Mma)?.name }
    val loser = fighters.firstOrNull { !it["winner"].bool() }?.let { espnTeam(it, LiveSport.Mma)?.name }
    val method = main.at("status", "result", "displayName").str() ?: main.at("status", "type", "detail").str()
    return LiveMatch(
        feed = LiveFeed.Espn,
        id = id,
        sport = LiveSport.Mma,
        competitionKey = "ufc",
        competitionName = "UFC",
        home = red,
        away = blue,
        homeScore = null,
        awayScore = null,
        phase = phase,
        statusName = if (mainDone) "MAIN_DONE" else states.joinToString(",") { it.orEmpty() },
        clock = null,
        startEpochMs = (event["date"].str() ?: fights.first()["date"].str())?.let(::isoToEpochMs),
        headline = event["name"].str(),
        result = if (mainDone && winner != null) {
            listOfNotNull("$winner beat ${loser ?: "their opponent"}", method?.takeUnless { it.equals("Final", true) }).joinToString(" · ")
        } else {
            null
        },
    )
}

/** ESPN's `/teams` answer for a competition, for the favourites picker. */
fun parseEspnTeams(text: String, sport: LiveSport): List<LiveTeam> {
    val root = parseJsonOrNull(text) ?: return emptyList()
    val teams = root["sports"].arr()?.firstOrNull().obj()?.get("leagues").arr()?.firstOrNull().obj()?.get("teams").arr()
        ?: return emptyList()
    return teams.mapNotNull { row ->
        val team = row.obj()?.get("team").obj() ?: return@mapNotNull null
        val name = team["displayName"].str() ?: return@mapNotNull null
        LiveTeam(sport, team["id"].str() ?: return@mapNotNull null, name, team["shortDisplayName"].str() ?: name)
    }
}

// -------------------------------------------------------------------------------------------------------- TheSportsDB

/** Boxing fight nights from TheSportsDB's next/past league lists: start at the listed time, result once written. */
fun parseSportsDbFights(text: String, nowMs: Long): List<LiveMatch> {
    val root = parseJsonOrNull(text) ?: return emptyList()
    return root["events"].arr().orEmpty().mapNotNull { element ->
        val event = element.obj() ?: return@mapNotNull null
        val id = event["idEvent"].str() ?: return@mapNotNull null
        val title = event["strEvent"].str() ?: return@mapNotNull null
        val start = event["strTimestamp"].str()?.let { isoToEpochMs(if (it.endsWith("Z")) it else "${it}Z") }
        val status = event["strStatus"].str().orEmpty()
        val result = event["strResult"].str()?.trim()?.takeIf { it.isNotBlank() }
        val names = fighterNames(title)
        val phase = when {
            status.equals("Postponed", true) || status.equals("Cancelled", true) -> MatchPhase.Postponed
            result != null || status in setOf("FT", "Match Finished", "AOT") -> MatchPhase.Finished
            start != null && nowMs >= start -> MatchPhase.Live
            else -> MatchPhase.Before
        }
        LiveMatch(
            feed = LiveFeed.SportsDb,
            id = "tsdb:$id",
            sport = LiveSport.Boxing,
            competitionKey = "boxing",
            competitionName = "Boxing",
            home = LiveTeam(LiveSport.Boxing, "tsdb:$id:a", names.first),
            away = LiveTeam(LiveSport.Boxing, "tsdb:$id:b", names.second.ifBlank { "TBC" }),
            homeScore = null,
            awayScore = null,
            phase = phase,
            statusName = status.ifBlank { phase.name },
            clock = null,
            startEpochMs = start,
            headline = title,
            result = result?.let(::firstSentence),
        )
    }
}

/** "MVPW 07 Turhan vs Baumgardner" -> Turhan, Baumgardner: the promotion's code and card number are dropped. */
internal fun fighterNames(title: String): Pair<String, String> {
    val parts = title.split(" vs. ", " vs ", " v ", ignoreCase = true)
    if (parts.size < 2) return title to ""
    val first = parts[0].trim().split(' ')
        .dropWhile { word -> word.any(Char::isDigit) || (word.length > 1 && word.all { it.isUpperCase() || it.isDigit() }) }
        .joinToString(" ")
        .ifBlank { parts[0].trim() }
    return first to parts[1].trim()
}

private fun firstSentence(text: String): String {
    val end = text.indexOf(". ").takeIf { it in 20..220 }
    return (if (end != null) text.substring(0, end + 1) else text).take(220)
}

// ------------------------------------------------------------------------------------------------------- API-Football

/** `GET https://v3.football.api-sports.io/fixtures?live=all` (header `x-apisports-key`). */
fun parseApiFootballLive(text: String): List<LiveMatch> {
    val root = parseJsonOrNull(text) ?: return emptyList()
    return root["response"].arr().orEmpty().mapNotNull { element ->
        val row = element.obj() ?: return@mapNotNull null
        val fixture = row["fixture"].obj() ?: return@mapNotNull null
        val league = row["league"].obj()
        val known = LiveScoreCatalog.forApiFootball(league?.get("id").str()?.toIntOrNull())
        val home = row.at("teams", "home").obj() ?: return@mapNotNull null
        val away = row.at("teams", "away").obj() ?: return@mapNotNull null
        val short = fixture.at("status", "short").str().orEmpty()
        val elapsed = fixture.at("status", "elapsed").str()?.toIntOrNull()
        val phase = when (short) {
            "NS", "TBD" -> MatchPhase.Before
            "HT" -> MatchPhase.HalfTime
            "FT", "AET", "PEN" -> MatchPhase.Finished
            "PST", "CANC", "ABD", "SUSP", "INT", "AWD", "WO" -> MatchPhase.Postponed
            else -> MatchPhase.Live
        }
        LiveMatch(
            feed = LiveFeed.ApiFootball,
            id = "apif:${fixture["id"].str() ?: return@mapNotNull null}",
            sport = LiveSport.Football,
            competitionKey = known?.key ?: league?.get("id").str(),
            competitionName = known?.name ?: league?.get("name").str(),
            home = LiveTeam(LiveSport.Football, home["id"].str().orEmpty(), home["name"].str() ?: return@mapNotNull null),
            away = LiveTeam(LiveSport.Football, away["id"].str().orEmpty(), away["name"].str() ?: return@mapNotNull null),
            homeScore = row.at("goals", "home").str()?.toIntOrNull(),
            awayScore = row.at("goals", "away").str()?.toIntOrNull(),
            phase = phase,
            statusName = short,
            clock = when (phase) {
                MatchPhase.Live -> elapsed?.let { "$it'" }
                MatchPhase.HalfTime -> "HT"
                MatchPhase.Finished -> "FT"
                else -> null
            },
            startEpochMs = fixture["timestamp"].str()?.toLongOrNull()?.times(1000),
            incidents = row["events"].arr().orEmpty().mapNotNull { apiFootballIncident(it.obj()) },
        )
    }
}

private fun apiFootballIncident(row: JsonObject?): MatchIncident? {
    row ?: return null
    val type = row["type"].str()?.lowercase().orEmpty()
    val detail = row["detail"].str().orEmpty()
    val lower = detail.lowercase()
    val kind = when {
        type == "goal" && lower.contains("missed") -> IncidentKind.PenaltyMissed
        type == "goal" && lower.contains("own") -> IncidentKind.OwnGoal
        type == "goal" && lower.contains("penalty") -> IncidentKind.PenaltyGoal
        type == "goal" -> IncidentKind.Goal
        type == "card" && (lower.contains("red") || lower.contains("second yellow")) -> IncidentKind.RedCard
        type == "var" -> IncidentKind.Var
        else -> return null
    }
    val elapsed = row.at("time", "elapsed").str()
    val extra = row.at("time", "extra").str()?.takeUnless { it == "0" }
    val clock = elapsed?.let { if (extra != null) "$it'+$extra'" else "$it'" }
    val teamId = row.at("team", "id").str()
    val player = row.at("player", "name").str()
    return MatchIncident(incidentKey(kind, clock, teamId, player), kind, teamId, clock, player, detail.ifBlank { null })
}

// ------------------------------------------------------------------------------------------------------------- time

/** "2026-11-08T14:00Z", "2026-11-08T14:00:00Z" or with a fraction, as UTC epoch milliseconds. */
internal fun isoToEpochMs(text: String): Long? {
    val match = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""").find(text)
        ?: return null
    val (y, mo, d, h, mi) = match.destructured
    val s = match.groupValues[6].toIntOrNull() ?: 0
    val zone = match.groupValues[7]
    val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
    var seconds = days * 86_400L + h.toInt() * 3_600L + mi.toInt() * 60L + s
    if (zone.isNotEmpty() && zone != "Z") {
        val sign = if (zone[0] == '-') -1 else 1
        val digits = zone.drop(1).replace(":", "")
        seconds -= sign * (digits.take(2).toInt() * 3_600L + digits.drop(2).toInt() * 60L)
    }
    return seconds * 1000
}

/** Days since 1970-01-01 (Howard Hinnant's algorithm), so this file needs no date library. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468L
}
