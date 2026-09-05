package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.isLiquidGlassNativeTabBarSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Caches the settings search index so it is built once, off the UI thread.
 *
 * [buildSettingsSearchEntries] resolves ~550 string resources. On desktop each one is a separate
 * blocking byte-range read out of the packaged jar, so building the index inline during composition
 * added roughly a quarter of a second of dead time to the first frame of the settings screen — the
 * whole index only ever gets read once the user actually types a query. Building it here keeps that
 * work off the composition thread, and [warm] runs it during the deferred startup warm so the
 * results are already in hand by the time settings is opened.
 *
 * Compose's own resource cache is global and process-wide, so a rebuild under a different key (a
 * different app language, or feature flags that differ from the ones [warm] guessed) costs only the
 * list construction, not the reads.
 */
internal object SettingsSearchIndex {

    private data class Key(
        val pluginsEnabled: Boolean,
        val downloadsEnabled: Boolean,
        val notificationsEnabled: Boolean,
        val liquidGlassNativeTabBarSupported: Boolean,
        val switchProfileAvailable: Boolean,
        val checkForUpdatesAvailable: Boolean,
        val languageCode: String,
    )

    private val mutex = Mutex()
    private var builtKey: Key? = null

    private val _entries = MutableStateFlow<List<SettingsSearchEntry>>(emptyList())

    /** Empty until the index has been built at least once; results simply appear when it lands. */
    val entries: StateFlow<List<SettingsSearchEntry>> = _entries.asStateFlow()

    suspend fun ensureBuilt(
        pluginsEnabled: Boolean,
        downloadsEnabled: Boolean,
        notificationsEnabled: Boolean,
        liquidGlassNativeTabBarSupported: Boolean,
        switchProfileAvailable: Boolean,
        checkForUpdatesAvailable: Boolean,
        languageCode: String,
    ) {
        val key = Key(
            pluginsEnabled = pluginsEnabled,
            downloadsEnabled = downloadsEnabled,
            notificationsEnabled = notificationsEnabled,
            liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
            switchProfileAvailable = switchProfileAvailable,
            checkForUpdatesAvailable = checkForUpdatesAvailable,
            languageCode = languageCode,
        )
        if (builtKey == key) return
        mutex.withLock {
            if (builtKey == key) return
            val built = withContext(Dispatchers.Default) {
                buildSettingsSearchEntries(
                    pluginsEnabled = key.pluginsEnabled,
                    downloadsEnabled = key.downloadsEnabled,
                    notificationsEnabled = key.notificationsEnabled,
                    liquidGlassNativeTabBarSupported = key.liquidGlassNativeTabBarSupported,
                    switchProfileAvailable = key.switchProfileAvailable,
                    checkForUpdatesAvailable = key.checkForUpdatesAvailable,
                )
            }
            builtKey = key
            _entries.value = built
        }
    }

    /**
     * Startup warm. The availability flags are the ones the settings screen almost always passes;
     * even when it ends up asking for a different combination, the expensive resource reads are
     * already cached by then.
     */
    suspend fun warm(languageCode: String) {
        // The settings screen may already have built a more accurate index; never overwrite it.
        if (builtKey != null) return
        ensureBuilt(
            pluginsEnabled = AppFeaturePolicy.pluginsEnabled,
            downloadsEnabled = AppFeaturePolicy.downloadsEnabled,
            notificationsEnabled = AppFeaturePolicy.notificationsEnabled,
            liquidGlassNativeTabBarSupported = isLiquidGlassNativeTabBarSupported(),
            switchProfileAvailable = true,
            checkForUpdatesAvailable = true,
            languageCode = languageCode,
        )
    }
}

/**
 * Reads the cached settings search index, kicking off a build if this combination has not been
 * built yet. Returns an empty list until the build lands — search results are the only consumer, so
 * there is nothing to draw before the user types anyway. Briefly, right after a language or feature
 * flag change, this can return the previous build; the rows only differ by a handful of entries and
 * the correct list replaces it as soon as the rebuild finishes.
 */
@Composable
internal fun rememberSettingsSearchEntries(
    pluginsEnabled: Boolean,
    downloadsEnabled: Boolean,
    notificationsEnabled: Boolean,
    liquidGlassNativeTabBarSupported: Boolean,
    switchProfileAvailable: Boolean,
    checkForUpdatesAvailable: Boolean,
    languageCode: String,
): List<SettingsSearchEntry> {
    LaunchedEffect(
        pluginsEnabled,
        downloadsEnabled,
        notificationsEnabled,
        liquidGlassNativeTabBarSupported,
        switchProfileAvailable,
        checkForUpdatesAvailable,
        languageCode,
    ) {
        SettingsSearchIndex.ensureBuilt(
            pluginsEnabled = pluginsEnabled,
            downloadsEnabled = downloadsEnabled,
            notificationsEnabled = notificationsEnabled,
            liquidGlassNativeTabBarSupported = liquidGlassNativeTabBarSupported,
            switchProfileAvailable = switchProfileAvailable,
            checkForUpdatesAvailable = checkForUpdatesAvailable,
            languageCode = languageCode,
        )
    }
    val entries by SettingsSearchIndex.entries.collectAsStateWithLifecycle()
    return entries
}
