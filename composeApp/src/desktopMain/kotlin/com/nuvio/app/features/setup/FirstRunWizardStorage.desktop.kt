package com.nuvio.app.features.setup

import com.nuvio.app.core.storage.DesktopStorage

/**
 * Device-level, never profile-scoped and never synced: whether *this machine* has been through
 * setup says nothing about the account signed in on it.
 *
 * The store name is listed in `PlatformLocalAccountDataCleaner.preservedStoreNames` — without that,
 * signing out deletes the file and the wizard re-runs on the next launch.
 */
private const val firstRunWizardPreferencesName = "nuvio_first_run_wizard"
private const val eligibleKey = "eligible"
private const val completedVersionKey = "completed_version"
private const val defaultsAppliedKey = "defaults_applied"

internal actual object FirstRunWizardStorage {
    private val store = DesktopStorage.store(firstRunWizardPreferencesName)

    actual fun isEligible(): Boolean {
        store.getBoolean(eligibleKey)?.let { return it }
        // DesktopStorage.isFreshInstall is only true in the process that created the app data
        // directory, so the answer has to be captured now rather than re-derived next launch.
        val eligible = DesktopStorage.isFreshInstall
        store.putBoolean(eligibleKey, eligible)
        return eligible
    }

    actual fun completedVersion(): Int = store.getInt(completedVersionKey) ?: 0

    actual fun setCompletedVersion(version: Int) {
        store.putInt(completedVersionKey, version)
    }

    actual fun defaultsApplied(): Boolean = store.getBoolean(defaultsAppliedKey) ?: false

    actual fun markDefaultsApplied() {
        // Written before the pass runs, not after: a crash midway must not replay writes over
        // whatever the user has already changed by the next launch.
        store.putBoolean(defaultsAppliedKey, true)
    }
}
