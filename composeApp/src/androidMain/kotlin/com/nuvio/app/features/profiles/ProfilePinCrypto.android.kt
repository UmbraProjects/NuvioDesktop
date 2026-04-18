package com.nuvio.app.features.profiles

import java.security.MessageDigest

actual object ProfilePinCrypto {
    actual fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return digest.joinToString(separator = "") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }
}