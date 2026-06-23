package com.nuvio.app.features.simkl

internal actual object SimklAuthStorage {
    actual fun loadPayload(): String? = null
    actual fun savePayload(payload: String) = Unit
    actual fun clearPayload() = Unit
}

internal actual object SimklSettingsStorage {
    actual fun loadPayload(): String? = null
    actual fun savePayload(payload: String) = Unit
}
