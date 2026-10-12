package com.nuvio.app.features.library

import com.nuvio.app.core.storage.DesktopStorage

// Not profile-scoped: these are facts about titles, the same for everyone.
internal actual object LibraryTitleFactsStorage {
    private val store = DesktopStorage.store("nuvio_library_title_facts")
    actual fun loadPayload(): String? = store.getString("facts")
    actual fun savePayload(payload: String) = store.putString("facts", payload)
}
