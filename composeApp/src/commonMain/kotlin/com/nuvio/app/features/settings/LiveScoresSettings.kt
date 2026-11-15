package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.livescores.LiveScoreCatalog
import com.nuvio.app.features.livescores.LiveScoreEngine
import com.nuvio.app.features.livescores.LiveScoreSettingsRepository
import com.nuvio.app.features.livescores.LiveScoreTeams
import com.nuvio.app.features.livescores.LiveSport
import com.nuvio.app.features.livescores.LiveTeam
import com.nuvio.app.features.livescores.ScoreChime
import com.nuvio.app.features.livescores.ScoreEventGroup
import com.nuvio.app.features.livescores.ScoreLook
import com.nuvio.app.features.livescores.searchTeams

/**
 * fork:live-scores - Settings > Live TV > Live scores: the switch (off by default), favourite teams, followed
 * competitions (none until picked), which events pop up, the look, the chime, the phone, and the optional backup key.
 */
internal fun LazyListScope.liveScoresSettingsContent(isTablet: Boolean) {
    item(key = "live-scores") { LiveScoresSection(isTablet) }
}

@Composable
private fun LiveScoresSection(isTablet: Boolean) {
    LaunchedEffect(Unit) { LiveScoreSettingsRepository.ensureLoaded() }
    val settings by LiveScoreSettingsRepository.state.collectAsStateWithLifecycle()
    val status by LiveScoreEngine.status.collectAsStateWithLifecycle()

    SettingsSection(title = "Live scores", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = "Live score pop-ups",
                description = "A card pops up when a followed match has a goal, a try, a red card, a missed penalty " +
                    "or VAR, kicks off, reaches half-time or finishes: over menus, Live TV, music, films and episodes. " +
                    "Scores come from ESPN; boxing from TheSportsDB.",
                checked = settings.enabled,
                isTablet = isTablet,
                onCheckedChange = LiveScoreSettingsRepository::setEnabled,
            )
            if (settings.enabled) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsActionRow(title = "Status", description = status, isTablet = isTablet) {
                    SettingsGlassButton(text = "Show a sample", onClick = { LiveScoreEngine.showSample(favourite = true) })
                }
            }
        }

        if (settings.enabled) {
            FavouritesGroup(isTablet, settings.favourites)
            CompetitionsGroup(isTablet, settings.followed)
            SettingsGroup(isTablet = isTablet) {
                SettingsMultiSelectRow(
                    title = "Pop up for",
                    description = "Which match events show a card.",
                    options = ScoreEventGroup.entries.map { SettingsChoiceOption(it, it.label) },
                    selectedValues = settings.groups,
                    emptyLabel = "Nothing",
                    isTablet = isTablet,
                    onToggle = { group -> LiveScoreSettingsRepository.setGroup(group, group !in settings.groups) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = "Look",
                    description = settings.look.description,
                    options = ScoreLook.entries.map { SettingsChoiceOption(it, it.label) },
                    selectedValue = settings.look,
                    isTablet = isTablet,
                    onSelected = LiveScoreSettingsRepository::setLook,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChoiceRow(
                    title = "Chime",
                    description = "A short sound with the card.",
                    options = ScoreChime.entries.map { SettingsChoiceOption(it, it.label) },
                    selectedValue = settings.chime,
                    isTablet = isTablet,
                    onSelected = LiveScoreSettingsRepository::setChime,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Notify the phone remote",
                    description = "Your favourites' events also arrive as notifications on Nuvio Remote while it is " +
                        "connected to this PC.",
                    checked = settings.phoneNotifications,
                    isTablet = isTablet,
                    onCheckedChange = LiveScoreSettingsRepository::setPhoneNotifications,
                )
            }
            BackupKeyGroup(isTablet, settings.apiFootballKey)
        }
    }
}

@Composable
private fun FavouritesGroup(isTablet: Boolean, favourites: List<LiveTeam>) {
    var query by remember { mutableStateOf("") }
    var teams by remember { mutableStateOf<List<LiveTeam>?>(null) }
    LaunchedEffect(query.isNotBlank()) {
        if (query.isNotBlank() && teams == null) teams = LiveScoreTeams.all()
    }
    val found = remember(query, teams, favourites) {
        searchTeams(teams.orEmpty(), query).filterNot { team -> favourites.any { it.sport == team.sport && it.id == team.id } }
    }
    SettingsGroup(isTablet = isTablet) {
        favourites.forEachIndexed { index, team ->
            if (index > 0) SettingsGroupDivider(isTablet = isTablet)
            SettingsActionRow(title = team.name, description = "Favourite · ${team.sport.label}", isTablet = isTablet) {
                SettingsGlassButton(text = "Remove", onClick = { LiveScoreSettingsRepository.removeFavourite(team) })
            }
        }
        if (favourites.isNotEmpty()) SettingsGroupDivider(isTablet = isTablet)
        SettingsTextRow(
            title = "Add a favourite team",
            description = when {
                query.isBlank() -> "Type a name, for example Man Utd. Favourites' matches pop up in any competition, " +
                    "get the big flash, and the chime by default."
                teams == null -> "Looking up teams…"
                found.isEmpty() -> "No team found for \"$query\"."
                else -> null
            },
            value = query,
            placeholder = "Man Utd",
            isTablet = isTablet,
            onValueChange = { query = it },
        )
        found.forEach { team ->
            SettingsGroupDivider(isTablet = isTablet)
            SettingsActionRow(title = team.name, description = team.sport.label, isTablet = isTablet) {
                SettingsGlassButton(
                    text = "Add",
                    accent = true,
                    onClick = {
                        LiveScoreSettingsRepository.addFavourite(team)
                        query = ""
                    },
                )
            }
        }
    }
}

@Composable
private fun CompetitionsGroup(isTablet: Boolean, followed: Set<String>) {
    val groups = listOf(
        "Football" to listOf(LiveSport.Football),
        "Rugby" to listOf(LiveSport.Rugby, LiveSport.RugbyLeague),
        "Fight nights" to listOf(LiveSport.Mma, LiveSport.Boxing),
    )
    SettingsGroup(isTablet = isTablet) {
        groups.forEachIndexed { index, (label, sports) ->
            if (index > 0) SettingsGroupDivider(isTablet = isTablet)
            val options = LiveScoreCatalog.competitions.filter { it.sport in sports }
            SettingsMultiSelectRow(
                title = "Follow: $label",
                description = when (label) {
                    "Fight nights" -> "A card when the card starts and with the main event's result."
                    else -> "Every match in these competitions pops up, not only your favourites'."
                },
                options = options.map { SettingsChoiceOption(it.key, it.name) },
                selectedValues = followed.filterTo(mutableSetOf()) { key -> options.any { it.key == key } },
                emptyLabel = "None",
                isTablet = isTablet,
                onToggle = { key -> LiveScoreSettingsRepository.setFollowed(key, key !in followed) },
            )
        }
    }
}

@Composable
private fun BackupKeyGroup(isTablet: Boolean, saved: String) {
    var key by remember(saved) { mutableStateOf(saved) }
    SettingsGroup(isTablet = isTablet) {
        SettingsTextRow(
            title = "Backup: API-Football key (optional)",
            description = "Used for football only when ESPN stops answering. A free key from api-football.com " +
                "allows 100 calls a day; Nuvio uses at most 90, once a minute while a followed match is live.",
            value = key,
            secret = true,
            isTablet = isTablet,
            onValueChange = { key = it },
        )
        SettingsGroupDivider(isTablet = isTablet)
        SettingsActionRow(title = "Save key", description = if (key.trim() == saved) "Saved." else null, isTablet = isTablet) {
            SettingsGlassButton(
                text = "Save",
                enabled = key.trim() != saved,
                accent = true,
                onClick = { LiveScoreSettingsRepository.setApiFootballKey(key) },
            )
        }
    }
}
