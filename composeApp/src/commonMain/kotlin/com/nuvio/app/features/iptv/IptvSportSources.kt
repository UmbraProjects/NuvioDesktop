package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.livescores.teamNameKey
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * fork:live-tv-sport-art - where a side's picture comes from, best first:
 *  1. ESPN's team lists (every team's logo, about forty leagues, read once a week and matched here with no request per
 *     team): football, rugby union and league, NHL, NBA, WNBA, NFL, MLB, AFL.
 *  2. Wikipedia's lead image for the side's article (crests of the clubs ESPN does not list, cricket franchises,
 *     fighters' photos), found with the sport in the search so "Saracens" is the rugby club.
 *  3. TheSportsDB: its free key only searches a few names, so it is the last try, one request every two seconds.
 * Whatever is found, or not, is kept on disk; a miss is asked again after three days.
 */
internal object IptvSportPictures {
    private const val FILE = "sport-art-v2.json"
    private const val FOUND_DAYS = 60L
    private const val MISSING_DAYS = 3L
    private const val DAY_MS = 24 * 3_600_000L

    @Serializable
    data class Known(val url: String? = null, val at: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), Known.serializer())
    private val known = ConcurrentHashMap<String, Known>()
    private val diskLock = Mutex()
    @Volatile private var loaded = false

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                IptvCacheStorage.openRead(FILE)?.use { known.putAll(json.decodeFromString(serializer, it.readBytes().decodeToString())) }
            }
            // The first version's file: TheSportsDB-only misses that would hide pictures the new sources find.
            runCatching { IptvCacheStorage.delete("sportsdb-art.json") }
            loaded = true
        }
    }

    private suspend fun save() = diskLock.withLock {
        runCatching { IptvCacheStorage.write(FILE) { it.write(json.encodeToString(serializer, HashMap(known)).encodeToByteArray()) } }
    }

    private fun fresh(k: Known, now: Long) = now - k.at < (if (k.url != null) FOUND_DAYS else MISSING_DAYS) * DAY_MS

    private fun key(name: String, sport: String?, person: Boolean, women: Boolean) =
        "${if (person) "p" else "t"}|${sport.orEmpty()}|${if (women) "w" else ""}|${IptvDecide.normalise(name)}"

    /** What is already known: the picture, "" for known to have none, null for not looked for yet. */
    fun peek(name: String, sport: String?, person: Boolean, women: Boolean): String? {
        load()
        val k = known[key(name, sport, person, women)] ?: return null
        return if (fresh(k, System.currentTimeMillis())) k.url.orEmpty() else null
    }

    /** The picture for one side, or null. A source that could not be reached is not taken as "none". */
    suspend fun picture(name: String, sport: String?, person: Boolean, women: Boolean): String? {
        load()
        val k = key(name, sport, person, women)
        known[k]?.takeIf { fresh(it, System.currentTimeMillis()) }?.let { return it.url }
        var unreachable = false
        suspend fun tryOne(source: suspend () -> String?): String? = try {
            source()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            unreachable = true
            null
        }
        val found = (if (person) null else tryOne { IptvEspnTeams.logo(name, sport, women) })
            ?: tryOne { IptvWikiPictures.find(name, sport, person, women) }
            ?: tryOne { IptvSportsDbPictures.find(name, sport, person, women) }
        if (found == null && unreachable) return null
        known[k] = Known(found, System.currentTimeMillis())
        save()
        return found
    }
}

/** Shared plumbing for the picture sources. */
internal object SportHttp {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    class Unreachable(status: Int) : Exception("HTTP $status")

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject? {
        val response = httpRequestRaw("GET", url, mapOf("Accept" to "application/json") + headers, "", callTimeoutMs = 12_000L)
        if (response.status !in 200..299) throw Unreachable(response.status)
        return runCatching { json.parseToJsonElement(response.body) as? JsonObject }.getOrNull()
    }

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** "man utd" against "manchester united": every word of [query] starts a word of [name], in order. */
    fun wordsStartInOrder(name: String, query: String): Boolean {
        val words = name.split(' ').filter { it.isNotBlank() }
        var at = 0
        for (part in query.split(' ').filter { it.isNotBlank() }) {
            while (at < words.size && !words[at].startsWith(part)) at++
            if (at == words.size) return false
            at++
        }
        return true
    }
}

/** ESPN's team lists, with logos: fetched together once a week, kept on disk, searched here. */
internal object IptvEspnTeams {
    private const val FILE = "espn-teams-v1.json"
    private const val KEEP_MS = 7 * 24 * 3_600_000L

    @Serializable
    data class Team(val sport: String, val name: String, val short: String, val location: String = "", val abbr: String = "", val logo: String, val women: Boolean = false)

    @Serializable
    private data class Stored(val at: Long, val teams: List<Team>)

    /** ESPN site-API path, and the sport in TheSportsDB's words (what IptvSportInfo.sport holds). */
    private val LEAGUES = listOf(
        "soccer/eng.1", "soccer/eng.2", "soccer/eng.3", "soccer/eng.4", "soccer/sco.1", "soccer/eng.w.1", "soccer/esp.1", "soccer/ger.1",
        "soccer/ita.1", "soccer/fra.1", "soccer/ned.1", "soccer/por.1", "soccer/bel.1", "soccer/tur.1", "soccer/usa.1", "soccer/usa.nwsl",
        "soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf", "soccer/uefa.nations", "soccer/fifa.world",
        "soccer/fifa.wwc", "soccer/uefa.weuro",
    ).map { it to "Soccer" } + listOf(
        "rugby/267979", "rugby/180659", "rugby/271937", "rugby/272073", "rugby/270557", "rugby/270559", "rugby/289234", "rugby/164205",
    ).map { it to "Rugby" } + listOf(
        "rugby-league/3" to IptvSportInfo.SPORT_RUGBY_LEAGUE,
        "hockey/nhl" to "Ice Hockey",
        "basketball/nba" to "Basketball",
        "basketball/wnba" to "Basketball",
        "football/nfl" to "American Football",
        "baseball/mlb" to "Baseball",
        "australian-football/afl" to "Australian Football",
    )

    private val lock = Mutex()
    @Volatile private var teams: List<Team>? = null

    private suspend fun all(): List<Team> = lock.withLock {
        teams?.let { return it }
        val serializer = Stored.serializer()
        val stored = runCatching {
            IptvCacheStorage.openRead(FILE)?.use { SportHttp.json.decodeFromString(serializer, it.readBytes().decodeToString()) }
        }.getOrNull()
        if (stored != null && System.currentTimeMillis() - stored.at < KEEP_MS) return stored.teams.also { teams = it }
        val fetched = fetch()
        // A failed refresh keeps last week's list rather than none.
        val list = if (fetched.size > 100 || stored == null) fetched else stored.teams
        if (fetched.size > 100) {
            runCatching {
                IptvCacheStorage.write(FILE) { it.write(SportHttp.json.encodeToString(serializer, Stored(System.currentTimeMillis(), fetched)).encodeToByteArray()) }
            }
        }
        if (list.isNotEmpty()) teams = list
        list
    }

    private suspend fun fetch(): List<Team> = coroutineScope {
        val gate = Semaphore(6)
        LEAGUES.map { (path, sport) ->
            async {
                gate.withPermit {
                    runCatching { parse(SportHttp.getJson("https://site.api.espn.com/apis/site/v2/sports/$path/teams"), sport, women = isWomen(path)) }
                        .getOrDefault(emptyList())
                }
            }
        }.flatMap { it.await() }.distinctBy { it.sport to it.logo }
    }

    private fun isWomen(path: String) = ".w." in path || path.endsWith("wnba") || path.endsWith("nwsl") || path.endsWith("wwc") || path.endsWith("weuro")

    private fun parse(root: JsonObject?, sport: String, women: Boolean): List<Team> = with(SportHttp) {
        val rows = root?.get("sports").arr()?.firstOrNull().obj()?.get("leagues").arr()?.firstOrNull().obj()?.get("teams").arr() ?: return emptyList()
        rows.mapNotNull { row ->
            val team = row.obj()?.get("team").obj() ?: return@mapNotNull null
            val name = team["displayName"].str() ?: return@mapNotNull null
            val logos = team["logos"].arr()?.mapNotNull { it.obj() }.orEmpty()
            // The plain logo for a dark background if there is one, else the first.
            val logo = (logos.firstOrNull { l -> l["rel"].arr()?.any { it.str() == "dark" } == true } ?: logos.firstOrNull())?.get("href").str()
                ?: return@mapNotNull null
            Team(sport, name, team["shortDisplayName"].str() ?: name, team["location"].str().orEmpty(), team["abbreviation"].str().orEmpty(), logo, women)
        }
    }

    /** Rugby league teams are "Rugby" to anything that does not tell the two apart. */
    private fun sportMatches(team: Team, sport: String?): Boolean = when (sport) {
        null -> true
        "Rugby" -> team.sport == "Rugby" || team.sport == IptvSportInfo.SPORT_RUGBY_LEAGUE
        else -> team.sport == sport
    }

    /** The logo of the one team [name] means; null when none, or when it could be teams of different sports. */
    suspend fun logo(name: String, sport: String?, women: Boolean): String? {
        val wanted = teamNameKey(name)
        if (wanted.isBlank()) return null
        val pool = all().filter { sportMatches(it, sport) }
        fun keys(t: Team) = listOf(t.name, t.short, t.location).map(::teamNameKey).filter { it.isNotBlank() }
        val exact = pool.filter { t -> keys(t).any { it == wanted } || (name.length in 2..4 && name.all { it.isUpperCase() } && t.abbr == name) }
        val loose = if (exact.isNotEmpty()) exact else pool.filter { t -> keys(t).any { SportHttp.wordsStartInOrder(it, wanted) } }
        // Women's sides from women's leagues, men's from the rest (a club is in both).
        val sided = loose.filter { it.women == women }.ifEmpty { if (women) loose else emptyList() }
        if (sided.isEmpty()) return null
        if (sport == null && sided.map { it.sport }.distinct().size > 1) return null
        return sided.minByOrNull { it.name.length }!!.logo
    }
}

/** Wikipedia's lead image for a side's article: one search request with the image and a short description. */
internal object IptvWikiPictures {
    private val gate = Mutex()
    private var lastAt = 0L

    private fun sportWords(sport: String?, person: Boolean): String = when {
        person && sport == IptvSportInfo.SPORT_FIGHTING -> "mixed martial artist"
        person -> "boxer"
        sport == "Soccer" -> "football club"
        sport == "Rugby" -> "rugby union"
        sport == IptvSportInfo.SPORT_RUGBY_LEAGUE -> "rugby league"
        sport == "Cricket" -> "cricket team"
        sport == "Basketball" -> "basketball team"
        sport == "Ice Hockey" -> "ice hockey team"
        sport == "American Football" -> "football team"
        sport == "Australian Football" -> "football club"
        sport == "Baseball" -> "baseball team"
        else -> "sports team"
    }

    /** The words the article's short description must have for the sport ("Association football club in Leeds"). */
    private fun describes(description: String, sport: String?, person: Boolean): Boolean {
        val d = description.lowercase()
        return when {
            person -> listOf("boxer", "fighter", "martial", "kickboxer").any { it in d }
            sport == "Soccer" -> "football" in d || "soccer" in d
            sport == "Rugby" || sport == IptvSportInfo.SPORT_RUGBY_LEAGUE -> "rugby" in d
            sport == "Cricket" -> "cricket" in d
            sport == "Basketball" -> "basketball" in d
            sport == "Ice Hockey" -> "hockey" in d
            sport == "American Football" || sport == "Australian Football" -> "football" in d
            sport == "Baseball" -> "baseball" in d
            else -> listOf("team", "club", "football", "rugby", "cricket", "basketball", "hockey", "national").any { it in d }
        }
    }

    suspend fun find(name: String, sport: String?, person: Boolean, women: Boolean): String? = with(SportHttp) {
        val query = listOfNotNull(name, if (women && !person) "women" else null, sportWords(sport, person)).joinToString(" ")
        val root = gate.withLock {
            val wait = lastAt + 300L - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastAt = System.currentTimeMillis()
            getJson(
                "https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&generator=search&gsrlimit=5" +
                    "&gsrsearch=${encode(query)}&prop=pageimages%7Cdescription&piprop=thumbnail&pithumbsize=240&pilicense=any",
                mapOf("User-Agent" to "NuvioDesktop/1.0 (Live TV sports art)"),
            )
        }
        val pages = root?.get("query").obj()?.get("pages").obj()?.values?.mapNotNull { it.obj() }.orEmpty()
            .sortedBy { it["index"].str()?.toIntOrNull() ?: 99 }
        val wanted = teamNameKey(name)
        val surname = teamNameKey(name.split(' ').last())
        val best = pages.firstOrNull { page ->
            val title = teamNameKey(page["title"].str().orEmpty().replace(Regex("""\s*\(.*\)$"""), ""))
            val nameOk = if (person) title.split(' ').lastOrNull() == surname || title == wanted else
                SportHttp.wordsStartInOrder(title, wanted) || SportHttp.wordsStartInOrder(wanted, title)
            nameOk && describes(page["description"].str().orEmpty(), sport, person) && page["thumbnail"].obj()?.get("source").str() != null
        }
        best?.get("thumbnail").obj()?.get("source").str()
    }
}

/** TheSportsDB with the free key: one request every two seconds, a minute's pause after a 429. Last resort. */
internal object IptvSportsDbPictures {
    private const val BASE = "https://www.thesportsdb.com/api/v1/json/123"
    private val gate = Mutex()
    private var lastAt = 0L
    private var pausedUntil = 0L

    private suspend fun get(path: String): JsonObject? = gate.withLock {
        val wait = maxOf(lastAt + 2_100L, pausedUntil) - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastAt = System.currentTimeMillis()
        try {
            SportHttp.getJson("$BASE/$path")
        } catch (busy: SportHttp.Unreachable) {
            pausedUntil = System.currentTimeMillis() + 61_000L
            throw busy
        }
    }

    private fun small(url: String) = if (url.endsWith("/small") || url.endsWith("/tiny")) url else "$url/small"

    suspend fun find(name: String, sport: String?, person: Boolean, women: Boolean): String? = with(SportHttp) {
        val tsdbSport = if (sport == IptvSportInfo.SPORT_RUGBY_LEAGUE) "Rugby" else sport
        if (person) {
            val players = get("searchplayers.php?p=${encode(name.trim().replace(' ', '_'))}")?.get("player").arr()?.mapNotNull { it.obj() }.orEmpty()
            val best = players.firstOrNull { p ->
                val s = p["strSport"].str().orEmpty()
                (tsdbSport == null || s.equals(tsdbSport, true) || s in setOf("Boxing", "Fighting")) &&
                    teamNameKey(p["strPlayer"].str().orEmpty()).endsWith(teamNameKey(name))
            }
            return (best?.get("strCutout").str() ?: best?.get("strThumb").str())?.let(::small)
        }
        val query = if (women) "$name Women" else name
        val teams = get("searchteams.php?t=${encode(query)}")?.get("teams").arr()?.mapNotNull { it.obj() }.orEmpty()
        val wanted = teamNameKey(query)
        val best = teams.firstOrNull { t ->
            (tsdbSport == null || t["strSport"].str().equals(tsdbSport, true)) &&
                listOfNotNull(t["strTeam"].str(), t["strTeamShort"].str()).any { teamNameKey(it) == wanted }
        }
        (best?.get("strBadge").str() ?: best?.get("strLogo").str())?.let(::small)
    }
}
