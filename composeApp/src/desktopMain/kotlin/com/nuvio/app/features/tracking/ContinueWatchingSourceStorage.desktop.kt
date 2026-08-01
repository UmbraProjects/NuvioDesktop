package com.nuvio.app.features.tracking

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object ContinueWatchingSourceStorage {
    private val store = DesktopStorage.store("nuvio_tracking_sources")
    private const val selectionKey = "continue_watching_source"
    private const val librarySelectionKey = "library_source"
    private const val calendarSelectionKey = "calendar_source"
    private const val ratingPromptKey = "rating_prompt_enabled"

    actual fun loadSelection(): String? = store.getString(ProfileScopedKey.of(selectionKey))

    actual fun saveSelection(value: String) {
        store.putString(ProfileScopedKey.of(selectionKey), value)
    }

    actual fun loadLibrarySelection(): String? =
        store.getString(ProfileScopedKey.of(librarySelectionKey))

    actual fun saveLibrarySelection(value: String) {
        store.putString(ProfileScopedKey.of(librarySelectionKey), value)
    }

    actual fun loadCalendarSelection(): String? =
        store.getString(ProfileScopedKey.of(calendarSelectionKey))

    actual fun saveCalendarSelection(value: String) {
        store.putString(ProfileScopedKey.of(calendarSelectionKey), value)
    }

    actual fun loadRatingPromptEnabled(): String? =
        store.getString(ProfileScopedKey.of(ratingPromptKey))

    actual fun saveRatingPromptEnabled(value: String) {
        store.putString(ProfileScopedKey.of(ratingPromptKey), value)
    }
}
