package com.nuvio.app.features.home

internal expect object HomeCatalogSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    /** For a write made after the fact: the profile the payload was snapshotted under. */
    fun savePayload(payload: String, profileId: Int)
}
