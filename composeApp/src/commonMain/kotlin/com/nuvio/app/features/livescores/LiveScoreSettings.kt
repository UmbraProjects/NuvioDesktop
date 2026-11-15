package com.nuvio.app.features.livescores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* fork:live-scores - the overlay's settings, per profile (store `nuvio_live_scores`). */

internal expect object LiveScoreStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** How a pop-up looks. All three are offered (user, Nov 11). */
enum class ScoreLook(val label: String, val description: String) {
    Corner("Corner card", "A small glass card at the bottom right for about eight seconds."),
    Ticker("Top ticker", "A thin bar across the top that slides in and out."),
    BigFlash("Big flash for favourites", "A large moment for your teams, the corner card for everything else."),
}

/** When the chime plays: favourites only by default (user, Nov 11). */
enum class ScoreChime(val label: String) { Favourites("Favourites only"), Always("Every pop-up"), Never("Never") }

data class LiveScoreSettings(
    /** Off by default: new features stay behind a switch until the user is happy with them. */
    val enabled: Boolean = false,
    val favourites: List<LiveTeam> = emptyList(),
    /** Competition keys from LiveScoreCatalog. Nothing until the user picks (user, Nov 11). */
    val followed: Set<String> = emptySet(),
    val look: ScoreLook = ScoreLook.Corner,
    val groups: Set<ScoreEventGroup> = ScoreEventGroup.entries.toSet(),
    val chime: ScoreChime = ScoreChime.Favourites,
    /** Favourites' events as notifications on the phone remote (user, Nov 11). */
    val phoneNotifications: Boolean = true,
    /** The keyed backup for football when ESPN fails: an API-Football key (api-sports.io), optional. */
    val apiFootballKey: String = "",
)

object LiveScoreSettingsRepository {
    private val _state = MutableStateFlow(LiveScoreSettings())
    val state: StateFlow<LiveScoreSettings> = _state.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _state.value = runCatching { read() }.getOrDefault(LiveScoreSettings())
    }

    /** Re-read after a profile switch (the store is profile-scoped). */
    fun reload() {
        loaded = false
        ensureLoaded()
    }

    private fun read(): LiveScoreSettings {
        val defaults = LiveScoreSettings()
        return LiveScoreSettings(
            enabled = LiveScoreStorage.getString("enabled") == "true",
            favourites = decodeTeams(LiveScoreStorage.getString("favourites")),
            followed = LiveScoreStorage.getString("followed")?.split(',')?.filter { LiveScoreCatalog.byKey(it) != null }?.toSet()
                ?: defaults.followed,
            look = LiveScoreStorage.getString("look")?.let { name -> ScoreLook.entries.firstOrNull { it.name == name } } ?: defaults.look,
            groups = LiveScoreStorage.getString("groups")
                ?.split(',')?.mapNotNull { name -> ScoreEventGroup.entries.firstOrNull { it.name == name } }?.toSet()
                ?: defaults.groups,
            chime = LiveScoreStorage.getString("chime")?.let { name -> ScoreChime.entries.firstOrNull { it.name == name } } ?: defaults.chime,
            phoneNotifications = LiveScoreStorage.getString("phone") != "false",
            apiFootballKey = LiveScoreStorage.getString("apikey.apifootball").orEmpty(),
        )
    }

    fun setEnabled(on: Boolean) = save("enabled", on.toString()) { it.copy(enabled = on) }

    fun addFavourite(team: LiveTeam) {
        val next = (state.value.favourites.filterNot { it.sport == team.sport && it.id == team.id } + team)
        save("favourites", encodeTeams(next)) { it.copy(favourites = next) }
    }

    fun removeFavourite(team: LiveTeam) {
        val next = state.value.favourites.filterNot { it.sport == team.sport && it.id == team.id }
        save("favourites", encodeTeams(next)) { it.copy(favourites = next) }
    }

    fun setFollowed(key: String, on: Boolean) {
        val next = if (on) state.value.followed + key else state.value.followed - key
        save("followed", next.joinToString(",")) { it.copy(followed = next) }
    }

    fun setLook(look: ScoreLook) = save("look", look.name) { it.copy(look = look) }

    fun setGroup(group: ScoreEventGroup, on: Boolean) {
        val next = if (on) state.value.groups + group else state.value.groups - group
        save("groups", next.joinToString(",") { it.name }) { it.copy(groups = next) }
    }

    fun setChime(chime: ScoreChime) = save("chime", chime.name) { it.copy(chime = chime) }

    fun setPhoneNotifications(on: Boolean) = save("phone", on.toString()) { it.copy(phoneNotifications = on) }

    /** Stored as `apikey.*` so the settings backup's credential filter leaves it out. */
    fun setApiFootballKey(key: String) = save("apikey.apifootball", key.trim()) { it.copy(apiFootballKey = key.trim()) }

    private fun save(key: String, value: String, change: (LiveScoreSettings) -> LiveScoreSettings) {
        ensureLoaded()
        _state.value = change(_state.value)
        runCatching { LiveScoreStorage.putString(key, value) }
    }
}

/** `sport|id|name|short` per team, rows joined by newlines; text is percent-escaped so names may hold anything. */
internal fun encodeTeams(teams: List<LiveTeam>): String = teams.joinToString("\n") { team ->
    listOf(team.sport.name, team.id, team.name, team.shortName).joinToString("|") { escapeField(it) }
}

internal fun decodeTeams(text: String?): List<LiveTeam> = text.orEmpty().lines().mapNotNull { line ->
    val parts = line.split('|').map(::unescapeField)
    if (parts.size < 3) return@mapNotNull null
    val sport = LiveSport.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
    LiveTeam(sport, parts[1], parts[2], parts.getOrNull(3)?.ifBlank { null } ?: parts[2])
}

private fun escapeField(text: String): String = text.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")
private fun unescapeField(text: String): String = text.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%")
