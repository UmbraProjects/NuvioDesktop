package com.nuvio.app.features.settings

internal expect object ApiKeysOnboardingStorage {
    fun isPermanentlyDismissed(): Boolean
    fun setPermanentlyDismissed(dismissed: Boolean)
}
