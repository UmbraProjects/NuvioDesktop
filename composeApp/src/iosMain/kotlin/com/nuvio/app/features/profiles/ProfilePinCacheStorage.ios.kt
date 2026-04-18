package com.nuvio.app.features.profiles

import platform.Foundation.NSUserDefaults

actual object ProfilePinCacheStorage {
    actual fun loadPayload(profileIndex: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey(profileIndex))

    actual fun savePayload(profileIndex: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey(profileIndex))
    }

    actual fun removePayload(profileIndex: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(payloadKey(profileIndex))
    }

    private fun payloadKey(profileIndex: Int): String = "profile_pin_cache_$profileIndex"
}