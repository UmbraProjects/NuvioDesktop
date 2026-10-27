package com.nuvio.app.features.settings

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Builds the real search index with every feature on. The index checks that every settings page is
 * registered and throws if one is not; Settings builds it on open, so a missing registration took
 * the whole window down the moment Settings was entered (it happened when Downloads merged into
 * the auto-downloads page).
 */
class SettingsSearchIndexBuildTest {
    @Test
    fun `search index builds with every feature enabled`() {
        val entries = runBlocking {
            buildSettingsSearchEntries(
                pluginsEnabled = true,
                downloadsEnabled = true,
                notificationsEnabled = true,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = true,
                checkForUpdatesAvailable = true,
            )
        }
        assertTrue(entries.isNotEmpty())
    }

    @Test
    fun `search index builds with optional features off`() {
        val entries = runBlocking {
            buildSettingsSearchEntries(
                pluginsEnabled = false,
                downloadsEnabled = false,
                notificationsEnabled = false,
                liquidGlassNativeTabBarSupported = false,
                switchProfileAvailable = false,
                checkForUpdatesAvailable = false,
            )
        }
        assertTrue(entries.isNotEmpty())
    }
}
