package com.nuvio.app.features.librarypvr

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LibraryPvrStorage {
    private val store = DesktopStorage.store("nuvio_library_pvr")

    actual fun loadSettings(profileId: Int): String? = store.getString("settings_$profileId")
    actual fun saveSettings(profileId: Int, payload: String) = store.putString("settings_$profileId", payload)

    actual fun loadMonitored(profileId: Int): String? = store.getString("monitored_$profileId")
    actual fun saveMonitored(profileId: Int, payload: String) = store.putString("monitored_$profileId", payload)

    actual fun loadGrabs(profileId: Int): String? = store.getString("grabs_$profileId")
    actual fun saveGrabs(profileId: Int, payload: String) = store.putString("grabs_$profileId", payload)
}
