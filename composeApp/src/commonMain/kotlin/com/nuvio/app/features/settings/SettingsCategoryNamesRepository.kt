package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSettingsCategoryNamesPayload(
    val names: Map<String, String> = emptyMap(),
)

/** Longer than this and the name stops fitting the sidebar it is meant to label. */
internal const val SettingsCategoryNameMaxLength = 32

/**
 * User-chosen names for the desktop settings categories, keyed by [SettingsPage] enum name and
 * persisted per profile.
 *
 * A rename is presentation only, like hiding: the page, its settings and its search entries are
 * untouched, and clearing the name puts the shipped label back. Entries for unknown pages are kept
 * rather than dropped so a rename made on a newer build survives a roll back and forward, matching
 * [SettingsHiddenCategoriesRepository].
 */
internal object SettingsCategoryNamesRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val payload = SettingsCategoryNamesStorage.loadPayload()
        val parsed = payload?.let {
            runCatching { json.decodeFromString<StoredSettingsCategoryNamesPayload>(it) }.getOrNull()
        }
        _names.value = parsed?.names.orEmpty()
            .mapValues { (_, name) -> name.sanitizedCategoryName() }
            .filterValues { it.isNotEmpty() }
    }

    fun onProfileChanged() {
        hasLoaded = false
        _names.value = emptyMap()
        ensureLoaded()
    }

    /** The name to show for [page], falling back to the shipped [defaultLabel]. */
    fun labelFor(page: SettingsPage, defaultLabel: String): String {
        ensureLoaded()
        return _names.value[page.name]?.takeIf { it.isNotEmpty() } ?: defaultLabel
    }

    /**
     * Renames [page], or clears the rename when [name] is blank or is just the shipped label typed
     * back in — an override that matches the default is only there to go stale the next time the
     * label changes.
     */
    fun setName(page: SettingsPage, name: String, defaultLabel: String) {
        ensureLoaded()
        val current = _names.value
        val next = applySettingsCategoryName(
            current = current,
            pageKey = page.name,
            name = name,
            defaultLabel = defaultLabel,
        )
        if (next == current) return
        _names.value = next
        persist()
    }

    fun resetAll() {
        ensureLoaded()
        if (_names.value.isEmpty()) return
        _names.value = emptyMap()
        persist()
    }

    private fun persist() {
        SettingsCategoryNamesStorage.savePayload(
            json.encodeToString(
                StoredSettingsCategoryNamesPayload(
                    // Sorted so the stored payload only changes when a name does.
                    names = _names.value.entries.sortedBy { it.key }.associate { it.key to it.value },
                ),
            ),
        )
    }
}

/**
 * The name map after renaming [pageKey] to [name]. Blank, or the shipped [defaultLabel] typed back
 * in, removes the override instead of storing one that only exists to go stale.
 */
internal fun applySettingsCategoryName(
    current: Map<String, String>,
    pageKey: String,
    name: String,
    defaultLabel: String,
): Map<String, String> {
    val sanitized = name.sanitizedCategoryName()
    return if (sanitized.isEmpty() || sanitized == defaultLabel.sanitizedCategoryName()) {
        current - pageKey
    } else {
        current + (pageKey to sanitized)
    }
}

/** Collapses whitespace and caps the length, so a stray paste cannot break the sidebar layout. */
internal fun String.sanitizedCategoryName(): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .take(SettingsCategoryNameMaxLength)
        // Again after the cut: the cap can land mid-gap and leave a name with a trailing space.
        .trim()
