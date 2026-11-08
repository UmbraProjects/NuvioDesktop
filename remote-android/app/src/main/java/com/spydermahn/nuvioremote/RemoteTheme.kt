package com.spydermahn.nuvioremote

/**
 * The TV's theme, as the PC sends it: `THEME <accent> <accentEnd or -> <background>`, six-digit hex. Plain ARGB ints,
 * no Android or Compose types, so it is read and tested like the rest of the protocol. Mirrors `themeLine` in the
 * PC's PhoneRemoteProtocol.kt.
 */
data class RemoteTheme(val accent: Int, val accentEnd: Int?, val background: Int) {
    /** How it is kept between launches, so the app opens in the TV's colours before it has connected. */
    fun encode(): String = listOf(hex(accent), accentEnd?.let(::hex) ?: "-", hex(background)).joinToString(",")

    companion object {
        /** Nuvio's own default (Ocean), until a PC says otherwise. */
        val Default = RemoteTheme(accent = 0xFF1E88E5.toInt(), accentEnd = null, background = 0xFF0D0D0F.toInt())

        fun parse(parts: List<String>): RemoteTheme? {
            if (parts.size < 4 || parts[0] != "THEME") return null
            return RemoteTheme(
                accent = color(parts[1]) ?: return null,
                accentEnd = color(parts[2]),
                background = color(parts[3]) ?: return null,
            )
        }

        fun decode(text: String?): RemoteTheme? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 3) return null
            return parse(listOf("THEME") + parts)
        }

        private fun color(hex: String): Int? =
            hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }

        private fun hex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)
    }
}
