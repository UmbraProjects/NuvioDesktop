package com.nuvio.app.features.livescores

/**
 * fork:live-scores - the competitions the user can follow. Nothing is followed until the user picks (user, Nov 11).
 *
 * [espnPath] is the ESPN site-API path (`sports/<path>/scoreboard`); [espnLeagueId] the numeric league id ESPN puts in
 * an event's uid (`s:600~l:700~e:...`), which is how a match from the "all" feed is matched to a competition. For
 * rugby the two differ (the Six Nations is `rugby/180659` but `l:8323`).
 * [apiFootballId] maps it on the keyed backup (API-Football), football only. Ids checked against the live feeds on
 * Nov 11, 2026.
 */
data class LiveCompetition(
    val key: String,
    val sport: LiveSport,
    val name: String,
    val espnPath: String?,
    val espnLeagueId: String?,
    val apiFootballId: Int? = null,
    /** Boxing comes from TheSportsDB: its league id. */
    val sportsDbLeagueId: String? = null,
)

object LiveScoreCatalog {
    val competitions: List<LiveCompetition> = listOf(
        // Football
        LiveCompetition("eng.1", LiveSport.Football, "Premier League", "soccer/eng.1", "700", 39),
        LiveCompetition("eng.fa", LiveSport.Football, "FA Cup", "soccer/eng.fa", "3918", 45),
        LiveCompetition("eng.league_cup", LiveSport.Football, "Carabao Cup", "soccer/eng.league_cup", "3920", 48),
        LiveCompetition("eng.2", LiveSport.Football, "Championship", "soccer/eng.2", "3914", 40),
        LiveCompetition("eng.3", LiveSport.Football, "League One", "soccer/eng.3", "3915", 41),
        LiveCompetition("eng.4", LiveSport.Football, "League Two", "soccer/eng.4", "3916", 42),
        LiveCompetition("sco.1", LiveSport.Football, "Scottish Premiership", "soccer/sco.1", "735", 179),
        LiveCompetition("eng.w.1", LiveSport.Football, "Women's Super League", "soccer/eng.w.1", "8097", 44),
        LiveCompetition("uefa.champions", LiveSport.Football, "Champions League", "soccer/uefa.champions", "775", 2),
        LiveCompetition("uefa.europa", LiveSport.Football, "Europa League", "soccer/uefa.europa", "776", 3),
        LiveCompetition("uefa.europa.conf", LiveSport.Football, "Conference League", "soccer/uefa.europa.conf", "20296", 848),
        LiveCompetition("fifa.world", LiveSport.Football, "World Cup", "soccer/fifa.world", "606", 1),
        LiveCompetition("uefa.nations", LiveSport.Football, "Nations League", "soccer/uefa.nations", "2395", 5),
        LiveCompetition("fifa.friendly", LiveSport.Football, "International friendlies", "soccer/fifa.friendly", "3922", 10),
        // Rugby union (ESPN uses numeric league slugs for rugby)
        LiveCompetition("rugby.267979", LiveSport.Rugby, "Premiership Rugby", "rugby/267979", "8007"),
        LiveCompetition("rugby.180659", LiveSport.Rugby, "Six Nations", "rugby/180659", "8323"),
        LiveCompetition("rugby.271937", LiveSport.Rugby, "Champions Cup", "rugby/271937", "8329"),
        LiveCompetition("rugby.272073", LiveSport.Rugby, "Challenge Cup", "rugby/272073", "8010"),
        LiveCompetition("rugby.270557", LiveSport.Rugby, "United Rugby Championship", "rugby/270557", "8331"),
        LiveCompetition("rugby.270559", LiveSport.Rugby, "Top 14", "rugby/270559", "8005"),
        LiveCompetition("rugby.289234", LiveSport.Rugby, "International tests", "rugby/289234", "10659"),
        LiveCompetition("rugby.17567", LiveSport.Rugby, "Nations Championship", "rugby/17567", "24400"),
        LiveCompetition("rugby.164205", LiveSport.Rugby, "Rugby World Cup", "rugby/164205", "8337"),
        LiveCompetition("rugby.268565", LiveSport.Rugby, "British & Irish Lions", "rugby/268565", "8016"),
        // Rugby league: ESPN serves every competition under one slug.
        LiveCompetition("rugby-league", LiveSport.RugbyLeague, "Rugby league (Super League and more)", "rugby-league/3", "3"),
        // Fight nights: start and result only.
        LiveCompetition("ufc", LiveSport.Mma, "UFC", "mma/ufc", null),
        LiveCompetition("boxing", LiveSport.Boxing, "Boxing", null, null, sportsDbLeagueId = "4445"),
    )

    private val byKey = competitions.associateBy { it.key }
    private val byEspnLeague = competitions.filter { it.espnLeagueId != null }.associateBy { it.sport to it.espnLeagueId }
    private val byApiFootball = competitions.filter { it.apiFootballId != null }.associateBy { it.apiFootballId }

    fun byKey(key: String): LiveCompetition? = byKey[key]

    fun forEspnLeague(sport: LiveSport, leagueId: String?): LiveCompetition? =
        leagueId?.let { byEspnLeague[sport to it] } ?: if (sport == LiveSport.RugbyLeague) byKey["rugby-league"] else null

    fun forApiFootball(leagueId: Int?): LiveCompetition? = leagueId?.let { byApiFootball[it] }

    /** The ESPN "all leagues" feed per sport, used to find favourites' matches wherever they play. */
    fun espnAllPath(sport: LiveSport): String? = when (sport) {
        LiveSport.Football -> "soccer/all"
        LiveSport.Rugby -> "rugby/all"
        LiveSport.RugbyLeague -> "rugby-league/3"
        LiveSport.Mma -> "mma/ufc"
        LiveSport.Boxing -> null
    }

    /** Where Settings looks for teams to pick as favourites. */
    val teamSources: List<LiveCompetition> = listOf(
        "eng.1", "eng.2", "eng.3", "eng.4", "sco.1", "eng.w.1", "uefa.champions", "uefa.nations",
        "rugby.267979", "rugby.180659", "rugby.270557", "rugby.270559", "rugby.289234", "rugby-league",
    ).mapNotNull(byKey::get)
}

/** ESPN's league id inside an event uid: `s:600~l:700~e:401879244` -> "700". */
internal fun espnLeagueIdFromUid(uid: String?): String? =
    uid?.split('~')?.firstOrNull { it.startsWith("l:") }?.removePrefix("l:")?.takeIf { it.isNotBlank() }

/** For matching a favourite across feeds by name: "Manchester Utd FC" and "Manchester United" agree. */
internal fun teamNameKey(name: String): String {
    val words = name.lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .map { if (it == "utd") "united" else it }
        .filterNot { it in setOf("fc", "afc", "cf", "the", "rfc") }
    return words.joinToString(" ")
}
