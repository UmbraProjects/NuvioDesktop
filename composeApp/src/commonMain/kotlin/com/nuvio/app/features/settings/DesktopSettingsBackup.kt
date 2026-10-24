package com.nuvio.app.features.settings

internal sealed interface DesktopSettingsBackupResult {
    data class Saved(val path: String) : DesktopSettingsBackupResult
    data class Failed(val message: String) : DesktopSettingsBackupResult
    data object Cancelled : DesktopSettingsBackupResult
}

internal expect object DesktopSettingsBackup {
    fun create(includeCredentials: Boolean): DesktopSettingsBackupResult
}
