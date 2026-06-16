package com.nuvio.app.core.storage

internal actual object PlatformLocalAccountDataCleaner {
    actual fun wipe() {
        DesktopStorage.wipe(
            preservedStoreNames = setOf("nuvio_mdblist_ratings_cache"),
        )
    }
}
