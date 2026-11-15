package com.nuvio.app.features.iptv

import com.nuvio.app.features.livescores.LiveTeam
import com.nuvio.app.features.livescores.teamNameKey
import kotlinx.serialization.Serializable

/**
 * fork:live-tv-learn - the teams in what has been watched: "Man Utd v Chelsea" counts a game for each side, whatever
 * the programme is called that night. Once a team has been watched often enough Following asks once whether to follow
 * it (and add it to Live Scores); a "no" is kept so it does not ask again.
 *
 * [seenAt] and [finishedAt] are the times of the last few games seen and watched to the end, newest last.
 */
@Serializable
data class IptvTeamStat(
    val name: String = "",
    val seenAt: List<Long> = emptyList(),
    val finishedAt: List<Long> = emptyList(),
) {
    val last: Long get() = maxOf(seenAt.lastOrNull() ?: 0L, finishedAt.lastOrNull() ?: 0L)
}

/** A team Following offers to follow: [team] when it was found in the Live Scores list (and can be added there). */
data class IptvLearnedTeam(
    val name: String,
    val games: Int,
    val finished: Int,
    /** What a "no" is kept under. */
    val key: String,
    val team: LiveTeam?,
)

object IptvTeamRules {
    /** Games seen in [WINDOW_DAYS] days, with at least [MIN_FINISHED] of them watched to the end, before Following asks. */
    const val MIN_GAMES = 3
    const val MIN_FINISHED = 2
    const val WINDOW_DAYS = 42
    private const val KEPT = 12
    private const val MAX_TEAMS = 120
    private const val DAY_MS = 24 * 3_600_000L

    private val VERSUS = Regex("""\s+(?:v|vs|vs\.|versus)\s+""", RegexOption.IGNORE_CASE)
    private val LIVE = Regex("""^(?:live|live:|live -)\s+""", RegexOption.IGNORE_CASE)
    private val RIGHT_END = Regex("""\s*(?:[(:,|\[]|\s-\s|\s–\s).*$""")

    /** The two sides of "Premier League: Man Utd v Chelsea (Live)", or nothing when the title is not a fixture. */
    fun teamsIn(title: String, kind: String?): List<String> {
        if (kind == IptvProgrammeKind.FILM) return emptyList()
        val split = VERSUS.find(title) ?: return emptyList()
        val left = title.substring(0, split.range.first)
            .substringAfterLast(':').substringAfterLast(" - ").substringAfterLast(" – ")
            .trim().replace(LIVE, "").trim()
        val right = title.substring(split.range.last + 1).replace(RIGHT_END, "").trim()
            .replace(Regex("""\s+live$""", RegexOption.IGNORE_CASE), "").trim()
        return listOf(left, right).filter { side ->
            side.length in 2..40 && side.any { it.isLetter() } && side.split(' ').count { it.isNotBlank() } <= 5
        }
    }

    /** [stats] after [event] for [entry] at [now]: each side of a fixture gets the game. */
    fun note(stats: Map<String, IptvTeamStat>, entry: IptvEpgEntry, event: IptvWatchEvent, now: Long): Map<String, IptvTeamStat> {
        if (event == IptvWatchEvent.None) return stats
        val sides = teamsIn(entry.title, entry.kind)
        if (sides.isEmpty()) return stats
        val out = LinkedHashMap(stats)
        sides.forEach { side ->
            val key = teamNameKey(side)
            if (key.isBlank()) return@forEach
            val old = out[key] ?: IptvTeamStat(name = side)
            out[key] = when (event) {
                IptvWatchEvent.Seen -> old.copy(name = side, seenAt = (old.seenAt + now).takeLast(KEPT))
                IptvWatchEvent.Finished -> old.copy(finishedAt = (old.finishedAt + now).takeLast(KEPT))
                IptvWatchEvent.None -> old
            }
        }
        if (out.size > MAX_TEAMS) out.entries.sortedBy { it.value.last }.take(out.size - MAX_TEAMS).forEach { out.remove(it.key) }
        return out
    }

    /** "man utd" against "Manchester United": every word of [query] starts a word of [name], in order. */
    private fun wordsStartInOrder(name: String, query: String): Boolean {
        val words = name.split(' ')
        var at = 0
        for (part in query.split(' ').filter { it.isNotBlank() }) {
            while (at < words.size && !words[at].startsWith(part)) at++
            if (at == words.size) return false
            at++
        }
        return true
    }

    /** The Live Scores team a programme's spelling means, when there is one clear match. */
    fun resolve(name: String, teams: List<LiveTeam>): LiveTeam? {
        val wanted = teamNameKey(name)
        if (wanted.isBlank()) return null
        val hits = teams.filter { t ->
            val keys = listOf(teamNameKey(t.name), teamNameKey(t.shortName))
            keys.any { it == wanted } || keys.any { wordsStartInOrder(it, wanted) }
        }
        return hits.firstOrNull { t -> teamNameKey(t.name) == wanted || teamNameKey(t.shortName) == wanted }
            ?: hits.minByOrNull { it.name.length }
    }

    /**
     * The teams to offer on Following: watched [MIN_GAMES] times in [WINDOW_DAYS] days (every spelling of a team
     * counted together), not followed already and not turned down. Most watched first.
     */
    fun learned(
        stats: Map<String, IptvTeamStat>,
        teams: List<LiveTeam>,
        follows: List<String>,
        declined: Set<String>,
        now: Long,
        limit: Int = 2,
    ): List<IptvLearnedTeam> {
        val since = now - WINDOW_DAYS * DAY_MS
        class Tally(val team: LiveTeam?, val names: MutableList<IptvTeamStat> = ArrayList())
        val groups = LinkedHashMap<String, Tally>()
        stats.values.forEach { stat ->
            val team = resolve(stat.name, teams)
            val group = team?.let { "${it.sport}:${it.id}" } ?: teamNameKey(stat.name)
            groups.getOrPut(group) { Tally(team) }.names += stat
        }
        return groups.values.mapNotNull { tally ->
            val games = tally.names.sumOf { s -> s.seenAt.count { it >= since } }
            val finished = tally.names.sumOf { s -> s.finishedAt.count { it >= since } }
            if (games < MIN_GAMES || finished < MIN_FINISHED) return@mapNotNull null
            val name = tally.team?.name ?: tally.names.maxByOrNull { it.seenAt.size }!!.name
            val key = teamNameKey(name)
            val spellings = listOfNotNull(name, tally.team?.shortName) + tally.names.map { it.name }
            if (key in declined || spellings.any { IptvDecide.isFollowed(follows, it) }) return@mapNotNull null
            IptvLearnedTeam(name, games, finished, key, tally.team) to tally.names.maxOf { it.last }
        }.sortedWith(compareByDescending<Pair<IptvLearnedTeam, Long>> { it.first.games }.thenByDescending { it.second })
            .map { it.first }
            .take(limit)
    }
}
