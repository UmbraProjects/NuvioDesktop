package com.spydermahn.nuvioremote

/**
 * What the QR code in Nuvio's Settings › Phone remote holds: the PC's name, port, pairing code and its addresses on the
 * home network, so pairing needs no typing. Plain Kotlin, read the same from the app's own scanner and from a link the
 * phone's camera opens.
 *
 * `nuvioremote://pair?n=<name>&p=<port>&c=<pin>&a=<address>,<address>` — mirrors `pairingLink` in the PC's
 * PhoneRemoteBindings.kt; change both together.
 */
data class PairingLink(val name: String, val port: Int, val pin: String, val hosts: List<String>) {
    companion object {
        const val SCHEME = "nuvioremote"

        fun parse(text: String?): PairingLink? {
            val trimmed = text?.trim() ?: return null
            if (!trimmed.startsWith("$SCHEME://pair?", ignoreCase = true)) return null
            val query = trimmed.substringAfter('?').split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) null else key to RemoteProtocol.decode(pair.substringAfter('='))
            }.toMap()
            val port = query["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val pin = query["c"]?.filter(Char::isDigit)?.takeIf { it.length == 6 } ?: return null
            val hosts = query["a"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 64 }
            if (hosts.isEmpty()) return null
            return PairingLink(name = query["n"]?.takeIf { it.isNotBlank() }?.take(64) ?: "Nuvio", port = port, pin = pin, hosts = hosts)
        }
    }
}
