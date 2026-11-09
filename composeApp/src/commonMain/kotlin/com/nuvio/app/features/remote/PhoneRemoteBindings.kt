package com.nuvio.app.features.remote

import kotlinx.coroutines.flow.StateFlow

/**
 * fork:phone-remote — bridge from the shared settings page to the desktop phone-remote listener,
 * shaped like `GamepadBindings` next door. Only a desktop actual exists.
 */
data class PhoneRemoteState(
    val enabled: Boolean = false,
    /** True once the listener is bound; false while off, or when every port was taken. */
    val listening: Boolean = false,
    val port: Int? = null,
    val pin: String = "",
    /** This PC's home-network addresses, for typing into the phone if it cannot find the PC itself. */
    val addresses: List<String> = emptyList(),
    val pairedDevices: List<String> = emptyList(),
    val connectedDevices: List<String> = emptyList(),
    /** What the phone will call this PC. */
    val pcName: String = "Nuvio",
) {
    /**
     * fork:phone-remote QR pairing — what the QR code in Settings holds: everything the phone would otherwise be told
     * by hand (where to find this PC and its code). The phone's camera opens the app with it; the app's own scanner
     * reads it too. Null while there is nothing to pair with.
     *
     * `nuvioremote://pair?n=<name>&p=<port>&c=<pin>&a=<address>,<address>`, each value URL-encoded. Read by
     * `PairingLink.kt` in the Android app; change both together.
     */
    fun pairingLink(): String? {
        val port = port ?: return null
        if (!listening || pin.isBlank() || addresses.isEmpty()) return null
        return "nuvioremote://pair?n=${encodeQueryValue(pcName)}&p=$port&c=$pin&a=" +
            addresses.joinToString(",") { encodeQueryValue(it) }
    }
}

private fun encodeQueryValue(text: String): String = buildString {
    text.encodeToByteArray().forEach { byte ->
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 0x80 || ch == '-' || ch == '.' || ch == '_' || ch == '~') append(ch)
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
    }
}

/**
 * The QR code for [text] as rows of dark (true) and light modules, without the quiet zone around it; null when it
 * cannot be made. Desktop only (ZXing).
 */
expect fun phoneRemoteQrModules(text: String): List<BooleanArray>?

expect fun ensurePhoneRemoteLoaded()

expect fun phoneRemoteState(): StateFlow<PhoneRemoteState>

expect fun setPhoneRemoteEnabled(enabled: Boolean)

/** A new pairing code; every phone paired so far has to pair again. */
expect fun resetPhoneRemotePairing()
