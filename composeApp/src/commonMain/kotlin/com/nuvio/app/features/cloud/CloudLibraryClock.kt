package com.nuvio.app.features.cloud

internal expect object CloudLibraryClock {
    fun nowEpochMs(): Long

    /**
     * Parses a provider's "added at" timestamp.
     *
     * Debrid providers are not consistent about zone offsets — TorBox returns `...Z` on some
     * endpoints and a bare local-looking timestamp on others — so a parse failure has to be a
     * `null`, never an exception: an unparseable date must leave the item visible, not drop it.
     */
    fun parseIsoDateTimeToEpochMs(value: String): Long?
}
