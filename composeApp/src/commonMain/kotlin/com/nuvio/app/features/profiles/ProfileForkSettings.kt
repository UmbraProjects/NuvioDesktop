package com.nuvio.app.features.profiles

import com.nuvio.app.features.details.filler.AnimeFillerSettings
import com.nuvio.app.features.games.GameLibrarySettingsRepository
import com.nuvio.app.features.input.gamepadSettingsProfileChanged
import com.nuvio.app.features.qualicache.QualiCacheSettingsRepository

// fork:profile-reload - the settings that keep a copy in memory and are stored per profile, but were missing from the list in
// ProfileRepository.selectProfile, so after a profile switch they kept showing the previous profile's values until the next
// restart. They live here, not in that list, so merging upstream (which keeps adding lines to it) does not conflict.

/** Reloads these settings for the profile just switched to. Called once from ProfileRepository.selectProfile. */
internal fun reloadForkProfileSettings() {
    GameLibrarySettingsRepository.onProfileChanged()
    QualiCacheSettingsRepository.onProfileChanged()
    AnimeFillerSettings.onProfileChanged()
    gamepadSettingsProfileChanged()
}
