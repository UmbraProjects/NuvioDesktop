package com.nuvio.app.features.setup

/** Current wizard revision. Bump when a later version adds a step existing users should be shown. */
internal const val FIRST_RUN_WIZARD_VERSION = 1

internal expect object FirstRunWizardStorage {
    /**
     * Whether this installation is entitled to the auto-opening wizard.
     *
     * Answered from the platform's fresh-install signal the first time it is asked and persisted
     * immediately: that signal is only accurate inside the process that creates the app data
     * directory, so a wizard interrupted by a crash would otherwise never resume.
     */
    fun isEligible(): Boolean

    /** 0 when the wizard has never been finished or skipped. */
    fun completedVersion(): Int

    fun setCompletedVersion(version: Int)

    /** Whether the one-time new-installation defaults pass has already run. */
    fun defaultsApplied(): Boolean

    fun markDefaultsApplied()
}
