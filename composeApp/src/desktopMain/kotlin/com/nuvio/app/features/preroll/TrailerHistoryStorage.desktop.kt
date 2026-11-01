package com.nuvio.app.features.preroll

import com.nuvio.app.core.storage.DesktopStorage

internal actual object TrailerHistoryStorage {
    private val store = DesktopStorage.store("nuvio_preshow_trailer_history")

    actual fun load(profileId: Int): String? = store.getString("history_$profileId")

    actual fun save(profileId: Int, json: String?) {
        store.putString("history_$profileId", json)
    }
}
