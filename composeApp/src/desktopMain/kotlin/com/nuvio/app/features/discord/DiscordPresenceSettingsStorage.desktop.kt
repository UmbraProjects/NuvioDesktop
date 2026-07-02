package com.nuvio.app.features.discord

import com.nuvio.app.core.storage.DesktopStorage

internal actual object DiscordPresenceSettingsStorage {
    private const val enabledKey = "enabled"
    private val store = DesktopStorage.store("nuvio_discord_presence")

    actual fun loadEnabled(): Boolean? = store.getBoolean(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = store.putBoolean(enabledKey, enabled)
}
