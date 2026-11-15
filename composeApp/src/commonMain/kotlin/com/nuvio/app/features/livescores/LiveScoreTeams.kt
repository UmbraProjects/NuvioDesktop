package com.nuvio.app.features.livescores

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * fork:live-scores - the teams Settings offers as favourites: ESPN's team lists for the UK leagues and the big
 * internationals (LiveScoreCatalog.teamSources), fetched when Settings first asks and kept for the session.
 */
object LiveScoreTeams {
    private val lock = Mutex()
    private var cached: List<LiveTeam>? = null

    suspend fun all(): List<LiveTeam> = lock.withLock {
        cached?.let { return it }
        val loaded = coroutineScope {
            LiveScoreCatalog.teamSources.map { competition ->
                async {
                    val path = competition.espnPath ?: return@async emptyList()
                    runCatching {
                        val response = httpRequestRaw(
                            method = "GET",
                            url = "https://site.api.espn.com/apis/site/v2/sports/$path/teams",
                            headers = mapOf("Accept" to "application/json"),
                            body = "",
                            callTimeoutMs = 12_000L,
                        )
                        if (response.status in 200..299) parseEspnTeams(response.body, competition.sport) else emptyList()
                    }.getOrDefault(emptyList())
                }
            }.flatMap { it.await() }
        }
        val unique = loaded.distinctBy { it.sport to it.id }.sortedBy { it.name }
        if (unique.isNotEmpty()) cached = unique
        unique
    }
}

/** "man united" in "manchester united": every typed word starts one of the name's words, in order. */
private fun wordsStartInOrder(name: String, query: String): Boolean {
    val words = name.split(' ')
    var at = 0
    for (part in query.split(' ')) {
        while (at < words.size && !words[at].startsWith(part)) at++
        if (at == words.size) return false
        at++
    }
    return true
}

/** Teams whose name or short name matches [query] ("man utd" finds Manchester United). */
fun searchTeams(teams: List<LiveTeam>, query: String, limit: Int = 8): List<LiveTeam> {
    val wanted = teamNameKey(query)
    if (wanted.isBlank()) return emptyList()
    fun score(team: LiveTeam): Int? {
        val keys = listOf(teamNameKey(team.name), teamNameKey(team.shortName))
        return when {
            keys.any { it == wanted } -> 0
            keys.any { it.startsWith(wanted) } -> 1
            keys.any { key -> key.split(' ').any { it.startsWith(wanted) } } -> 2
            keys.any { key -> wordsStartInOrder(key, wanted) } -> 2
            keys.any { it.contains(wanted) } -> 3
            else -> null
        }
    }
    return teams.mapNotNull { team -> score(team)?.let { it to team } }
        .sortedWith(compareBy({ it.first }, { it.second.name.length }))
        .map { it.second }
        .take(limit)
}
