package com.nuvio.app.features.profiles

import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
internal data class CachedProfilePinPayload(
    val salt: String,
    val digest: String,
    val profileUpdatedAt: String = "",
)

internal fun generateProfilePinSalt(): String {
    val first = Random.nextLong().toULong().toString(16)
    val second = Random.nextLong().toULong().toString(16)
    return "$first$second"
}

internal fun hashProfilePin(profileIndex: Int, salt: String, pin: String): String =
    ProfilePinCrypto.sha256Hex("profile:$profileIndex:$salt:$pin")