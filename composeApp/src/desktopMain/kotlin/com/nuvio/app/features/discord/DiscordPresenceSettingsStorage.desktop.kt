package com.nuvio.app.features.discord

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DiscordPresenceSettingsStorage {
    // Legacy boolean toggle; retained only to migrate existing installs to a mode.
    private const val legacyEnabledKey = "enabled"
    private const val modeKey = "mode"
    private val store = DesktopStorage.store("nuvio_discord_presence")

    actual fun loadMode(): DiscordPresenceMode {
        store.getString(modeKey)?.let { stored ->
            runCatching { DiscordPresenceMode.valueOf(stored) }.getOrNull()?.let { return it }
        }
        // Migration: the previous boolean toggle showed the full integration when enabled,
        // so a previously-enabled install maps to Full; anything else stays Disabled.
        return if (store.getBoolean(legacyEnabledKey) == true) {
            DiscordPresenceMode.Full
        } else {
            DiscordPresenceMode.Disabled
        }
    }

    actual fun saveMode(mode: DiscordPresenceMode) {
        store.putString(modeKey, mode.name)
    }
}
