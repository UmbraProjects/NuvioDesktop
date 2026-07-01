package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage

private const val apiKeysOnboardingPreferencesName = "nuvio_api_keys_onboarding"
private const val dismissedKey = "dismissed_permanently"

internal actual object ApiKeysOnboardingStorage {
    private val store = DesktopStorage.store(apiKeysOnboardingPreferencesName)

    actual fun isPermanentlyDismissed(): Boolean = store.getBoolean(dismissedKey) ?: false

    actual fun setPermanentlyDismissed(dismissed: Boolean) {
        store.putBoolean(dismissedKey, dismissed)
    }
}
