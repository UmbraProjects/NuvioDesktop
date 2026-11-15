package com.nuvio.app.features.livescores

/**
 * fork:live-scores - what to pop up when a match is seen again.
 *
 * Rules:
 * - The first look at a match pops nothing: what had already happened is marked as seen (no flood when the app opens
 *   mid-match, or when a match first becomes followed).
 * - Phase changes: before -> live is kick-off, live -> half-time, live or half-time -> finished is full-time
 *   (before -> finished, a match missed while away, is full-time too). Fight nights: start, then the main result.
 * - New incidents (by key) become events: goals and penalty goals and own goals, tries, penalty and drop goals,
 *   red cards (and rugby yellow cards: a sin bin changes a rugby match), missed penalties, VAR. Conversions do not
 *   pop on their own: the next card shows the score.
 * - The score is the check: a side's score rising with no new scoring incident still pops (the feed's detail lags),
 *   a side's score falling pops "Goal ruled out" (VAR) even when the feed has no VAR row.
 */
data class DiffResult(val events: List<ScoreEvent>, val seen: Set<String>)

fun detectScoreEvents(
    previous: LiveMatch?,
    current: LiveMatch,
    favourite: Boolean,
    seen: Set<String>,
): DiffResult {
    val incidentKeys = current.incidents.mapTo(mutableSetOf()) { it.key }
    if (previous == null) return DiffResult(emptyList(), seen + incidentKeys + phaseKeys(current))

    val events = mutableListOf<ScoreEvent>()
    val nowSeen = seen.toMutableSet()
    fun emit(kind: ScoreEventKind, key: String, forHome: Boolean?, detail: String?, clock: String?, alsoKey: String? = null) {
        val full = "${current.id}|$key"
        val also = alsoKey?.let { "${current.id}|$it" }
        if (full in nowSeen || (also != null && also in nowSeen)) {
            nowSeen += full
            also?.let(nowSeen::add)
            return
        }
        nowSeen += full
        also?.let(nowSeen::add)
        events += ScoreEvent(kind, current, forHome, detail, clock, favourite, full)
    }

    val fightNight = current.sport == LiveSport.Mma || current.sport == LiveSport.Boxing
    if (fightNight) {
        if (previous.phase == MatchPhase.Before && current.phase in setOf(MatchPhase.Live, MatchPhase.Finished)) {
            emit(ScoreEventKind.FightStart, "start", null, current.headline, null)
        }
        if (previous.result == null && current.result != null) {
            emit(ScoreEventKind.FightResult, "result", null, current.result, null)
        }
        return DiffResult(events, nowSeen + incidentKeys.map { "${current.id}|$it" })
    }

    val started = previous.phase == MatchPhase.Before && current.phase in setOf(MatchPhase.Live, MatchPhase.HalfTime)
    if (started) emit(ScoreEventKind.KickOff, "kickoff", null, null, current.clock)

    // Incidents the last look did not have.
    val previousKeys = previous.incidents.mapTo(mutableSetOf()) { it.key }
    val fresh = current.incidents.filter { it.key !in previousKeys && "${current.id}|${it.key}" !in seen }
    val homeRose = (current.homeScore ?: 0) > (previous.homeScore ?: 0)
    val awayRose = (current.awayScore ?: 0) > (previous.awayScore ?: 0)
    var scoringShown = false
    val goalOrdinal = goalOrdinals(current)
    for (incident in fresh) {
        val side = sideOf(incident, current, homeRose, awayRose)
        when (incident.kind) {
            IncidentKind.Goal, IncidentKind.PenaltyGoal, IncidentKind.OwnGoal -> {
                scoringShown = true
                val detail = when (incident.kind) {
                    IncidentKind.PenaltyGoal -> listOfNotNull(incident.player, "pen").joinToString(" · ")
                    IncidentKind.OwnGoal -> listOfNotNull(incident.player, "own goal").joinToString(" · ")
                    else -> incident.player
                }
                emit(ScoreEventKind.Goal, incident.key, side, detail, incident.clock, goalOrdinal[incident.key]?.let { "goal#$it" })
            }
            IncidentKind.Try -> {
                scoringShown = true
                emit(ScoreEventKind.Try, incident.key, side, incident.player, incident.clock)
            }
            IncidentKind.KickedPoints -> {
                scoringShown = true
                val what = incident.detail?.lowercase()?.replaceFirstChar { it.uppercase() }
                emit(ScoreEventKind.Points, incident.key, side, listOfNotNull(incident.player, what).joinToString(" · ").ifBlank { null }, incident.clock)
            }
            IncidentKind.Conversion -> scoringShown = true
            IncidentKind.RedCard -> emit(ScoreEventKind.RedCard, incident.key, side, incident.player, incident.clock)
            IncidentKind.YellowCard -> if (current.sport != LiveSport.Football) {
                emit(ScoreEventKind.SinBin, incident.key, side, incident.player, incident.clock)
            }
            IncidentKind.PenaltyMissed -> emit(ScoreEventKind.PenaltyMissed, incident.key, side, incident.player, incident.clock)
            IncidentKind.Var -> emit(ScoreEventKind.Var, incident.key, side, incident.detail ?: "VAR check", incident.clock)
            IncidentKind.Other -> Unit
        }
    }

    // The score is the check. Football goals are counted, so a goal shown from the score is not shown again when its
    // row arrives (and the other way round). Rugby points cannot be counted: there it only fills in for a feed that
    // reports no scoring rows at all.
    val scoreKey = "score:${current.homeScore}-${current.awayScore}"
    val scoreSide = if (homeRose && !awayRose) true else if (awayRose && !homeRose) false else null
    if (!scoringShown && (homeRose || awayRose) && !started) {
        if (current.sport == LiveSport.Football) {
            val total = (current.homeScore ?: 0) + (current.awayScore ?: 0)
            emit(ScoreEventKind.Goal, scoreKey, scoreSide, null, current.clock, "goal#$total")
        } else if (current.incidents.none { it.kind in ScoringKinds }) {
            emit(ScoreEventKind.Points, scoreKey, scoreSide, null, current.clock)
        }
    }
    val homeFell = (current.homeScore ?: 0) < (previous.homeScore ?: 0)
    val awayFell = (current.awayScore ?: 0) < (previous.awayScore ?: 0)
    if ((homeFell || awayFell) && current.homeScore != null && current.awayScore != null) {
        emit(ScoreEventKind.Var, "$scoreKey:down", if (homeFell) true else false, "Goal ruled out", current.clock)
        // The goal's number is free again for the next real goal.
        val total = (current.homeScore ?: 0) + (current.awayScore ?: 0)
        nowSeen.removeAll { key -> key.startsWith("${current.id}|goal#") && (key.substringAfterLast('#').toIntOrNull() ?: 0) > total }
        nowSeen.removeAll { key -> key.startsWith("${current.id}|score:") && !key.endsWith(":down") }
    }

    if (previous.phase == MatchPhase.Live && current.phase == MatchPhase.HalfTime) {
        emit(ScoreEventKind.HalfTime, "halftime", null, null, "HT")
    }
    if (previous.phase in setOf(MatchPhase.Before, MatchPhase.Live, MatchPhase.HalfTime) && current.phase == MatchPhase.Finished) {
        emit(ScoreEventKind.FullTime, "fulltime", null, null, current.clock ?: "FT")
    }
    return DiffResult(events, nowSeen + incidentKeys.map { "${current.id}|$it" })
}

private val ScoringKinds = setOf(
    IncidentKind.Goal, IncidentKind.PenaltyGoal, IncidentKind.OwnGoal, IncidentKind.Try, IncidentKind.Conversion, IncidentKind.KickedPoints,
)

/** Football: each goal row's number in the match (the feeds list them in time order). */
private fun goalOrdinals(match: LiveMatch): Map<String, Int> {
    if (match.sport != LiveSport.Football) return emptyMap()
    var count = 0
    return match.incidents
        .filter { it.kind == IncidentKind.Goal || it.kind == IncidentKind.PenaltyGoal || it.kind == IncidentKind.OwnGoal }
        .associate { it.key to ++count }
}

/** Keys for phases already reached at the first look, so a reload never repeats a kick-off or full-time. */
private fun phaseKeys(match: LiveMatch): List<String> = buildList {
    if (match.phase != MatchPhase.Before) {
        add("${match.id}|kickoff")
        add("${match.id}|start")
    }
    if (match.phase == MatchPhase.HalfTime) add("${match.id}|halftime")
    if (match.phase == MatchPhase.Finished) {
        add("${match.id}|halftime")
        add("${match.id}|fulltime")
    }
    if (match.result != null) add("${match.id}|result")
    match.incidents.forEach { add("${match.id}|${it.key}") }
    if (match.sport == LiveSport.Football) {
        val total = (match.homeScore ?: 0) + (match.awayScore ?: 0)
        (1..maxOf(total, goalOrdinals(match).size)).forEach { add("${match.id}|goal#$it") }
    }
}

/**
 * Which side an incident belongs to. For a score, the side whose score rose wins when exactly one did (feeds
 * disagree on which team an own goal is filed under); otherwise the incident's own team.
 */
private fun sideOf(incident: MatchIncident, match: LiveMatch, homeRose: Boolean, awayRose: Boolean): Boolean? {
    val scoring = incident.kind in setOf(IncidentKind.Goal, IncidentKind.PenaltyGoal, IncidentKind.OwnGoal, IncidentKind.Try, IncidentKind.KickedPoints)
    if (scoring && homeRose != awayRose) return homeRose
    return when (incident.teamId) {
        null -> null
        match.home.id -> true
        match.away.id -> false
        else -> null
    }
}

/** Whether [team] is one of [favourites]: same sport and the same id, or the same name on another feed. */
fun isFavouriteTeam(team: LiveTeam, favourites: List<LiveTeam>): Boolean = favourites.any { favourite ->
    favourite.sport == team.sport && (favourite.id == team.id || teamNameKey(favourite.name) == teamNameKey(team.name))
}

/** A match is followed when either side is a favourite or its competition is followed. */
fun isFollowedMatch(match: LiveMatch, favourites: List<LiveTeam>, followed: Set<String>): Boolean =
    match.competitionKey in followed || isFavouriteTeam(match.home, favourites) || isFavouriteTeam(match.away, favourites)

/** The score line the cards show: "Man Utd 2 – 1 Liverpool", or the fight headline. */
fun scoreLine(match: LiveMatch, short: Boolean = true): String {
    if (match.sport == LiveSport.Mma || match.sport == LiveSport.Boxing) return match.headline ?: "${match.home.name} vs ${match.away.name}"
    val home = if (short) match.home.shortName else match.home.name
    val away = if (short) match.away.shortName else match.away.name
    return if (match.homeScore == null || match.awayScore == null) "$home v $away" else "$home ${match.homeScore} – ${match.awayScore} $away"
}

/** A notification's two lines: "GOAL · Man United 2 – 1 Liverpool" and "Bruno Fernandes · pen · 67' · Premier League". */
fun scoreNotificationText(event: ScoreEvent): Pair<String, String> {
    val title = "${event.kind.label} · ${scoreLine(event.match)}"
    val text = listOfNotNull(
        event.detail?.takeIf { it.isNotBlank() },
        event.clock?.takeIf { it.isNotBlank() },
        event.match.competitionName ?: event.match.sport.label,
    ).joinToString(" · ")
    return title to text
}
